package com.termux.agent.llm

import android.content.Context
import com.termux.agent.model.ToolCall

data class LLMMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null
)

data class LLMPlanResult(
    val reasoning: String,
    val toolCalls: List<ToolCall>,
    val finished: Boolean,
    val finalAnswer: String? = null
)

interface LLMProvider {
    val config: LLMConfig

    suspend fun generatePlan(
        userInput: String,
        conversation: List<LLMMessage>,
        availableTools: List<ToolDefinition>
    ): LLMPlanResult

    suspend fun decideNext(
        stepResult: String,
        conversation: List<LLMMessage>,
        availableTools: List<ToolDefinition>
    ): LLMPlanResult

    fun isConfigured(): Boolean
}

object ProviderFactory {
    fun create(config: LLMConfig): LLMProvider {
        return when (config.providerType) {
            ProviderType.OPENAI -> OpenAICompatibleProvider(config)
            ProviderType.ANTHROPIC -> AnthropicProvider(config)
            ProviderType.GEMINI -> GeminiProvider(config)
        }
    }

    fun createFromPrefs(context: Context): LLMProvider {
        return create(LLMConfig.load(context))
    }
}
