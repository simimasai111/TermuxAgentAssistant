package com.termux.agent.tools

import com.termux.agent.model.ToolCall
import com.termux.agent.model.ToolResult
import com.termux.agent.transport.TermuxBridgeClient

class GetEnvTool(
    private val bridge: TermuxBridgeClient = TermuxBridgeClient()
) : Tool {

    override val name: String = "GetEnvTool"

    override suspend fun execute(call: ToolCall): ToolResult {
        val startTime = System.currentTimeMillis()

        val envResult = bridge.execute(
            TermuxBridgeClient.ExecutionRequest(
                command = "printenv",
                timeoutMs = 10_000L
            )
        )

        if (!envResult.ok) {
            val fallback = bridge.execute(
                TermuxBridgeClient.ExecutionRequest(
                    command = "env",
                    timeoutMs = 10_000L
                )
            )
            return fallback.copy(
                metadata = mapOf(
                    "tool" to name,
                    "source" to "env"
                )
            )
        }

        val filtered = envResult.stdout.lines()
            .filterNot { line ->
                val key = line.substringBefore("=")
                SENSITIVE_ENV_KEYS.any { key.contains(it, ignoreCase = true) }
            }
            .joinToString("\n")

        val duration = System.currentTimeMillis() - startTime
        return ToolResult(
            ok = true,
            exitCode = 0,
            stdout = filtered,
            stderr = "",
            durationMs = duration,
            metadata = mapOf(
                "tool" to name,
                "source" to "printenv",
                "sensitive_filtered" to true
            )
        )
    }

    companion object {
        val SENSITIVE_ENV_KEYS = setOf(
            "KEY", "SECRET", "TOKEN", "PASSWORD", "PASS", "API_KEY",
            "ACCESS_KEY", "PRIVATE", "CREDENTIAL", "AUTH"
        )
    }
}
