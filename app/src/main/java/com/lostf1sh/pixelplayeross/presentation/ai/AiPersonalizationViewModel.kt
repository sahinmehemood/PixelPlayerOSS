package com.lostf1sh.pixelplayeross.presentation.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.ai.AiPersonalizationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AiPersonalizationUiState(
    val apiKeyInput: String = "",
    val apiKeyConfigured: Boolean = false,
    val endpoint: String = AiPersonalizationRepository.DEFAULT_ENDPOINT,
    val model: String = AiPersonalizationRepository.DEFAULT_MODEL,
    val enabled: Boolean = false,
    val sharingConfirmed: Boolean = false,
    val isLoading: Boolean = false,
    val status: String? = null,
    val error: String? = null,
)

@HiltViewModel
class AiPersonalizationViewModel @Inject constructor(
    private val repository: AiPersonalizationRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AiPersonalizationUiState())
    val uiState = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            runCatching { repository.getSettings() }
                .onSuccess { settings ->
                    _uiState.update {
                        it.copy(
                            apiKeyConfigured = settings.apiKeyConfigured,
                            endpoint = settings.endpoint,
                            model = settings.model,
                            enabled = settings.enabled,
                            sharingConfirmed = settings.metadataSharingConfirmed,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message ?: "Could not load secure settings.") }
                }
        }
    }

    fun updateApiKey(value: String) = _uiState.update { it.copy(apiKeyInput = value, error = null) }
    fun updateEndpoint(value: String) = _uiState.update { it.copy(endpoint = value, error = null) }
    fun updateModel(value: String) = _uiState.update { it.copy(model = value, error = null) }
    fun setEnabled(value: Boolean) = _uiState.update { it.copy(enabled = value, error = null) }
    fun setSharingConfirmed(value: Boolean) = _uiState.update { it.copy(sharingConfirmed = value, error = null) }

    fun save() {
        runRequest("Settings saved securely.") {
            val state = _uiState.value
            repository.saveSettings(
                apiKey = state.apiKeyInput,
                endpoint = state.endpoint,
                model = state.model,
                enabled = state.enabled,
                metadataSharingConfirmed = state.sharingConfirmed,
            )
            val saved = repository.getSettings()
            _uiState.update {
                it.copy(
                    apiKeyInput = "",
                    apiKeyConfigured = saved.apiKeyConfigured,
                    endpoint = saved.endpoint,
                    model = saved.model,
                    enabled = saved.enabled,
                    sharingConfirmed = saved.metadataSharingConfirmed,
                )
            }
        }
    }

    fun testConnection() {
        runRequest("Connection test completed.") {
            val state = _uiState.value
            repository.saveSettings(
                apiKey = state.apiKeyInput,
                endpoint = state.endpoint,
                model = state.model,
                enabled = state.enabled,
                metadataSharingConfirmed = state.sharingConfirmed,
            )
            val reply = repository.testConnection()
            val saved = repository.getSettings()
            _uiState.update {
                it.copy(
                    apiKeyInput = "",
                    apiKeyConfigured = saved.apiKeyConfigured,
                    endpoint = saved.endpoint,
                    model = saved.model,
                    enabled = saved.enabled,
                    sharingConfirmed = saved.metadataSharingConfirmed,
                    status = "Connection successful: $reply",
                )
            }
        }
    }

    fun clearApiKey() {
        runRequest("API key removed. AI personalization is now off.") {
            repository.clearApiKey()
            _uiState.update {
                it.copy(apiKeyInput = "", apiKeyConfigured = false, enabled = false)
            }
        }
    }

    private fun runRequest(successMessage: String, action: suspend () -> Unit) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, status = null) }
            try {
                action()
                _uiState.update { it.copy(status = it.status ?: successMessage) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(error = error.message ?: "AI settings request failed.") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}
