package com.termux.agent.tools

import com.termux.agent.model.ToolCall
import com.termux.agent.model.ToolResult
import com.termux.agent.security.SandboxPathMapper
import java.io.File

class ListDirTool(
    private val pathMapper: SandboxPathMapper = SandboxPathMapper()
) : Tool {

    override val name: String = "ListDirTool"

    override suspend fun execute(call: ToolCall): ToolResult {
        val startTime = System.currentTimeMillis()
        val path = call.args["path"] as? String ?: "."

        val resolved = pathMapper.resolve(path)
        if (resolved.isFailure) {
            return ToolResult.error(resolved.exceptionOrNull()?.message ?: "Path resolution failed")
        }

        val dir = File(resolved.getOrThrow())

        return try {
            if (!dir.exists()) {
                return ToolResult.error("Directory not found: $path", exitCode = 1)
            }
            if (!dir.isDirectory) {
                return ToolResult.error("Path is not a directory: $path", exitCode = 1)
            }

            val entries = dir.listFiles()
            val output = buildString {
                entries?.forEach { entry ->
                    val type = if (entry.isDirectory) "d" else "-"
                    val size = entry.length()
                    val lastModified = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(entry.lastModified()))
                    appendLine("$type  $lastModified  ${size.toString().padStart(10)}  ${entry.name}")
                }
                appendLine("Total: ${entries?.size ?: 0} entries")
            }

            val duration = System.currentTimeMillis() - startTime
            ToolResult(
                ok = true,
                exitCode = 0,
                stdout = output,
                stderr = "",
                durationMs = duration,
                metadata = mapOf("path" to path, "entry_count" to (entries?.size ?: 0))
            )
        } catch (e: Exception) {
            ToolResult.error("Failed to list directory: ${e.message}")
        }
    }
}
