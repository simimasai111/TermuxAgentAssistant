package com.termux.agent.tools

import com.termux.agent.model.ToolCall
import com.termux.agent.model.ToolResult
import com.termux.agent.security.CommandParser
import com.termux.agent.security.CommandPolicy
import com.termux.agent.security.RiskClassifier
import com.termux.agent.transport.TermuxBridgeClient

class ExecuteCommandTool(
    private val bridge: TermuxBridgeClient,
    private val policy: CommandPolicy = CommandPolicy(),
    private val parser: CommandParser = CommandParser(),
    private val classifier: RiskClassifier = RiskClassifier(policy)
) : Tool {

    override val name: String = "ExecuteCommandTool"

    data class CommandArgs(
        val program: String,
        val args: List<String> = emptyList(),
        val workDir: String = ".",
        val timeoutMs: Long = TermuxBridgeClient.DEFAULT_TIMEOUT_MS,
        val env: Map<String, String> = emptyMap(),
        val stdin: String = ""
    )

    override suspend fun execute(call: ToolCall): ToolResult {
        val program = call.args["program"] as? String ?: return ToolResult.error("Missing 'program' argument")
        val cmdArgs = (call.args["args"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val workDir = call.args["workDir"] as? String ?: "."
        val timeoutMs = (call.args["timeoutMs"] as? Number)?.toLong() ?: TermuxBridgeClient.DEFAULT_TIMEOUT_MS
        val env = (call.args["env"] as? Map<*, *>)?.filterKeys { it is String }?.mapKeys { it.key.toString() }?.mapValues { it.value.toString() } ?: emptyMap()
        val stdin = call.args["stdin"] as? String ?: ""

        val policyResult = policy.check(program, cmdArgs)
        if (policyResult is CommandPolicy.PolicyResult.DENIED) {
            return ToolResult.error(policyResult.reason, exitCode = -1)
        }

        val parsed = parser.parse(program, cmdArgs)
        val warnings = parser.analyze(parsed)
        val risk = classifier.classify(program, cmdArgs)

        val request = TermuxBridgeClient.ExecutionRequest(
            command = program,
            args = cmdArgs,
            workDir = workDir,
            timeoutMs = timeoutMs,
            env = env,
            stdin = stdin
        )

        val result = bridge.execute(request)

        val metadata = mutableMapOf<String, Any?>(
            "risk_level" to risk.name,
            "warnings" to warnings,
            "policy_result" to policyResult::class.simpleName
        )
        metadata.putAll(result.metadata)

        return result.copy(metadata = metadata)
    }
}
