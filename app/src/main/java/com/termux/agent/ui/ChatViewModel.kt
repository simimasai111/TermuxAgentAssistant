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

    private val _bootstrapErrorDetail = MutableLiveData<String?>(null)
    val bootstrapErrorDetail: LiveData<String?> = _bootstrapErrorDetail

    private val _bootstrapInstalling = MutableLiveData(false)
    val bootstrapInstalling: LiveData<Boolean> = _bootstrapInstalling

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
                when (state) {
                    is TermuxBootstrapManager.BootstrapState.NotStarted -> {
                        _bootstrapStatus.postValue("")
                        _bootstrapInstalling.postValue(false)
                        _bootstrapErrorDetail.postValue(null)
                    }
                    is TermuxBootstrapManager.BootstrapState.Downloading -> {
                        _bootstrapStatus.postValue("正在下载 Termux 环境 (${state.progress}%)...")
                        _bootstrapInstalling.postValue(true)
                    }
                    is TermuxBootstrapManager.BootstrapState.Extracting -> {
                        _bootstrapStatus.postValue(state.message)
                        _bootstrapInstalling.postValue(true)
                    }
                    is TermuxBootstrapManager.BootstrapState.Complete -> {
                        _bootstrapStatus.postValue("")
                        _bootstrapInstalling.postValue(false)
                        _messages.value = (_messages.value ?: emptyList()) + ChatMessage(
                            role = MessageRole.SYSTEM,
                            content = "Termux 环境已就绪"
                        )
                        updateLlmStatus()
                    }
                    is TermuxBootstrapManager.BootstrapState.Failed -> {
                        _bootstrapStatus.postValue("安装失败: ${state.error}")
                        _bootstrapInstalling.postValue(false)
                        _bootstrapErrorDetail.postValue(state.detailLog.ifEmpty { state.error })
                        updateLlmStatus()
                    }
                }
            }
        }
    }

    fun retryBootstrap() {
        viewModelScope.launch {
            _bootstrapErrorDetail.postValue(null)
            _bootstrapStatus.postValue("正在重试...")
            val result = bootstrapManager.install()
            // state flow will pick it up
        }
    }

    fun clearError() {
        _bootstrapErrorDetail.postValue(null)
        _bootstrapStatus.postValue("可以在设置中修改镜像地址后重试")
    }

    private fun updateLlmStatus() {
        val bootstrapReady = when {
            bootstrapManager.isInstalled -> "✓ Termux"
            app.bootstrapState.value is TermuxBootstrapManager.BootstrapState.Failed -> "✗ Termux 安装失败"
            else -> "⏳ 初始化中"
        }
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
