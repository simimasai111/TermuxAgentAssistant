package com.termux.agent.security

data class CommandPolicy(
    val allowlist: Set<String> = DEFAULT_ALLOWLIST,
    val blocklist: Set<String> = DEFAULT_BLOCKLIST,
    val dangerousCommands: Set<String> = DEFAULT_DANGEROUS,
    val maxArgsLength: Int = 1024,
    val enableStaticAnalysis: Boolean = true,
    val requireConfirmationForDangerous: Boolean = true
) {
    fun check(program: String, args: List<String>): PolicyResult {
        val base = program.split("/").last()

        if (base in blocklist) {
            return PolicyResult.DENIED("Command '$base' is blocked")
        }
        if (base !in allowlist) {
            return PolicyResult.DENIED("Command '$base' is not in allowlist")
        }
        if (args.joinToString(" ").length > maxArgsLength) {
            return PolicyResult.DENIED("Argument string exceeds max length of $maxArgsLength")
        }
        if (base in dangerousCommands && requireConfirmationForDangerous) {
            return PolicyResult.NEEDS_CONFIRMATION("Command '$base' requires user confirmation")
        }
        return PolicyResult.ALLOWED
    }

    companion object {
        val DEFAULT_ALLOWLIST = setOf(
            "bash", "sh", "apt-get", "apt", "python3", "python", "pip", "pip3",
            "ls", "cat", "head", "tail", "pwd", "mkdir", "cp", "mv",
            "rm", "grep", "sed", "awk", "tar", "unzip", "zip",
            "echo", "printf", "which", "type", "env", "printenv",
            "chmod", "chown", "find", "wc", "sort", "uniq", "cut",
            "tee", "diff", "cmp", "date", "whoami", "id", "uname"
        )

        val DEFAULT_BLOCKLIST = setOf(
            "sudo", "su", "chroot", "mount", "umount",
            "reboot", "shutdown", "halt", "poweroff",
            "dd", "mkfs", "fdisk", "parted",
            "iptables", "ufw", "systemctl", "service",
            "passwd", "adduser", "useradd", "deluser", "userdel"
        )

        val DEFAULT_DANGEROUS = setOf(
            "rm", "dd", "curl", "wget", "nc", "netcat",
            "apt-get", "apt", "dpkg", "chmod", "chown"
        )
    }
}

sealed class PolicyResult {
    data object ALLOWED : PolicyResult()
    data class DENIED(val reason: String) : PolicyResult()
    data class NEEDS_CONFIRMATION(val reason: String) : PolicyResult()
}
