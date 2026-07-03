package com.termux.agent.settings

import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.termux.agent.R
import com.termux.agent.llm.LLMConfig
import com.termux.agent.llm.ProviderType
import com.termux.agent.termux.TermuxConfig

class SettingsActivity : AppCompatActivity() {

    private lateinit var providerSpinner: Spinner
    private lateinit var baseUrlEdit: EditText
    private lateinit var apiKeyEdit: EditText
    private lateinit var modelIdEdit: EditText
    private lateinit var maxTokensEdit: EditText
    private lateinit var temperatureEdit: EditText
    private lateinit var bootstrapMirrorEdit: EditText
    private lateinit var saveButton: Button
    private lateinit var testButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        providerSpinner = findViewById(R.id.spinner_provider)
        baseUrlEdit = findViewById(R.id.edit_base_url)
        apiKeyEdit = findViewById(R.id.edit_api_key)
        modelIdEdit = findViewById(R.id.edit_model_id)
        maxTokensEdit = findViewById(R.id.edit_max_tokens)
        temperatureEdit = findViewById(R.id.edit_temperature)
        bootstrapMirrorEdit = findViewById(R.id.edit_bootstrap_mirror)
        saveButton = findViewById(R.id.btn_save)
        testButton = findViewById(R.id.btn_test)

        providerSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            ProviderType.entries.map { it.displayName }
        )

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                val provider = ProviderType.entries[pos]
                val (defUrl, defModel) = LLMConfig.DEFAULTS[provider]!!
                if (baseUrlEdit.text.isBlank() || baseUrlEdit.text.toString() == LLMConfig.DEFAULTS[ProviderType.OPENAI]!!.first) {
                    baseUrlEdit.setText(defUrl)
                }
                if (modelIdEdit.text.isBlank()) {
                    modelIdEdit.setText(defModel)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadConfig()

        saveButton.setOnClickListener { saveConfig() }
        testButton.setOnClickListener { testConnection() }
    }

    private fun loadConfig() {
        val llmConfig = LLMConfig.load(this)
        val termuxConfig = TermuxConfig.load(this)

        val providerIdx = ProviderType.entries.indexOf(llmConfig.providerType).coerceAtLeast(0)
        providerSpinner.setSelection(providerIdx)
        baseUrlEdit.setText(llmConfig.baseUrl)
        apiKeyEdit.setText(llmConfig.apiKey)
        modelIdEdit.setText(llmConfig.modelId)
        maxTokensEdit.setText(llmConfig.maxTokens.toString())
        temperatureEdit.setText(llmConfig.temperature.toString())
        bootstrapMirrorEdit.setText(termuxConfig.bootstrapMirrorUrl)
    }

    private fun saveConfig() {
        val provider = ProviderType.entries[providerSpinner.selectedItemPosition]
        val baseUrl = baseUrlEdit.text.toString().trim()
        val apiKey = apiKeyEdit.text.toString().trim()
        val modelId = modelIdEdit.text.toString().trim()
        val maxTokens = maxTokensEdit.text.toString().toIntOrNull() ?: 2048
        val temperature = temperatureEdit.text.toString().toDoubleOrNull() ?: 0.1
        val bootstrapMirror = bootstrapMirrorEdit.text.toString().trim()

        if (apiKey.isBlank()) {
            Toast.makeText(this, "请输入 API Key", Toast.LENGTH_SHORT).show()
            return
        }

        val llmConfig = LLMConfig(provider, baseUrl, apiKey, modelId, maxTokens, temperature)
        LLMConfig.save(this, llmConfig)

        val termuxConfig = TermuxConfig(
            bootstrapMirrorUrl = bootstrapMirror.ifEmpty { TermuxConfig.defaultMirrorUrl }
        )
        TermuxConfig.save(this, termuxConfig)

        Toast.makeText(this, "配置已保存 (${provider.displayName})", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testConnection() {
        val provider = ProviderType.entries[providerSpinner.selectedItemPosition]
        val apiKey = apiKeyEdit.text.toString().trim()

        if (apiKey.isBlank()) {
            Toast.makeText(this, "请先输入 API Key", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "正在测试连接...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val testUrl = when (provider) {
                    ProviderType.OPENAI -> "${baseUrlEdit.text.toString().trimEnd('/')}/models"
                    ProviderType.ANTHROPIC -> "${baseUrlEdit.text.toString().trimEnd('/')}/v1/messages"
                    ProviderType.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                }
                val url = java.net.URL(testUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                if (provider == ProviderType.ANTHROPIC) {
                    conn.setRequestProperty("x-api-key", apiKey)
                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                }
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val code = conn.responseCode
                conn.disconnect()

                runOnUiThread {
                    if (code in 200..299) {
                        Toast.makeText(this, "连接成功 (HTTP $code)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "连接失败 (HTTP $code)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
