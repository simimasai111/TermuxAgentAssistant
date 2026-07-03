package com.termux.agent.tools

import com.termux.agent.model.ToolCall
import com.termux.agent.model.ToolResult
import com.termux.agent.security.SandboxPathMapper
import java.io.File

class ReadFileTool(
    private val pathMapper: SandboxPathMapper = SandboxPathMapper(),
    private val maxFileSize: Long = 200_000L
) : Tool {

    override val name: String = "ReadFileTool"

    override suspend fun execute(call: ToolCall): ToolResult {
        val startTime = System.currentTimeMillis()
        val path = call.args["path"] as? String ?: return ToolResult.error("Missing 'path' argument")

        val resolved = pathMapper.resolve(path)
        if (resolved.isFailure) {
            return ToolResult.error(resolved.exceptionOrNull()?.message ?: "Path resolution failed")
        }

        val file = File(resolved.getOrThrow())

        return try {
            if (!file.exists()) {
                return ToolResult.error("File not found: $path", exitCode = 1)
            }
            if (file.isDirectory) {
                return ToolResult.error("Path is a directory, not a file: $path", exitCode = 1)
            }
            if (file.length() > maxFileSize) {
                val content = file.readText().take(maxFileSize.toInt())
                val duration = System.currentTimeMillis() - startTime
                return ToolResult(
                    ok = true,
                    exitCode = 0,
                    stdout = content + "\n... [file truncated at ${maxFileSize} bytes]",
                    stderr = "",
                    durationMs = duration,
                    metadata = mapOf("path" to path, "truncated" to true),
                    truncated = true
                )
            }

            val content = file.readText()
            val duration = System.currentTimeMillis() - startTime
            ToolResult(
                ok = true,
                exitCode = 0,
                stdout = content,
                stderr = "",
                durationMs = duration,
                metadata = mapOf("path" to path, "size_bytes" to file.length())
            )
        } catch (e: Exception) {
            ToolResult.error("Failed to read file: ${e.message}")
        }
    }
}
