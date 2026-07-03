package com.termux.agent.llm

import com.termux.agent.model.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class AnthropicProvider(
    override val config: LLMConfig
) : LLMProvider {

    private val apiVersion = "2023-06-01"

    private val systemPrompt = buildString {
        appendLine("你是运行在 Android Termux 环境中的 AI Agent 助手。")
        appendLine("你的任务是将用户的自然语言需求转化为可执行的 Linux 命令或文件操作。")
        appendLine("")
        appendLine("## 可用工具")
        appendLine("- execute_command: 执行 Linux 命令 (apt, python3, bash 等)")
        appendLine("- read_file: 读取文件内容")
        appendLine("- write_file: 写入文件内容")
        appendLine("- list_dir: 列出目录")
        appendLine("- get_env: 获取环境信息")
        appendLine("")
        appendLine("## 执行规则")
        appendLine("1. 能在一个步骤完成的不要拆成多步")
        appendLine("2. 安装软件前先检查是否已存在")
        appendLine("3. 命令执行失败后分析错误并尝试修复")
        appendLine("4. 文件路径默认使用沙箱根目录")
        appendLine("5. 最终给出清晰的任务完成摘要")
        appendLine("")
        appendLine("## 安全限制")
        appendLine("- 不要尝试访问 /etc, /proc, /sys 等系统目录")
        appendLine("- 不要使用 sudo, su, dd, mkfs 等危险命令")
        appendLine("- 删除操作要谨慎")
    }

    override fun isConfigured(): Boolean = config.apiKey.isNotBlank()

    override suspend fun generatePlan(
        userInput: String,
        conversation: List<LLMMessage>,
        availableTools: List<ToolDefinition>
    ): LLMPlanResult = withContext(Dispatchers.IO) {
        val messages = buildMessages(conversation + LLMMessage("user", userInput))
        val tools = availableTools.map { it.toAnthropicTool() }
        val response = callAPI(messages, tools)
        parseResponse(response)
    }

    override suspend fun decideNext(
        stepResult: String,
        conversation: List<LLMMessage>,
        availableTools: List<ToolDefinition>
    ): LLMPlanResult = withContext(Dispatchers.IO) {
        val messages = buildMessages(conversation + LLMMessage("user", stepResult))
        val tools = availableTools.map { it.toAnthropicTool() }
        val response = callAPI(messages, tools)
        parseResponse(response)
    }

    private fun buildMessages(conversation: List<LLMMessage>): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        var currentRole: String? = null
        var currentContent = mutableListOf<Map<String, Any?>>()

        fun flush() {
            if (currentRole != null && currentContent.isNotEmpty()) {
                result.add(mapOf("role" to currentRole, "content" to currentContent.toList()))
                currentContent = mutableListOf()
            }
        }

        for (msg in conversation) {
            val mappedRole = when (msg.role) {
                "assistant" -> "assistant"
                "user" -> "user"
                "tool" -> "user"
                else -> "user"
            }

            if (mappedRole != currentRole && currentRole != null) flush()

            currentRole = mappedRole

            when (msg.role) {
                "tool" -> {
                    currentContent.add(mapOf(
                        "type" to "tool_result",
                        "tool_use_id" to (msg.toolCallId ?: "tc_${System.currentTimeMillis()}"),
                        "content" to msg.content
                    ))
                }
                else -> {
                    currentContent.add(mapOf(
                        "type" to "text",
                        "text" to msg.content
                    ))
                }
            }
        }
        flush()
        return result
    }

    private fun callAPI(
        messages: List<Map<String, Any?>>,
        tools: List<Map<String, Any?>>
    ): String {
        val baseUrl = config.baseUrl.trimEnd('/')
        val url = URL("$baseUrl/v1/messages")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-api-key", config.apiKey)
        conn.setRequestProperty("anthropic-version", apiVersion)
        conn.doOutput = true
        conn.connectTimeout = 60000
        conn.readTimeout = 120000

        val body = JSONObject().apply {
            put("model", config.modelId)
            put("max_tokens", config.maxTokens)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                for (msg in messages) {
                    put(JSONObject().apply {
                        put("role", msg["role"] as String)
                        put("content", JSONArray().apply {
                            val contentList = msg["content"] as List<*>
                            for (block in contentList) {
                                put(JSONObject(block as Map<String, Any?>))
                            }
                        })
                    })
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    for (t in tools) put(JSONObject(t))
                })
            }
        }

        try {
            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(body.toString())
            writer.flush()
            writer.close()

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream)).readText()
            if (code !in 200..299) throw RuntimeException("Anthropic API error $code: $text")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(json: String): LLMPlanResult {
        val obj = JSONObject(json)
        val content = obj.optJSONArray("content") ?: JSONArray()
        val stopReason = obj.optString("stop_reason", "end_turn")

        val textBlocks = mutableListOf<String>()
        val toolUseBlocks = mutableListOf<JSONObject>()

        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            when (block.optString("type")) {
                "text" -> textBlocks.add(block.optString("text", ""))
                "tool_use" -> toolUseBlocks.add(block)
            }
        }

        val reasoning = textBlocks.joinToString("\n")

        if (stopReason == "end_turn" || (toolUseBlocks.isEmpty() && stopReason != "tool_use")) {
            return LLMPlanResult(
                reasoning = reasoning,
                toolCalls = emptyList(),
                finished = true,
                finalAnswer = reasoning.ifBlank { "任务完成" }
            )
        }

        val toolCalls = toolUseBlocks.mapIndexed { idx, block ->
            val name = block.optString("name", "")
            val argsRaw = block.optJSONObject("input") ?: JSONObject()
            val toolCallId = block.optString("id", "tc_${System.currentTimeMillis()}_$idx")

            ToolCall(
                stepId = "step_${System.currentTimeMillis()}_$idx",
                toolName = mapToolName(name),
                args = parseArgs(argsRaw)
            )
        }

        return LLMPlanResult(
            reasoning = reasoning,
            toolCalls = toolCalls,
            finished = false
        )
    }

    private fun parseArgs(obj: JSONObject): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            val value = obj.get(key)
            result[key] = when (value) {
                is JSONArray -> (0 until value.length()).map { value.get(it).toString() }
                else -> value.toString()
            }
        }
        return result
    }

    private fun mapToolName(llmName: String): String {
        return when (llmName) {
            "execute_command" -> "ExecuteCommandTool"
            "read_file" -> "ReadFileTool"
            "write_file" -> "WriteFileTool"
            "list_dir" -> "ListDirTool"
            "get_env" -> "GetEnvTool"
            else -> llmName
        }
    }

    private fun ToolDefinition.toAnthropicTool(): Map<String, Any?> {
        return mapOf(
            "name" to name,
            "description" to description,
            "input_schema" to parameters
        )
    }
}
