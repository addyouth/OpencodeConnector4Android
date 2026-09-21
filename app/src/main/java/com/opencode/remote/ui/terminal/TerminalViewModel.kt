package com.opencode.remote.ui.terminal

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.remote.data.repository.OConnectorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 远程终端 VM：单活跃 pty，离开页面不断服务端（cursor 续连回来）。 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val repository: OConnectorRepository,
) : ViewModel() {

    val output: StateFlow<String> = repository.terminalText
    val status: StateFlow<String> = repository.terminalStatus

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun ensureOpen(directory: String?) {
        if (repository.hasTerminal()) {
            repository.terminalReconnect()
            return
        }
        viewModelScope.launch {
            try {
                repository.terminalOpen(directory)
            } catch (e: Exception) {
                Log.e(TAG, "terminal open failed", e)
                _error.value = e.localizedMessage ?: e.javaClass.simpleName
            }
        }
    }

    fun send(text: String) {
        try {
            repository.terminalSend(text)
        } catch (e: Exception) {
            Log.w(TAG, "terminal send failed", e)
            _error.value = e.localizedMessage ?: e.javaClass.simpleName
        }
    }

    fun reconnect() {
        try {
            repository.terminalReconnect()
        } catch (e: Exception) {
            Log.w(TAG, "terminal reconnect failed", e)
        }
    }

    fun closeAndDelete() {
        try {
            repository.terminalClose(delete = true)
        } catch (e: Exception) {
            Log.w(TAG, "terminal close failed", e)
        }
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        // 只断 socket，服务端 pty 保留（cursor 续连回来）
        try {
            repository.terminalClose(delete = false)
        } catch (e: Exception) {
            Log.w(TAG, "terminal detach failed", e)
        }
        super.onCleared()
    }

    companion object {
        private const val TAG = "TerminalViewModel"
    }
}
