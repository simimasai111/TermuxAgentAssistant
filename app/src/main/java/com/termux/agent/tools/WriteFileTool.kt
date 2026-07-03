package com.termux.agent.tools

import com.termux.agent.model.ToolCall
import com.termux.agent.model.ToolResult
import com.termux.agent.security.SandboxPathMapper
import java.io.File

class WriteFileTool(
    private val pathMapper: SandboxPathMapper = SandboxPathMapper(),
    private val maxFileSize: Long = 1_000_000L
) : Tool {

    override val name: String = "WriteFileTool"

    override suspend fun execute(call: ToolCall): ToolResult {
        val startTime = System.currentTimeMillis()
        val path = call.args["path"] as? String ?: return ToolResult.error("Missing 'path' argument")
        val content = call.args["content"] as? String ?: return ToolResult.error("Missing 'content' argument")

        if (content.length.toLong() > maxFileSize) {
            return ToolResult.error("Content exceeds max file size of ${maxFileSize} bytes")
        }

        val resolved = pathMapper.resolve(path)
        if (resolved.isFailure) {
            return ToolResult.error(resolved.exceptionOrNull()?.message ?: "Path resolution failed")
        }

        val file = File(resolved.getOrThrow())

        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            val duration = System.currentTimeMillis() - startTime
            ToolResult(
                ok = true,
                exitCode = 0,
                stdout = "Written ${content.length} bytes to $path",
                stderr = "",
                durationMs = duration,
                metadata = mapOf("path" to path, "bytes_written" to content.length)
            )
        } catch (e: Exception) {
            ToolResult.error("Failed to write file: ${e.message}")
        }
    }
}
