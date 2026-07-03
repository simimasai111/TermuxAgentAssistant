package com.termux.agent.llm

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any?>
) {
    fun toJsonMap(): Map<String, Any?> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to description,
            "parameters" to parameters
        )
    )
}

object ToolDefinitions {

    val executeCommand = ToolDefinition(
        name = "execute_command",
        description = "Execute a Linux command in the Termux sandbox. Use this to run programs, install packages, or perform system operations.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "program" to mapOf(
                    "type" to "string",
                    "description" to "The program to execute (e.g., bash, python3, apt-get, ls)"
                ),
                "args" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string"),
                    "description" to "Arguments to pass to the program"
                ),
                "workDir" to mapOf(
                    "type" to "string",
                    "description" to "Working directory (default: sandbox root)"
                ),
                "timeoutMs" to mapOf(
                    "type" to "number",
                    "description" to "Timeout in milliseconds (default: 120000)"
                ),
                "stdin" to mapOf(
                    "type" to "string",
                    "description" to "Standard input to pass to the command"
                )
            ),
            "required" to listOf("program")
        )
    )

    val readFile = ToolDefinition(
        name = "read_file",
        description = "Read the contents of a file within the sandbox directory.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf(
                    "type" to "string",
                    "description" to "Path to the file (absolute or relative to sandbox)"
                )
            ),
            "required" to listOf("path")
        )
    )

    val writeFile = ToolDefinition(
        name = "write_file",
        description = "Write content to a file within the sandbox directory. Creates parent directories if needed.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf(
                    "type" to "string",
                    "description" to "Path to the file (absolute or relative to sandbox)"
                ),
                "content" to mapOf(
                    "type" to "string",
                    "description" to "Content to write to the file"
                )
            ),
            "required" to listOf("path", "content")
        )
    )

    val listDir = ToolDefinition(
        name = "list_dir",
        description = "List the contents of a directory within the sandbox.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "path" to mapOf(
                    "type" to "string",
                    "description" to "Path to the directory (default: sandbox root)"
                )
            ),
            "required" to emptyList<String>()
        )
    )

    val getEnv = ToolDefinition(
        name = "get_env",
        description = "Get system environment information (environment variables, OS info).",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "filter" to mapOf(
                    "type" to "string",
                    "description" to "Optional: filter for specific environment variables"
                )
            ),
            "required" to emptyList<String>()
        )
    )

    val ALL_TOOLS: List<ToolDefinition> = listOf(
        executeCommand, readFile, writeFile, listDir, getEnv
    )
}
