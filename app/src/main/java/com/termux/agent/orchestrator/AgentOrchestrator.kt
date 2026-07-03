package com.termux.agent.orchestrator

import com.termux.agent.llm.LLMConfig
import com.termux.agent.llm.LLMMessage
import com.termux.agent.llm.LLMPlanResult
import com.termux.agent.llm.LLMProvider
import com.termux.agent.llm.ProviderFactory
import com.termux.agent.llm.ToolDefinitions
import com.termux.agent.model.RiskLevel
import com.termux.agent.model.StepResult
import com.termux.agent.model.StepStatus
import com.termux.agent.model.ToolCall
import com.termux.agent.model.ToolResult
import com.termux.agent.security.CommandPolicy
import com.termux.agent.security.RiskClassifier
import com.termux.agent.tools.ExecuteCommandTool
import com.termux.agent.tools.GetEnvTool
import com.termux.agent.tools.ListDirTool
import com.termux.agent.tools.ReadFileTool
import com.termux.agent.tools.Tool
import com.termux.agent.tools.WriteFileTool
import com.termux.agent.transport.TermuxBridgeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AgentOrchestrator(
    private val bridge: TermuxBridgeClient = TermuxBridgeClient(),
    private val policy: CommandPolicy = CommandPolicy(),
    private val classifier: RiskClassifier = RiskClassifier(policy),
    private val llmProvider: LLMProvider = ProviderFactory.create(LLMConfig())
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _currentPlan = MutableStateFlow<ExecutionPlan?>(null)
    val currentPlan: StateFlow<ExecutionPlan?> = _currentPlan.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val tools: Map<String, Tool> = mapOf(
        "ExecuteCommandTool" to ExecuteCommandTool(bridge, policy),
        "ReadFileTool" to ReadFileTool(),
        "WriteFileTool" to WriteFileTool(),
        "ListDirTool" to ListDirTool(),
        "GetEnvTool" to GetEnvTool(bridge)
    )

    private val conversation = mutableListOf<LLMMessage>()
    private var pendingConfirmation: StepResult? = null

    fun startTask(userInput: String) {
        if (_state.value != AgentState.IDLE && _state.value != AgentState.FINALIZE) return

        if (!llmProvider.isConfigured()) {
            _messages.value = _messages.value + ChatMessage(
                role = MessageRole.SYSTEM,
                content = "请先在设置中配置 API Key"
            )
            return
        }

        val plan = ExecutionPlan(
            taskId = "task_${System.currentTimeMillis()}",
            goal = userInput
        )
        _currentPlan.value = plan
        _messages.value = _messages.value + ChatMessage(
            role = MessageRole.USER,
            content = userInput
        )
        conversation.add(LLMMessage("user", userInput))

        setState(AgentState.PLAN)
        scope.launch { generatePlan() }
    }

    private suspend fun generatePlan() {
        val userInput = conversation.lastOrNull()?.content ?: return
        val result = llmProvider.generatePlan(userInput, conversation, ToolDefinitions.ALL_TOOLS)
        handleLLMResult(result)
    }

    private fun handleLLMResult(result: LLMPlanResult) {
        if (result.finished) {
            val answer = result.finalAnswer ?: result.reasoning
            conversation.add(LLMMessage("assistant", answer))
            _messages.value = _messages.value + ChatMessage(
                role = MessageRole.AGENT,
                content = answer
            )
            finishPlan()
            return
        }

        if (result.toolCalls.isEmpty()) {
            val msg = result.reasoning.ifBlank { "无法生成执行计划" }
            conversation.add(LLMMessage("assistant", msg))
            _messages.value = _messages.value + ChatMessage(
                role = MessageRole.AGENT,
                content = msg
            )
            finishPlan()
            return
        }

        if (result.reasoning.isNotBlank()) {
            conversation.add(LLMMessage("assistant", result.reasoning))
            _messages.value = _messages.value + ChatMessage(
                role = MessageRole.AGENT,
                content = result.reasoning
            )
        }

        val plan = _currentPlan.value ?: return
        for (call in result.toolCalls) {
            plan.steps.add(StepResult(
                stepId = call.stepId,
                toolName = call.toolName,
                args = call.args,
                result = ToolResult(ok = false, exitCode = -1, stdout = "", stderr = "", durationMs = 0),
                status = StepStatus.PENDING,
                riskLevel = RiskLevel.NONE
            ))
        }
        _currentPlan.value = plan

        setState(AgentState.PRECHECK)
        runNextStep()
    }

    private fun runNextStep() {
        val plan = _currentPlan.value ?: return
        val nextStep = plan.steps.find { it.status == StepStatus.PENDING } ?: run {
            finishPlan()
            return
        }

        val tool = tools[nextStep.toolName] ?: run {
            failStep(nextStep.stepId, "Tool not found: ${nextStep.toolName}")
            return
        }

        val risk = classifier.classify(
            (nextStep.args["program"] as? String) ?: "",
            (nextStep.args["args"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        )

        if (risk == RiskLevel.HIGH) {
            pendingConfirmation = nextStep.copy(riskLevel = RiskLevel.HIGH)
            setState(AgentState.AWAITING_CONFIRMATION)
            _messages.value = _messages.value + ChatMessage(
                role = MessageRole.SYSTEM,
                content = "步骤 ${nextStep.stepId} 需要确认: ${nextStep.toolName} args=${nextStep.args}"
            )
            return
        }

        setState(AgentState.EXECUTE)
        scope.launch {
            executeStep(tool, nextStep)
        }
    }

    fun confirmStep() {
        val step = pendingConfirmation ?: return
        pendingConfirmation = null
        val tool = tools[step.toolName] ?: return
        setState(AgentState.EXECUTE)
        scope.launch { executeStep(tool, step) }
    }

    fun rejectStep() {
        val step = pendingConfirmation ?: return
        pendingConfirmation = null
        val plan = _currentPlan.value ?: return
        val idx = plan.steps.indexOfFirst { it.stepId == step.stepId }
        if (idx >= 0) {
            plan.steps[idx] = step.copy(status = StepStatus.CANCELLED)
            _currentPlan.value = plan
            _messages.value = _messages.value + ChatMessage(
                role = MessageRole.SYSTEM,
                content = "步骤 ${step.stepId} 已被用户取消"
            )
        }
        runNextStep()
    }

    private suspend fun executeStep(tool: Tool, step: StepResult) {
        val plan = _currentPlan.value ?: return
        val idx = plan.steps.indexOfFirst { it.stepId == step.stepId }
        if (idx < 0) return

        val runningStep = step.copy(status = StepStatus.RUNNING)
        plan.steps[idx] = runningStep
        _currentPlan.value = plan

        val call = ToolCall(
            stepId = step.stepId,
            toolName = step.toolName,
            args = step.args
        )

        val result = tool.execute(call)
        setState(AgentState.OBSERVE)

        val completedStep = runningStep.copy(
            result = result,
            status = if (result.ok) StepStatus.COMPLETED else StepStatus.FAILED
        )
        plan.steps[idx] = completedStep
        _currentPlan.value = plan

        val stepLog = formatResult(completedStep)
        _messages.value = _messages.value + ChatMessage(
            role = MessageRole.TOOL,
            content = stepLog,
            stepId = step.stepId
        )

        conversation.add(LLMMessage("tool", stepLog, toolCallId = step.stepId))

        setState(AgentState.DECIDE)
        scope.launch { decideNextWithLLM(stepLog) }
    }

    private suspend fun decideNextWithLLM(stepLog: String) {
        val result = llmProvider.decideNext(stepLog, conversation, ToolDefinitions.ALL_TOOLS)
        handleLLMResult(result)
    }

    private fun finishPlan() {
        val plan = _currentPlan.value
        val allCompleted = plan?.steps?.all { it.status == StepStatus.COMPLETED } == true

        val summary = buildString {
            appendLine("任务完成摘要")
            appendLine("目标: ${plan?.goal}")
            appendLine("状态: ${if (allCompleted) "成功" else "部分完成"}")
            appendLine("步骤数: ${plan?.steps?.size ?: 0}")
            plan?.steps?.forEach { step ->
                append("- ${step.stepId} [${step.status}] (${step.result.durationMs}ms)\n")
            }
        }

        _messages.value = _messages.value + ChatMessage(
            role = MessageRole.AGENT,
            content = summary
        )

        setState(AgentState.FINALIZE)
        setState(AgentState.IDLE)
    }

    private fun failStep(stepId: String, reason: String) {
        val plan = _currentPlan.value ?: return
        val idx = plan.steps.indexOfFirst { it.stepId == stepId }
        if (idx >= 0) {
            plan.steps[idx] = plan.steps[idx].copy(
                status = StepStatus.FAILED,
                result = ToolResult.error(reason)
            )
            _currentPlan.value = plan
        }
        finishPlan()
    }

    private fun formatResult(step: StepResult): String {
        val r = step.result
        return buildString {
            appendLine("步骤 ${step.stepId}: ${step.toolName}")
            appendLine("状态: ${step.status} | 退出码: ${r.exitCode} | 耗时: ${r.durationMs}ms")
            if (r.stdout.isNotBlank()) {
                appendLine("--- stdout ---")
                appendLine(r.stdout.take(500))
            }
            if (r.stderr.isNotBlank()) {
                appendLine("--- stderr ---")
                appendLine(r.stderr.take(500))
            }
            if (r.truncated) appendLine("[输出已截断]")
        }
    }

    private fun setState(newState: AgentState) {
        if (_state.value.canTransitionTo(newState)) {
            _state.value = newState
        }
    }
}

data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val stepId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageRole {
    USER,
    AGENT,
    TOOL,
    SYSTEM
}
