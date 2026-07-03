package com.termux.agent.orchestrator

enum class AgentState {
    IDLE,
    PLAN,
    PRECHECK,
    AWAITING_CONFIRMATION,
    EXECUTE,
    OBSERVE,
    DECIDE,
    FINALIZE,
    ERROR;

    fun canTransitionTo(next: AgentState): Boolean {
        return when (this) {
            IDLE -> next == PLAN
            PLAN -> next == PRECHECK || next == FINALIZE || next == ERROR
            PRECHECK -> next == AWAITING_CONFIRMATION || next == EXECUTE || next == ERROR
            AWAITING_CONFIRMATION -> next == EXECUTE || next == PLAN || next == FINALIZE
            EXECUTE -> next == OBSERVE || next == ERROR
            OBSERVE -> next == DECIDE || next == ERROR
            DECIDE -> next == PLAN || next == PRECHECK || next == EXECUTE || next == FINALIZE || next == IDLE || next == ERROR
            FINALIZE -> next == IDLE || next == ERROR
            ERROR -> next == IDLE || next == PLAN
        }
    }
}
