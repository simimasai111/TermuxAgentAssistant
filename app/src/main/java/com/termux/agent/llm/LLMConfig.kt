package com.termux.agent.llm

import android.content.Context
import android.content.SharedPreferences

enum class ProviderType(val displayName: String) {
    OPENAI("OpenAI"),
    ANTHROPIC("Anthropic"),
    GEMINI("Gemini")
}

data class LLMConfig(
    val providerType: ProviderType = ProviderType.OPENAI,
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val modelId: String = DEFAULT_MODEL_ID,
    val maxTokens: Int = 2048,
    val temperature: Double = 0.1
) {
    companion object {
        val DEFAULTS = mapOf(
            ProviderType.OPENAI to Pair("https://api.openai.com/v1", "gpt-4o-mini"),
            ProviderType.ANTHROPIC to Pair("https://api.anthropic.com", "claude-sonnet-4-20250514"),
            ProviderType.GEMINI to Pair("https://generativelanguage.googleapis.com", "gemini-2.0-flash")
        )

        val DEFAULT_BASE_URL get() = DEFAULTS[ProviderType.OPENAI]!!.first
        val DEFAULT_MODEL_ID get() = DEFAULTS[ProviderType.OPENAI]!!.second

        private const val PREFS_NAME = "llm_config"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_TEMPERATURE = "temperature"

        fun load(context: Context): LLMConfig {
            val prefs = prefs(context)
            val provider = try {
                ProviderType.valueOf(prefs.getString(KEY_PROVIDER, ProviderType.OPENAI.name) ?: ProviderType.OPENAI.name)
            } catch (_: Exception) { ProviderType.OPENAI }

            val (defUrl, defModel) = DEFAULTS[provider] ?: DEFAULTS[ProviderType.OPENAI]!!

            return LLMConfig(
                providerType = provider,
                baseUrl = prefs.getString(KEY_BASE_URL, defUrl) ?: defUrl,
                apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
                modelId = prefs.getString(KEY_MODEL_ID, defModel) ?: defModel,
                maxTokens = prefs.getInt(KEY_MAX_TOKENS, 2048),
                temperature = prefs.getFloat(KEY_TEMPERATURE, 0.1f).toDouble()
            )
        }

        fun save(context: Context, config: LLMConfig) {
            prefs(context).edit().apply {
                putString(KEY_PROVIDER, config.providerType.name)
                putString(KEY_BASE_URL, config.baseUrl)
                putString(KEY_API_KEY, config.apiKey)
                putString(KEY_MODEL_ID, config.modelId)
                putInt(KEY_MAX_TOKENS, config.maxTokens)
                putFloat(KEY_TEMPERATURE, config.temperature.toFloat())
                apply()
            }
        }

        private fun prefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
