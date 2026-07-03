package com.termux.agent.tools

import com.termux.agent.model.ToolCall
import com.termux.agent.model.ToolResult

interface Tool {
    val name: String
    suspend fun execute(call: ToolCall): ToolResult
}
