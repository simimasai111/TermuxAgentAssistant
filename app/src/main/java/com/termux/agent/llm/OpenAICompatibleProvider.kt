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

class OpenAICompatibleProvider(
    override val config: LLMConfig
) : LLMProvider {

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

    override fun isConfigured(): Boolean {
        return config.apiKey.isNotBlank()
    }

    override suspend fun generatePlan(
        userInput: String,
        conversation: List<LLMMessage>,
        availableTools: List<ToolDefinition>
    ): LLMPlanResult = withContext(Dispatchers.IO) {
        val messages = buildMessages(conversation + LLMMessage("user", userInput))
        val tools = availableTools.map { it.toJsonMap() }
        val response = callChatAPI(messages, tools)
        parseResponse(response)
    }

    override suspend fun decideNext(
        stepResult: String,
        conversation: List<LLMMessage>,
        availableTools: List<ToolDefinition>
    ): LLMPlanResult = withContext(Dispatchers.IO) {
        val messages = buildMessages(conversation + LLMMessage("tool", stepResult))
        val tools = availableTools.map { it.toJsonMap() }
        val response = callChatAPI(messages, tools)
        parseResponse(response)
    }

    private fun buildMessages(conversation: List<LLMMessage>): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>(
            mapOf("role" to "system", "content" to systemPrompt)
        )
        for (msg in conversation) {
            when (msg.role) {
                "tool" -> {
                    result.add(mapOf(
                        "role" to "tool",
                        "tool_call_id" to (msg.toolCallId ?: "call_${System.currentTimeMillis()}"),
                        "content" to msg.content
                    ))
                }
                else -> {
                    result.add(mapOf("role" to msg.role, "content" to msg.content))
                }
            }
        }
        return result
    }

    private fun callChatAPI(
        messages: List<Map<String, Any?>>,
        tools: List<Map<String, Any?>>
    ): String {
        val url = URL("${config.baseUrl.trimEnd('/')}/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        conn.doOutput = true
        conn.connectTimeout = 60000
        conn.readTimeout = 120000

        val body = JSONObject().apply {
            put("model", config.modelId)
            put("messages", JSONArray().apply {
                for (msg in messages) {
                    put(JSONObject().apply {
                        put("role", msg["role"] as String)
                        put("content", msg["content"] as String)
                        if (msg.containsKey("tool_call_id")) {
                            put("tool_call_id", msg["tool_call_id"] as String)
                        }
                    })
                }
            })
            put("max_tokens", config.maxTokens)
            put("temperature", config.temperature)
            if (tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    for (tool in tools) {
                        put(JSONObject(tool as Map<String, Any?>))
                    }
                })
            }
        }

        try {
            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(body.toString())
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val reader = BufferedReader(InputStreamReader(stream))
            val response = reader.readText()
            reader.close()

            if (responseCode !in 200..299) {
                throw RuntimeException("API error $responseCode: $response")
            }

            return response
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(json: String): LLMPlanResult {
        val obj = JSONObject(json)
        val choices = obj.getJSONArray("choices")
        if (choices.length() == 0) {
            return LLMPlanResult(
                reasoning = "No response from model",
                toolCalls = emptyList(),
                finished = true,
                finalAnswer = "模型无响应"
            )
        }

        val choice = choices.getJSONObject(0)
        val message = choice.getJSONObject("message")
        val content = message.optString("content", "")
        val finishReason = choice.optString("finish_reason", "stop")

        if (finishReason == "stop" || content.isNotBlank()) {
            return LLMPlanResult(
                reasoning = content,
                toolCalls = emptyList(),
                finished = true,
                finalAnswer = content
            )
        }

        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null && toolCalls.length() > 0) {
            val calls = mutableListOf<ToolCall>()
            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.getJSONObject(i)
                val function = tc.getJSONObject("function")
                val name = function.getString("name")
                val argsStr = function.optString("arguments", "{}")

                val args = try {
                    parseArgs(JSONObject(argsStr))
                } catch (e: Exception) {
                    mapOf("raw" to argsStr)
                }

                calls.add(ToolCall(
                    stepId = "step_${System.currentTimeMillis()}_$i",
                    toolName = mapToolName(name),
                    args = args
                ))
            }

            return LLMPlanResult(
                reasoning = content,
                toolCalls = calls,
                finished = false
            )
        }

        return LLMPlanResult(
            reasoning = content,
            toolCalls = emptyList(),
            finished = true,
            finalAnswer = content.ifBlank { "任务完成" }
        )
    }

    private fun parseArgs(obj: JSONObject): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            val value = obj.get(key)
            result[key] = when (value) {
                is JSONArray -> {
                    (0 until value.length()).map { value.get(it).toString() }
                }
                is JSONObject -> value.toString()
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
}
