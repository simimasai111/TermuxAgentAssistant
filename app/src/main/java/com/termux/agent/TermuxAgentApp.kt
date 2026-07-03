package com.termux.agent

import android.app.Application
import com.termux.agent.termux.TermuxBootstrapManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TermuxAgentApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var bootstrapManager: TermuxBootstrapManager
        private set

    private val _bootstrapState = MutableStateFlow<TermuxBootstrapManager.BootstrapState>(
        TermuxBootstrapManager.BootstrapState.NotStarted
    )
    val bootstrapState: StateFlow<TermuxBootstrapManager.BootstrapState> = _bootstrapState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        bootstrapManager = TermuxBootstrapManager(this)
        initializeTermux()
    }

    private fun initializeTermux() {
        appScope.launch {
            val result = bootstrapManager.install()
            _bootstrapState.value = result
        }
    }
}
