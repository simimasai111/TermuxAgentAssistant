package com.termux.agent.security

data class ParsedCommand(
    val program: String,
    val args: List<String>,
    val hasCommandSubstitution: Boolean = false,
    val hasBacktick: Boolean = false,
    val hasRedirectToSensitive: Boolean = false,
    val hasPipes: Boolean = false,
    val rawCommand: String = ""
)

class CommandParser {

    private val sensitivePaths = listOf(
        "/etc", "/boot", "/dev", "/proc", "/sys",
        "/data/data/com.termux", "/system"
    )

    fun parse(program: String, args: List<String>): ParsedCommand {
        val fullCommand = listOf(program) + args
        val raw = fullCommand.joinToString(" ")

        val hasSubstitution = raw.contains("\$(") || raw.contains('`')
        val hasBacktick = raw.contains('`')

        val hasRedirectSensitive = args.any { arg ->
            sensitivePaths.any { sensitive ->
                arg.startsWith(">") && arg.contains(sensitive) ||
                        arg.startsWith(">>") && arg.contains(sensitive) ||
                        arg.startsWith("2>") && arg.contains(sensitive)
            }
        }

        val hasPipes = raw.contains("|") || args.any { it == "|" }

        return ParsedCommand(
            program = program,
            args = args,
            hasCommandSubstitution = hasSubstitution,
            hasBacktick = hasBacktick,
            hasRedirectToSensitive = hasRedirectSensitive,
            hasPipes = hasPipes,
            rawCommand = raw
        )
    }

    fun analyze(command: ParsedCommand): List<String> {
        val warnings = mutableListOf<String>()

        if (command.hasCommandSubstitution) {
            warnings.add("Command substitution detected (\$()): possible injection risk")
        }
        if (command.hasBacktick) {
            warnings.add("Backtick detected: possible injection risk")
        }
        if (command.hasRedirectToSensitive) {
            warnings.add("Redirection to sensitive path detected")
        }
        if (command.hasPipes) {
            warnings.add("Pipe operator detected")
        }

        return warnings
    }
}
