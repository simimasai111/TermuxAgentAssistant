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
import java.net.URL
import java.util.zip.ZipFile

class TermuxBootstrapManager(private val context: Context) {

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
        data class Failed(val error: String) : BootstrapState()
    }

    private var _state: BootstrapState = BootstrapState.NotStarted
    val state: BootstrapState get() = _state

    private val arch: String
        get() = when (Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a") {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "i686"
            else -> "aarch64"
        }

    private val bootstrapUrl: String
        get() = "https://github.com/termux/termux-packages/releases/download/bootstrap-archives/bootstrap-$arch.zip"

    suspend fun install(): BootstrapState = withContext(Dispatchers.IO) {
        if (isInstalled) {
            _state = BootstrapState.Complete
            return@withContext _state
        }

        try {
            _state = BootstrapState.Downloading(0)
            val zipFile = downloadBootstrap()

            _state = BootstrapState.Extracting("解压文件系统...")
            val tarXzFile = extractZipToTarXz(zipFile)
            zipFile.delete()

            _state = BootstrapState.Extracting("安装基础系统...")
            extractTarXz(tarXzFile, context.filesDir)
            tarXzFile.delete()

            setupEnvironment()
            statusFile.writeText("done")

            _state = BootstrapState.Complete
            _state
        } catch (e: Exception) {
            _state = BootstrapState.Failed(e.message ?: "Unknown error")
            _state
        }
    }

    private suspend fun downloadBootstrap(): File = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "bootstrap")
        cacheDir.mkdirs()
        val zipFile = File(cacheDir, "bootstrap-$arch.zip")

        if (zipFile.exists() && zipFile.length() > 1_000_000) {
            return@withContext zipFile
        }

        val url = URL(bootstrapUrl)
        val conn = url.openConnection()
        conn.connectTimeout = 30000
        val totalSize = conn.contentLengthLong

        conn.getInputStream().use { input ->
            FileOutputStream(zipFile).use { output ->
                val buffer = ByteArray(8192)
                var downloaded = 0L
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    if (totalSize > 0) {
                        val progress = ((downloaded * 100) / totalSize).toInt()
                        _state = BootstrapState.Downloading(progress)
                    }
                }
            }
        }

        zipFile
    }

    private fun extractZipToTarXz(zipFile: File): File {
        val zip = ZipFile(zipFile)
        val entry = zip.entries().asSequence().firstOrNull { it.name.endsWith(".tar.xz") }
            ?: throw RuntimeException("No .tar.xz found in bootstrap zip")

        val tarXzFile = File(context.cacheDir, "bootstrap.tar.xz")
        zip.getInputStream(entry).use { input ->
            FileOutputStream(tarXzFile).use { output ->
                input.copyTo(output)
            }
        }
        zip.close()
        return tarXzFile
    }

    private fun extractTarXz(tarXzFile: File, destDir: File) {
        FileInputStream(tarXzFile).use { fis ->
            XZCompressorInputStream(fis).use { xzIn ->
                TarArchiveInputStream(xzIn).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextTarEntry
                    while (entry != null) {
                        val outputFile = File(destDir, entry.name)

                        if (entry.isSymbolicLink) {
                            outputFile.parentFile?.mkdirs()
                            try {
                                outputFile.delete()
                                java.nio.file.Files.createSymbolicLink(
                                    outputFile.toPath(),
                                    java.nio.file.Paths.get(entry.linkName)
                                )
                            } catch (_: Exception) {
                            }
                        } else if (entry.isDirectory) {
                            outputFile.mkdirs()
                        } else {
                            outputFile.parentFile?.mkdirs()
                            FileOutputStream(outputFile).use { fos ->
                                tarIn.transferTo(fos)
                            }
                            outputFile.setExecutable(
                                (entry.mode and 64) != 0 ||
                                        (entry.mode and 128) != 0 ||
                                        (entry.mode and 256) != 0,
                                false
                            )
                        }

                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
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
