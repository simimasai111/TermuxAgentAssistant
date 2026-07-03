package com.termux.agent.termux

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringWriter
import java.io.PrintWriter
import java.net.URL
import java.util.zip.ZipFile
import java.util.zip.ZipException
import org.json.JSONArray

class TermuxBootstrapManager(
    private val context: Context,
    private val termuxConfig: TermuxConfig = TermuxConfig.load(context)
) {

    val prefix: File get() = File(context.filesDir, "usr")
    val home: File get() = File(context.filesDir, "home")
    val bashPath: String get() = File(prefix, "bin/bash").absolutePath

    val isInstalled: Boolean get() = bashFile.exists() && prefix.exists()

    private val bashFile: File get() = File(prefix, "bin/bash")
    private val statusFile: File get() = File(context.filesDir, ".bootstrap_done")

    sealed class BootstrapState {
        data object NotStarted : BootstrapState()
        data class Downloading(val progress: Int) : BootstrapState()
        data class Extracting(val message: String) : BootstrapState()
        data object Complete : BootstrapState()
        data class Failed(val error: String, val detailLog: String = "") : BootstrapState()
    }

    private var _state: BootstrapState = BootstrapState.NotStarted
    val state: BootstrapState get() = _state

    private val errorLog = StringBuilder()

    private val arch: String
        get() = when (Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a") {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "i686"
            else -> "aarch64"
        }

    private var resolvedTag: String? = null

    private suspend fun resolveDownloadUrls(): List<String> = withContext(Dispatchers.IO) {
        val rawConfig = termuxConfig.bootstrapMirrorUrl

        if (rawConfig != "auto") {
            return@withContext listOf(rawConfig.replace("\$arch", arch))
        }

        val tag = resolveLatestTag()
        resolvedTag = tag

        val base = "https://github.com/termux/termux-packages/releases/download/$tag/bootstrap-$arch.zip"
        val mirror = "https://ghproxy.net/https://github.com/termux/termux-packages/releases/download/$tag/bootstrap-$arch.zip"

        listOf(mirror, base)
    }

    private suspend fun resolveLatestTag(): String = withContext(Dispatchers.IO) {
        errorLog.appendLine("解析最新 bootstrap tag...")
        val apiUrl = URL("https://api.github.com/repos/termux/termux-packages/releases?per_page=5")
        val conn = apiUrl.openConnection()
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        val json = conn.inputStream.bufferedReader().readText()
        val releases = org.json.JSONArray(json)

        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            val tag = release.optString("tag_name", "")
            if (tag.startsWith("bootstrap-")) {
                errorLog.appendLine("  -> 找到: $tag")
                return@withContext tag
            }
        }

        throw RuntimeException("无法获取 Termux bootstrap 最新版本")
    }

    suspend fun install(): BootstrapState = withContext(Dispatchers.IO) {
        errorLog.clear()

        if (isInstalled) {
            _state = BootstrapState.Complete
            return@withContext _state
        }

        try {
            _state = BootstrapState.Downloading(0)
            val zipFile = downloadWithFallback()

            _state = BootstrapState.Extracting("解压 ZIP...")
            val tarXzFile = extractZipToTarXz(zipFile)
            zipFile.delete()

            _state = BootstrapState.Extracting("解压基础系统 (XZ/TAR)...")
            extractTarXz(tarXzFile, context.filesDir)
            tarXzFile.delete()

            _state = BootstrapState.Extracting("配置环境...")
            setupEnvironment()
            statusFile.writeText("done")

            _state = BootstrapState.Complete
            _state
        } catch (e: Exception) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val fullTrace = sw.toString()
            errorLog.appendLine(fullTrace)
            _state = BootstrapState.Failed(
                error = e.message ?: e.javaClass.simpleName,
                detailLog = errorLog.toString()
            )
            _state
        }
    }

    private suspend fun downloadWithFallback(): File = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "bootstrap")
        cacheDir.mkdirs()
        val zipFile = File(cacheDir, "bootstrap-$arch.zip")

        if (zipFile.exists() && zipFile.length() > 1_000_000) {
            errorLog.appendLine("使用缓存: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
            return@withContext zipFile
        }

        val urlsToTry = resolveDownloadUrls()
        var lastError: Exception? = null
        var attempt = 0

        for (urlStr in urlsToTry) {
            attempt++
            errorLog.appendLine("[尝试 $attempt] $urlStr")
            try {
                _state = BootstrapState.Downloading(0)
                val url = URL(urlStr)
                val conn = url.openConnection()
                conn.connectTimeout = 15000
                conn.readTimeout = 120000
                val totalSize = conn.contentLengthLong
                errorLog.appendLine("  -> 文件大小: ${totalSize} bytes")

                if (totalSize <= 0) {
                    errorLog.appendLine("  -> 警告: 无法获取文件大小，继续下载")
                }

                conn.getInputStream().use { input ->
                    FileOutputStream(zipFile).use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var bytesRead: Int
                        var lastProgress = -1

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (totalSize > 0) {
                                val progress = ((downloaded * 100) / totalSize).toInt()
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    _state = BootstrapState.Downloading(progress)
                                }
                            }
                        }
                        errorLog.appendLine("  -> 下载完成: $downloaded bytes")
                    }
                }

                if (zipFile.length() < 100_000) {
                    errorLog.appendLine("  -> 文件太小 (${zipFile.length()} bytes)，可能不是有效 ZIP")
                    zipFile.delete()
                    throw RuntimeException("Downloaded file too small (${zipFile.length()} bytes)")
                }

                try {
                    ZipFile(zipFile).close()
                    errorLog.appendLine("  -> ZIP 验证通过")
                } catch (ze: ZipException) {
                    errorLog.appendLine("  -> ZIP 验证失败: ${ze.message}")
                    zipFile.delete()
                    throw RuntimeException("Invalid ZIP file: ${ze.message}")
                }

                return@withContext zipFile
            } catch (e: Exception) {
                lastError = e
                errorLog.appendLine("  -> 失败: ${e.message}")
                if (zipFile.exists()) zipFile.delete()
            }
        }

        val msg = "所有下载源都失败了，最后错误: ${lastError?.message}"
        errorLog.appendLine(msg)
        throw RuntimeException(msg, lastError)
    }

    private fun extractZipToTarXz(zipFile: File): File {
        errorLog.appendLine("解压 ZIP: ${zipFile.absolutePath}")
        val zip = ZipFile(zipFile)
        val entries = zip.entries().asSequence().toList()
        errorLog.appendLine("  -> ZIP 包含 ${entries.size} 个条目: ${entries.map { it.name }}")

        val entry = entries.firstOrNull { it.name.endsWith(".tar.xz") }
            ?: throw RuntimeException("ZIP 中没有找到 .tar.xz 文件。条目: ${entries.map { it.name }}")

        errorLog.appendLine("  -> 找到: ${entry.name} (${entry.compressedSize} -> ${entry.size} bytes)")

        val tarXzFile = File(context.cacheDir, "bootstrap.tar.xz")
        zip.getInputStream(entry).use { input ->
            FileOutputStream(tarXzFile).use { output ->
                input.copyTo(output)
            }
        }
        zip.close()
        errorLog.appendLine("  -> 提取完成: ${tarXzFile.absolutePath} (${tarXzFile.length()} bytes)")
        return tarXzFile
    }

    private fun extractTarXz(tarXzFile: File, destDir: File) {
        errorLog.appendLine("解压 TAR.XZ -> $destDir")
        var fileCount = 0
        var dirCount = 0
        var symCount = 0
        var failCount = 0

        FileInputStream(tarXzFile).use { fis ->
            XZCompressorInputStream(fis).use { xzIn ->
                TarArchiveInputStream(xzIn).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextTarEntry
                    while (entry != null) {
                        val outputFile = File(destDir, entry.name)

                        try {
                            if (entry.isSymbolicLink) {
                                outputFile.parentFile?.mkdirs()
                                outputFile.delete()
                                try {
                                    java.nio.file.Files.createSymbolicLink(
                                        outputFile.toPath(),
                                        java.nio.file.Paths.get(entry.linkName)
                                    )
                                    symCount++
                                } catch (e: Exception) {
                                    outputFile.delete()
                                    val target = File(destDir, entry.linkName)
                                    if (target.exists() && target.isFile) {
                                        java.nio.file.Files.copy(target.toPath(), outputFile.toPath())
                                    }
                                    symCount++
                                }
                            } else if (entry.isDirectory) {
                                outputFile.mkdirs()
                                dirCount++
                            } else {
                                outputFile.parentFile?.mkdirs()
                                FileOutputStream(outputFile).use { fos ->
                                    tarIn.transferTo(fos)
                                }
                                val isExec = (entry.mode and 64) != 0 ||
                                        (entry.mode and 128) != 0 ||
                                        (entry.mode and 256) != 0
                                if (isExec) {
                                    outputFile.setExecutable(true, false)
                                }
                                fileCount++
                            }
                        } catch (e: Exception) {
                            failCount++
                            if (failCount <= 5) {
                                errorLog.appendLine("  -> 解压失败: ${entry.name}: ${e.message}")
                            }
                        }

                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }

        errorLog.appendLine("  -> 完成: $fileCount 文件, $dirCount 目录, $symCount 链接, $failCount 失败")
    }

    private fun setupEnvironment() {
        home.mkdirs()

        val bashrc = File(home, ".bashrc")
        if (!bashrc.exists()) {
            bashrc.writeText("""
export PREFIX=$prefix
export HOME=$home
export PATH=$prefix/bin:${'$'}PATH
export LD_LIBRARY_PATH=$prefix/lib
export TMPDIR=$prefix/tmp
cd ${'$'}HOME
""".trimIndent())
        }

        File(prefix, "tmp").mkdirs()
        errorLog.appendLine("环境配置完成")
    }

    fun environmentVars(): Map<String, String> = mapOf(
        "PREFIX" to prefix.absolutePath,
        "HOME" to home.absolutePath,
        "PATH" to "${prefix.absolutePath}/bin:/system/bin:/system/xbin",
        "LD_LIBRARY_PATH" to "${prefix.absolutePath}/lib",
        "TMPDIR" to "${prefix.absolutePath}/tmp",
        "TERMUX_VERSION" to "0.118.0",
        "TERM" to "xterm-256color",
        "BOOTSTRAP_DONE" to "1"
    )
}
