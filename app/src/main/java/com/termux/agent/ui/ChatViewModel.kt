package com.termux.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.termux.agent.TermuxAgentApp
import com.termux.agent.llm.LLMConfig
import com.termux.agent.llm.ProviderFactory
import com.termux.agent.orchestrator.AgentOrchestrator
import com.termux.agent.orchestrator.AgentState
import com.termux.agent.orchestrator.ChatMessage
import com.termux.agent.orchestrator.MessageRole
import com.termux.agent.termux.TermuxBootstrapManager
import com.termux.agent.transport.TermuxBridgeClient
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as TermuxAgentApp
    private val bootstrapManager = app.bootstrapManager
    private val bridge = TermuxBridgeClient(bootstrapManager)
    private val llmConfig = LLMConfig.load(application)
    private val llmProvider = ProviderFactory.create(llmConfig)
    private val orchestrator = AgentOrchestrator(
        bridge = bridge,
        llmProvider = llmProvider
    )

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _showConfirmation = MutableLiveData(false)
    val showConfirmation: LiveData<Boolean> = _showConfirmation

    private val _llmStatus = MutableLiveData<String>("")
    val llmStatus: LiveData<String> = _llmStatus

    private val _bootstrapStatus = MutableLiveData<String>("")
    val bootstrapStatus: LiveData<String> = _bootstrapStatus

    private val _toastMessage = MutableLiveData<String?>(null)
    val toastMessage: LiveData<String?> = _toastMessage

    init {
        updateLlmStatus()
        observeBootstrap()

        viewModelScope.launch {
            orchestrator.messages.collect { msgs ->
                _messages.postValue(msgs)
            }
        }

        viewModelScope.launch {
            orchestrator.state.collect { state ->
                _showConfirmation.postValue(state == AgentState.AWAITING_CONFIRMATION)
                when (state) {
                    AgentState.ERROR -> _toastMessage.postValue("执行出错")
                    AgentState.FINALIZE -> _toastMessage.postValue("任务完成")
                    else -> {}
                }
            }
        }
    }

    private fun observeBootstrap() {
        viewModelScope.launch {
            app.bootstrapState.collect { state ->
                val msg = when (state) {
                    is TermuxBootstrapManager.BootstrapState.NotStarted -> ""
                    is TermuxBootstrapManager.BootstrapState.Downloading ->
                        "正在下载 Termux 环境 (${state.progress}%)..."
                    is TermuxBootstrapManager.BootstrapState.Extracting ->
                        state.message
                    is TermuxBootstrapManager.BootstrapState.Complete -> {
                        _messages.value = (_messages.value ?: emptyList()) + ChatMessage(
                            role = MessageRole.SYSTEM,
                            content = "Termux 环境已就绪"
                        )
                        ""
                    }
                    is TermuxBootstrapManager.BootstrapState.Failed ->
                        "安装失败: ${state.error}"
                }
                _bootstrapStatus.postValue(msg)
            }
        }
    }

    private fun updateLlmStatus() {
        val bootstrapReady = if (bootstrapManager.isInstalled) "✓ Termux" else "⏳ 初始化中"
        _llmStatus.value = if (llmConfig.apiKey.isNotBlank()) {
            "$bootstrapReady | ${llmConfig.providerType.displayName}"
        } else {
            "$bootstrapReady | 未配置 API Key"
        }
    }

    fun sendMessage(text: String) {
        if (!bootstrapManager.isInstalled) {
            _toastMessage.postValue("Termux 环境正在初始化，请稍候...")
            return
        }
        orchestrator.startTask(text)
    }

    fun confirmStep() {
        orchestrator.confirmStep()
    }

    fun rejectStep() {
        orchestrator.rejectStep()
    }
}
