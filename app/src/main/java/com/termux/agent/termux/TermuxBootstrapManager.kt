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

class TermuxBootstrapManager(
    private val context: Context,
    private val termuxConfig: TermuxConfig = TermuxConfig.load(context)
) {

    val prefix: File get() = File(context.filesDir, "usr")
    val home: File get() = File(context.filesDir, "home")
    val bashPath: String get() = File(prefix, "bin/bash").absolutePath

    val isInstalled: Boolean get() = bashFile.exists() && prefix.exists()
    val hasBundledZip: Boolean get() = context.assets.list("bootstrap")?.any { it.endsWith("-$arch.zip") } == true

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

    suspend fun install(): BootstrapState = withContext(Dispatchers.IO) {
        errorLog.clear()

        if (isInstalled) {
            _state = BootstrapState.Complete
            return@withContext _state
        }

        try {
            if (hasBundledZip) {
                _state = BootstrapState.Extracting("从安装包解压 bootstrap...")
                extractFromAssets()
            } else {
                _state = BootstrapState.Downloading(0)
                errorLog.appendLine("未找到内置 bootstrap，尝试下载...")
                downloadAndExtract()
            }

            _state = BootstrapState.Extracting("配置环境...")
            setupEnvironment()
            statusFile.writeText("done")

            _state = BootstrapState.Complete
            _state
        } catch (e: Exception) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            errorLog.appendLine(sw.toString())
            _state = BootstrapState.Failed(
                error = e.message ?: e.javaClass.simpleName,
                detailLog = errorLog.toString()
            )
            _state
        }
    }

    private fun extractFromAssets() {
        val assetName = "bootstrap/bootstrap-$arch.zip"
        errorLog.appendLine("从 assets 提取: $assetName")

        val zipFile = File(context.cacheDir, "bootstrap-$arch.zip")
        zipFile.parentFile?.mkdirs()

        context.assets.open(assetName).use { input ->
            FileOutputStream(zipFile).use { output ->
                input.copyTo(output)
            }
        }

        errorLog.appendLine("  -> 提取到缓存: ${zipFile.absolutePath} (${zipFile.length()} bytes)")

        verifyAndExtract(zipFile)
        zipFile.delete()
    }

    private suspend fun downloadAndExtract() = withContext(Dispatchers.IO) {
        errorLog.appendLine("解析最新 bootstrap tag...")
        val apiUrl = URL("https://api.github.com/repos/termux/termux-packages/releases?per_page=5")
        val conn = apiUrl.openConnection()
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val json = conn.inputStream.bufferedReader().readText()
        val releases = org.json.JSONArray(json)
        var tag: String? = null
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            val t = r.optString("tag_name", "")
            if (t.startsWith("bootstrap-")) { tag = t; break }
        }
        val resolvedTag = tag ?: throw RuntimeException("无法获取最新 bootstrap 版本")

        val urls = listOf(
            "https://ghproxy.net/https://github.com/termux/termux-packages/releases/download/$resolvedTag/bootstrap-$arch.zip",
            "https://github.com/termux/termux-packages/releases/download/$resolvedTag/bootstrap-$arch.zip"
        )

        val cacheDir = File(context.cacheDir, "bootstrap")
        cacheDir.mkdirs()
        val zipFile = File(cacheDir, "bootstrap-$arch.zip")
        var lastError: Exception? = null

        for (urlStr in urls) {
            errorLog.appendLine("下载: $urlStr")
            try {
                val url = URL(urlStr)
                val c = url.openConnection()
                c.connectTimeout = 15000
                c.readTimeout = 120000
                c.getInputStream().use { input ->
                    FileOutputStream(zipFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (zipFile.length() > 100_000) {
                    errorLog.appendLine("  -> 完成 (${zipFile.length()} bytes)")
                    verifyAndExtract(zipFile)
                    zipFile.delete()
                    return@withContext
                }
                zipFile.delete()
            } catch (e: Exception) {
                lastError = e
                errorLog.appendLine("  -> 失败: ${e.message}")
                zipFile.delete()
            }
        }

        throw lastError ?: RuntimeException("所有下载源失败")
    }

    private fun verifyAndExtract(zipFile: File) {
        errorLog.appendLine("验证 ZIP...")
        try {
            ZipFile(zipFile).close()
            errorLog.appendLine("  -> ZIP 有效")
        } catch (e: ZipException) {
            throw RuntimeException("无效的 ZIP 文件: ${e.message}")
        }

        _state = BootstrapState.Extracting("解压 bootstrap...")
        val zip = ZipFile(zipFile)
        val entries = zip.entries().asSequence().toList()
        errorLog.appendLine("ZIP 条目: ${entries.map { it.name }}")

        val tarXzEntry = entries.firstOrNull { it.name.endsWith(".tar.xz") }
        if (tarXzEntry != null) {
            // 旧格式: zip 内嵌 tar.xz
            val tarXzFile = File(context.cacheDir, "bootstrap.tar.xz")
            zip.getInputStream(tarXzEntry).use { input ->
                FileOutputStream(tarXzFile).use { output -> input.copyTo(output) }
            }
            zip.close()
            errorLog.appendLine("  -> tar.xz 提取完成 (${tarXzFile.length()} bytes)")
            _state = BootstrapState.Extracting("解压文件系统...")
            extractTarXz(tarXzFile, context.filesDir)
            tarXzFile.delete()
        } else {
            // 新格式: zip 直接包含文件系统
            errorLog.appendLine("  -> 检测到新格式，直接从 ZIP 解压文件系统...")
            extractZipDirect(zip, context.filesDir)
            zip.close()
        }
    }

    private fun extractZipDirect(zip: ZipFile, destDir: File) {
        var fileCount = 0; var dirCount = 0; var failCount = 0
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val outputFile = File(destDir, entry.name)
            try {
                if (entry.isDirectory) {
                    outputFile.mkdirs()
                    dirCount++
                } else {
                    outputFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outputFile).use { output -> input.transferTo(output) }
                    }
                    val name = entry.name
                    if (name.startsWith("usr/bin/") || name.startsWith("bin/") ||
                        name.startsWith("usr/libexec/") || name.startsWith("libexec/")
                    ) {
                        outputFile.setExecutable(true, false)
                    }
                    fileCount++
                }
            } catch (e: Exception) {
                failCount++
                if (failCount <= 5) errorLog.appendLine("  -> 解压失败: ${entry.name}: ${e.message}")
            }
        }
        errorLog.appendLine("  -> $fileCount 文件, $dirCount 目录, $failCount 失败")
    }

    private fun extractTarXz(tarXzFile: File, destDir: File) {
        errorLog.appendLine("解压 TAR.XZ -> $destDir")
        var fileCount = 0; var dirCount = 0; var symCount = 0; var failCount = 0

        FileInputStream(tarXzFile).use { fis ->
            XZCompressorInputStream(fis).use { xzIn ->
                TarArchiveInputStream(xzIn).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextTarEntry
                    while (entry != null) {
                        val outputFile = File(destDir, entry.name)
                        try {
                            when {
                                entry.isSymbolicLink -> {
                                    outputFile.parentFile?.mkdirs()
                                    outputFile.delete()
                                    try {
                                        java.nio.file.Files.createSymbolicLink(
                                            outputFile.toPath(), java.nio.file.Paths.get(entry.linkName)
                                        )
                                    } catch (_: Exception) {
                                        val target = File(destDir, entry.linkName)
                                        if (target.exists() && target.isFile)
                                            java.nio.file.Files.copy(target.toPath(), outputFile.toPath())
                                    }
                                    symCount++
                                }
                                entry.isDirectory -> { outputFile.mkdirs(); dirCount++ }
                                else -> {
                                    outputFile.parentFile?.mkdirs()
                                    FileOutputStream(outputFile).use { fos -> tarIn.transferTo(fos) }
                                    if ((entry.mode and 64) != 0 || (entry.mode and 128) != 0 || (entry.mode and 256) != 0)
                                        outputFile.setExecutable(true, false)
                                    fileCount++
                                }
                            }
                        } catch (e: Exception) {
                            failCount++
                            if (failCount <= 5) errorLog.appendLine("  -> 解压失败: ${entry.name}: ${e.message}")
                        }
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
        errorLog.appendLine("  -> $fileCount 文件, $dirCount 目录, $symCount 链接, $failCount 失败")
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
