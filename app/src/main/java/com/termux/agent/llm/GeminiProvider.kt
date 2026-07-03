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

class GeminiProvider(
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

    override fun isConfigured(): Boolean = config.apiKey.isNotBlank()

    override suspend fun generatePlan(
        userInput: String,
        conversation: List<LLMMessage>,
        availableTools: List<ToolDefinition>
    ): LLMPlanResult = withContext(Dispatchers.IO) {
        val contents = buildContents(conversation + LLMMessage("user", userInput))
        val tools = availableTools.map { it.toGeminiTool() }
        val response = callAPI(contents, tools)
        parseResponse(response)
    }

    override suspend fun decideNext(
        stepResult: String,
        conversation: List<LLMMessage>,
        availableTools: List<ToolDefinition>
    ): LLMPlanResult = withContext(Dispatchers.IO) {
        val contents = buildContents(conversation + LLMMessage("user", stepResult))
        val tools = availableTools.map { it.toGeminiTool() }
        val response = callAPI(contents, tools)
        parseResponse(response)
    }

    private fun buildContents(conversation: List<LLMMessage>): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        var currentRole: String? = null
        var currentParts = mutableListOf<Map<String, Any?>>()

        fun flush() {
            if (currentRole != null && currentParts.isNotEmpty()) {
                result.add(mapOf("role" to currentRole, "parts" to currentParts.toList()))
                currentParts = mutableListOf()
            }
        }

        for (msg in conversation) {
            val mappedRole = when (msg.role) {
                "assistant" -> "model"
                "user" -> "user"
                "tool" -> "function"
                else -> "user"
            }

            if (mappedRole != currentRole && currentRole != null) flush()
            currentRole = mappedRole

            when (msg.role) {
                "tool" -> {
                    currentParts.add(mapOf(
                        "functionResponse" to mapOf(
                            "name" to (msg.toolCallId ?: "unknown"),
                            "response" to mapOf("result" to msg.content)
                        )
                    ))
                }
                else -> {
                    currentParts.add(mapOf("text" to msg.content))
                }
            }
        }
        flush()
        return result
    }

    private fun callAPI(
        contents: List<Map<String, Any?>>,
        tools: List<Map<String, Any?>>
    ): String {
        val baseUrl = config.baseUrl.trimEnd('/')
        val urlStr = "$baseUrl/v1beta/models/${config.modelId}:generateContent?key=${config.apiKey}"
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 60000
        conn.readTimeout = 120000

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("contents", JSONArray().apply {
                for (c in contents) {
                    put(JSONObject().apply {
                        put("role", c["role"] as String)
                        put("parts", JSONArray().apply {
                            val parts = c["parts"] as List<*>
                            for (p in parts) {
                                put(JSONObject(p as Map<String, Any?>))
                            }
                        })
                    })
                }
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", config.maxTokens)
                put("temperature", config.temperature)
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
            if (code !in 200..299) throw RuntimeException("Gemini API error $code: $text")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(json: String): LLMPlanResult {
        val obj = JSONObject(json)
        val candidates = obj.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return LLMPlanResult("No response", emptyList(), true, "模型无响应")
        }

        val candidate = candidates.getJSONObject(0)
        val content = candidate.optJSONObject("content")
        val finishReason = candidate.optString("finishReason", "STOP")

        if (content == null) {
            val blockReason = candidate.optString("blockReason", "")
            return LLMPlanResult("Blocked: $blockReason", emptyList(), true, "请求被拦截: $blockReason")
        }

        val parts = content.optJSONArray("parts") ?: JSONArray()
        val textParts = mutableListOf<String>()
        val fnCallParts = mutableListOf<JSONObject>()

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("text")) {
                textParts.add(part.optString("text", ""))
            }
            if (part.has("functionCall")) {
                fnCallParts.add(part.getJSONObject("functionCall"))
            }
        }

        val reasoning = textParts.joinToString("\n")

        if (finishReason == "STOP" || fnCallParts.isEmpty()) {
            return LLMPlanResult(
                reasoning = reasoning,
                toolCalls = emptyList(),
                finished = true,
                finalAnswer = reasoning.ifBlank { "任务完成" }
            )
        }

        val toolCalls = fnCallParts.mapIndexed { idx, fc ->
            val name = fc.optString("name", "")
            val argsRaw = fc.optJSONObject("args") ?: JSONObject()
            ToolCall(
                stepId = "step_${System.currentTimeMillis()}_$idx",
                toolName = mapToolName(name),
                args = parseArgs(argsRaw)
            )
        }

        return LLMPlanResult(reasoning, toolCalls, false)
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

    private fun ToolDefinition.toGeminiTool(): Map<String, Any?> {
        return mapOf(
            "functionDeclarations" to listOf(mapOf(
                "name" to name,
                "description" to description,
                "parameters" to parameters
            ))
        )
    }
}
