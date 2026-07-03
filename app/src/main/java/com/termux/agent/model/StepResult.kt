package com.termux.agent.model

data class StepResult(
    val stepId: String,
    val toolName: String,
    val args: Map<String, Any?>,
    val result: ToolResult,
    val status: StepStatus = StepStatus.COMPLETED,
    val riskLevel: RiskLevel = RiskLevel.NONE
)

enum class StepStatus {
    PENDING,
    PRECHECK,
    AWAITING_CONFIRMATION,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    CANCELLED
}

enum class RiskLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH
}
