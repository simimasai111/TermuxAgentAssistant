package com.termux.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.termux.agent.llm.LLMConfig
import com.termux.agent.llm.ProviderFactory
import com.termux.agent.orchestrator.AgentOrchestrator
import com.termux.agent.orchestrator.AgentState
import com.termux.agent.orchestrator.ChatMessage
import com.termux.agent.orchestrator.MessageRole
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val llmConfig = LLMConfig.load(application)
    private val llmProvider = ProviderFactory.create(llmConfig)
    private val orchestrator = AgentOrchestrator(llmProvider = llmProvider)

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _showConfirmation = MutableLiveData(false)
    val showConfirmation: LiveData<Boolean> = _showConfirmation

    private val _llmStatus = MutableLiveData<String>("")
    val llmStatus: LiveData<String> = _llmStatus

    private val _toastMessage = MutableLiveData<String?>(null)
    val toastMessage: LiveData<String?> = _toastMessage

    init {
        updateLlmStatus()
        _messages.value = listOf(
            ChatMessage(
                role = MessageRole.AGENT,
                content = "你好！我是 Termux AI Agent 助手。请问有什么需要？"
            )
        )

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

    private fun updateLlmStatus() {
        _llmStatus.value = if (llmConfig.apiKey.isNotBlank()) {
            "${llmConfig.providerType.displayName} | ${llmConfig.modelId}"
        } else {
            "未配置 API Key"
        }
    }

    fun sendMessage(text: String) {
        orchestrator.startTask(text)
    }

    fun confirmStep() {
        orchestrator.confirmStep()
    }

    fun rejectStep() {
        orchestrator.rejectStep()
    }
}
