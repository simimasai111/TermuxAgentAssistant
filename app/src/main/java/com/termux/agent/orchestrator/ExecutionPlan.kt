package com.termux.agent.orchestrator

import com.termux.agent.model.StepResult

data class ExecutionPlan(
    val taskId: String,
    val goal: String,
    val steps: MutableList<StepResult> = mutableListOf(),
    val status: PlanStatus = PlanStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis()
)

enum class PlanStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}
