package com.termux.agent.security

import java.io.File

class SandboxPathMapper(
    private val sandboxRoot: String = DEFAULT_SANDBOX_ROOT
) {
    companion object {
        const val DEFAULT_SANDBOX_ROOT = "/data/data/com.termux.agent/termux-sandbox"
    }

    fun resolve(userPath: String): Result<String> {
        return try {
            val cleaned = userPath.trimStart(' ', '\t')
            val absolute = if (cleaned.startsWith("/")) cleaned else "$sandboxRoot/$cleaned"
            val canonical = File(absolute).canonicalPath
            val sandboxCanonical = File(sandboxRoot).canonicalPath

            if (!canonical.startsWith(sandboxCanonical)) {
                Result.failure(SecurityException("Path escape detected: $userPath -> $canonical"))
            } else {
                Result.success(canonical)
            }
        } catch (e: Exception) {
            Result.failure(SecurityException("Path resolution failed: ${e.message}"))
        }
    }

    fun isWithinSandbox(absolutePath: String): Boolean {
        return try {
            val canonical = File(absolutePath).canonicalPath
            val sandboxCanonical = File(sandboxRoot).canonicalPath
            canonical.startsWith(sandboxCanonical)
        } catch (e: Exception) {
            false
        }
    }

    fun sandboxPath(relativePath: String): String = "$sandboxRoot/$relativePath"
}
