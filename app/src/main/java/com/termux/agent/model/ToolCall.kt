package com.termux.agent.model

data class ToolCall(
    val stepId: String,
    val toolName: String,
    val args: Map<String, Any?>,
    val timeoutMs: Long = 120000L,
    val workDir: String = "sandbox:///",
    val env: Map<String, String> = emptyMap(),
    val limits: OutputLimits = OutputLimits()
)

data class OutputLimits(
    val maxStdoutBytes: Long = 200_000L,
    val maxStderrBytes: Long = 100_000L
)
