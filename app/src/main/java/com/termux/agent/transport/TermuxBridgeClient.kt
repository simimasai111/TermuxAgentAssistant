package com.termux.agent.transport

import com.termux.agent.model.OutputLimits
import com.termux.agent.model.ToolResult
import com.termux.agent.termux.TermuxBootstrapManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class TermuxBridgeClient(
    private val bootstrapManager: TermuxBootstrapManager? = null
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }

    data class ExecutionRequest(
        val command: String,
        val args: List<String> = emptyList(),
        val workDir: String = ".",
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val env: Map<String, String> = emptyMap(),
        val limits: OutputLimits = OutputLimits(),
        val stdin: String = ""
    )

    suspend fun execute(request: ExecutionRequest): ToolResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        return@withContext try {
            val bashPath = bootstrapManager?.bashPath ?: "bash"
            val termuxEnv = bootstrapManager?.environmentVars() ?: emptyMap()

            val processBuilder = ProcessBuilder(
                bashPath, "-c",
                buildCommandString(request.command, request.args)
            )

            val workDirFile = if (request.workDir.startsWith("/")) {
                File(request.workDir)
            } else {
                bootstrapManager?.let { File(it.home, request.workDir) } ?: File(request.workDir)
            }
            processBuilder.directory(workDirFile)

            val env = processBuilder.environment()
            env.putAll(termuxEnv)
            env.putAll(request.env)

            val process = processBuilder.start()

            if (request.stdin.isNotEmpty()) {
                process.outputStream.write(request.stdin.toByteArray())
                process.outputStream.flush()
                process.outputStream.close()
            }

            val finished = process.waitFor(request.timeoutMs, TimeUnit.MILLISECONDS)
            val duration = System.currentTimeMillis() - startTime

            if (!finished) {
                process.destroyForcibly()
                return@withContext ToolResult.timeout(duration)
            }

            val stdout = readStream(process.inputStream, request.limits.maxStdoutBytes)
            val stderr = readStream(process.errorStream, request.limits.maxStderrBytes)
            val exitCode = process.exitValue()

            val truncated = (stdout.length.toLong() >= request.limits.maxStdoutBytes) ||
                    (stderr.length.toLong() >= request.limits.maxStderrBytes)

            ToolResult(
                ok = exitCode == 0,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                durationMs = duration,
                metadata = mapOf(
                    "command" to request.command,
                    "embedded_termux" to (bootstrapManager?.isInstalled == true),
                    "truncated" to truncated
                ),
                truncated = truncated
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            ToolResult.error("Execution failed: ${e.message}", durationMs = duration)
        }
    }

    private fun buildCommandString(command: String, args: List<String>): String {
        return if (args.isEmpty()) {
            command
        } else {
            val escapedArgs = args.joinToString(" ") { arg ->
                if (arg.contains(" ") || arg.contains("\"") || arg.contains("'")) {
                    "'${arg.replace("'", "'\\''")}'"
                } else {
                    arg
                }
            }
            "$command $escapedArgs"
        }
    }

    private fun readStream(stream: java.io.InputStream, maxBytes: Long): String {
        val reader = InputStreamReader(stream)
        val buffer = CharArray(4096)
        val output = StringBuilder()
        var totalRead = 0L

        try {
            var bytesRead = reader.read(buffer)
            while (bytesRead != -1) {
                totalRead += bytesRead
                if (totalRead > maxBytes) {
                    output.append(buffer, 0, (maxBytes - (totalRead - bytesRead)).toInt())
                    output.append("\n... [output truncated at ${maxBytes} bytes]")
                    break
                }
                output.append(buffer, 0, bytesRead)
                bytesRead = reader.read(buffer)
            }
        } finally {
            reader.close()
        }

        return output.toString()
    }
}
