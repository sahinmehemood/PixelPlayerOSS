package com.lostf1sh.pixelplayeross.presentation.telegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.telegram.TelegramChannel
import com.lostf1sh.pixelplayeross.data.telegram.TelegramRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import timber.log.Timber

data class TelegramUiState(
    val authorizationState: TdApi.AuthorizationState? = null,
    val initializationError: String? = null,
    val hasApiCredentials: Boolean = false,
    val apiIdInput: String = "",
    val apiHashInput: String = "",
    val phoneNumber: String = "",
    val code: String = "",
    val password: String = "",
    val channelInput: String = "",
    val channels: List<TelegramChannel> = emptyList(),
    val selectedChannel: TelegramChannel? = null,
    val songs: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val status: String? = null,
)

@HiltViewModel
class TelegramViewModel @Inject constructor(
    private val telegramRepository: TelegramRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TelegramUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val hasCredentials = runCatching { telegramRepository.hasApiCredentials() }
                .getOrDefault(false)
            _uiState.update { it.copy(hasApiCredentials = hasCredentials) }
        }
        viewModelScope.launch {
            telegramRepository.authorizationState.collect { authorization ->
                _uiState.update { it.copy(authorizationState = authorization) }
                if (authorization is TdApi.AuthorizationStateReady) {
                    viewModelScope.launch {
                        // Wait for the login action to release its loading state before
                        // starting the first channel sync.
                        uiState.first { !it.isLoading }
                        refreshJoinedChannels()
                    }
                }
            }
        }
        viewModelScope.launch {
            telegramRepository.initializationError.collect { message ->
                _uiState.update { it.copy(initializationError = message) }
            }
        }
        viewModelScope.launch {
            telegramRepository.errors.collect { error ->
                _uiState.update {
                    it.copy(error = "Telegram: ${error.message ?: "request failed"}")
                }
            }
        }
    }

    fun updateApiId(value: String) = _uiState.update {
        it.copy(apiIdInput = value.filter(Char::isDigit).take(10), error = null)
    }

    fun updateApiHash(value: String) = _uiState.update {
        it.copy(apiHashInput = value.filter(Char::isLetterOrDigit).take(32), error = null)
    }

    fun submitApiCredentials() {
        val state = _uiState.value
        val apiId = state.apiIdInput.toIntOrNull()
        val apiHash = state.apiHashInput.trim()
        if (apiId == null || apiId <= 0) {
            _uiState.update { it.copy(error = "Enter the positive numeric API ID from my.telegram.org.") }
            return
        }
        runAction("Saving Telegram API credentials securely…") {
            telegramRepository.configureApiCredentials(apiId, apiHash)
            _uiState.update {
                it.copy(
                    apiIdInput = "",
                    apiHashInput = "",
                    hasApiCredentials = true,
                    initializationError = null,
                    status = "Telegram API credentials saved on this device. Connecting…",
                )
            }
        }
    }

    fun resetApiCredentials() {
        runAction("Closing Telegram and clearing saved API credentials…") {
            telegramRepository.resetApiCredentials()
            _uiState.update {
                it.copy(
                    authorizationState = null,
                    hasApiCredentials = false,
                    apiIdInput = "",
                    apiHashInput = "",
                    phoneNumber = "",
                    code = "",
                    password = "",
                    channels = emptyList(),
                    selectedChannel = null,
                    songs = emptyList(),
                    initializationError = "Telegram API credentials are not configured. Enter your API ID and API hash to continue.",
                    status = "Telegram session cleared. Enter the replacement API credentials.",
                )
            }
        }
    }

    fun updatePhoneNumber(value: String) = _uiState.update {
        it.copy(phoneNumber = value.filter { char -> char.isDigit() || char == '+' }.take(20), error = null)
    }

    fun updateCode(value: String) = _uiState.update {
        it.copy(code = value.filter(Char::isDigit).take(10), error = null)
    }

    fun updatePassword(value: String) = _uiState.update { it.copy(password = value, error = null) }
    fun updateChannelInput(value: String) = _uiState.update { it.copy(channelInput = value, error = null) }
    fun clearError() = _uiState.update { it.copy(error = null) }

    fun submitPhoneNumber() {
        val phone = _uiState.value.phoneNumber.trim()
        if (!phone.startsWith("+") || phone.count(Char::isDigit) < 7) {
            _uiState.update { it.copy(error = "Enter your phone number in international format, including +country code.") }
            return
        }
        runAction("Sending Telegram verification code…") {
            telegramRepository.sendPhoneNumber(phone)
            _uiState.update { it.copy(status = "Verification code requested. Check Telegram or SMS.") }
        }
    }

    fun submitCode() {
        val code = _uiState.value.code.trim()
        if (code.length < 3) {
            _uiState.update { it.copy(error = "Enter the verification code.") }
            return
        }
        runAction("Verifying code…") { telegramRepository.checkAuthenticationCode(code) }
    }

    fun submitPassword() {
        val password = _uiState.value.password
        if (password.isBlank()) {
            _uiState.update { it.copy(error = "Enter your Telegram two-step verification password.") }
            return
        }
        runAction("Verifying password…") { telegramRepository.checkAuthenticationPassword(password) }
    }

    fun logout() {
        runAction("Signing out of Telegram…") {
            telegramRepository.logout()
            _uiState.update {
                it.copy(
                    phoneNumber = "",
                    code = "",
                    password = "",
                    channelInput = "",
                    channels = emptyList(),
                    selectedChannel = null,
                    songs = emptyList(),
                    error = null,
                    status = "Signed out. Sign in again whenever you're ready.",
                )
            }
        }
    }

    fun refreshJoinedChannels() {
        if (_uiState.value.isLoading) return
        runAction("Loading your Telegram channels…") {
            val channels = telegramRepository.listJoinedChannels()
            _uiState.update {
                it.copy(
                    channels = channels,
                    status = if (channels.isEmpty()) "No chats found. Add a public channel or private invite link." else "Loaded ${channels.size} chats.",
                )
            }
        }
    }

    fun addChannel() {
        val input = _uiState.value.channelInput.trim()
        if (input.isBlank()) {
            _uiState.update { it.copy(error = "Enter a public username or private invite link.") }
            return
        }
        runAction("Resolving Telegram channel…") {
            val channel = telegramRepository.addChannel(input)
            val channels = (_uiState.value.channels + channel).distinctBy { it.chatId }
            _uiState.update {
                it.copy(
                    channels = channels,
                    selectedChannel = channel,
                    channelInput = "",
                    songs = emptyList(),
                    status = "Opening ${channel.title}…",
                )
            }
            loadSongs(channel)
        }
    }

    fun selectChannel(channel: TelegramChannel) {
        if (_uiState.value.selectedChannel?.chatId == channel.chatId && _uiState.value.songs.isNotEmpty()) return
        runAction("Syncing audio from ${channel.title}…") {
            _uiState.update { it.copy(selectedChannel = channel, songs = emptyList()) }
            loadSongs(channel)
        }
    }

    fun refreshSelectedChannel() {
        _uiState.value.selectedChannel?.let { channel ->
            runAction("Refreshing ${channel.title}…") {
                loadSongs(channel)
            }
        }
    }

    private suspend fun loadSongs(channel: TelegramChannel) {
        val songs = telegramRepository.getAudioMessages(channel)
        _uiState.update {
            it.copy(
                selectedChannel = channel,
                songs = songs,
                status = if (songs.isEmpty()) {
                    "No audio files found in ${channel.title}."
                } else {
                    "Found ${songs.size} audio files in ${channel.title}."
                },
            )
        }
    }

    private fun runAction(message: String, action: suspend () -> Unit) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, status = message) }
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Telegram action failed")
                _uiState.update {
                    it.copy(error = error.message ?: "Telegram request failed.", status = null)
                }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}
