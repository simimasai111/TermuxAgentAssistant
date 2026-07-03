package com.termux.agent.model

data class ToolResult(
    val ok: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val metadata: Map<String, Any?> = emptyMap(),
    val truncated: Boolean = false
) {
    companion object {
        fun error(message: String, exitCode: Int = -1, durationMs: Long = 0): ToolResult = ToolResult(
            ok = false,
            exitCode = exitCode,
            stdout = "",
            stderr = message,
            durationMs = durationMs,
            metadata = mapOf("error" to message)
        )

        fun timeout(durationMs: Long): ToolResult = ToolResult(
            ok = false,
            exitCode = -1,
            stdout = "",
            stderr = "Command timed out after ${durationMs}ms",
            durationMs = durationMs,
            metadata = mapOf("timeout" to true)
        )
    }
}
