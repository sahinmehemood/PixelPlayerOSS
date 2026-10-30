package com.lostf1sh.pixelplayeross.data.telegram

import android.content.Context
import com.lostf1sh.pixelplayeross.BuildConfig
import com.lostf1sh.pixelplayeross.data.security.EncryptedSecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import timber.log.Timber

@Singleton
class TelegramClientManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secretStore: EncryptedSecretStore,
) {
    companion object {
        private const val STORED_API_ID = "telegram_api_id"
        private const val STORED_API_HASH = "telegram_api_hash"

        @Volatile private var nativeLibraryLoaded = false
        init {
            nativeLibraryLoaded = try {
                System.loadLibrary("tdjni")
                true
            } catch (error: UnsatisfiedLinkError) {
                Timber.e(error, "TDLib native library could not be loaded")
                false
            }
        }
    }

    private val _authorizationState = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authorizationState = _authorizationState.asStateFlow()

    private val _errors = MutableSharedFlow<TdApi.Error>(extraBufferCapacity = 16)
    val errors = _errors.asSharedFlow()

    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 64)
    val updates = _updates.asSharedFlow()

    private val _initializationError = MutableStateFlow<String?>(null)
    val initializationError = _initializationError.asStateFlow()

    @Volatile private var client: Client? = null

    private val defaultHandler = Client.ResultHandler { result ->
        if (result is TdApi.Error) reportError(result)
    }

    init {
        initializeClient()
    }

    private data class ApiCredentials(
        val apiId: Int,
        val apiHash: String,
    )

    private fun readApiCredentials(): ApiCredentials? {
        val storedId = secretStore.getString(STORED_API_ID)?.toIntOrNull()
        val storedHash = secretStore.getString(STORED_API_HASH)?.takeIf { it.isNotBlank() }
        val apiId = storedId ?: BuildConfig.TELEGRAM_API_ID
        val apiHash = storedHash ?: BuildConfig.TELEGRAM_API_HASH
        if (!TelegramApiCredentialsValidator.isValid(apiId, apiHash)) return null
        return ApiCredentials(apiId, apiHash.trim())
    }

    fun hasApiCredentials(): Boolean = runCatching { readApiCredentials() != null }.getOrDefault(false)

    @Synchronized
    fun configureApiCredentials(apiId: Int, apiHash: String) {
        TelegramApiCredentialsValidator.validate(apiId, apiHash)
        check(client == null) {
            "Telegram is already initialized. Sign out and restart the app before changing API credentials."
        }
        secretStore.putString(STORED_API_ID, apiId.toString())
        secretStore.putString(STORED_API_HASH, apiHash.trim())
        _initializationError.value = null
        initializeClient()
        _initializationError.value?.let { error ->
            if (client == null) throw IllegalStateException(error)
        }
    }

    @Synchronized
    private fun initializeClient() {
        if (client != null) return
        if (!nativeLibraryLoaded) {
            _initializationError.value = "TDLib native library is unavailable for this device."
            return
        }
        val credentials = try {
            readApiCredentials()
        } catch (error: Exception) {
            _initializationError.value = "Secure Telegram settings could not be opened: ${error.message ?: "unknown error"}"
            Timber.e(error, "Could not read encrypted Telegram API credentials")
            return
        }
        if (credentials == null) {
            _initializationError.value =
                "Telegram API credentials are not configured. Enter your API ID and API hash from my.telegram.org below."
            return
        }

        try {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
            val created = Client.create(
                Client.ResultHandler { result ->
                    when (result) {
                        is TdApi.Update -> {
                            if (result is TdApi.UpdateAuthorizationState) {
                                onAuthorizationStateUpdated(result.authorizationState)
                            }
                            _updates.tryEmit(result)
                        }
                        is TdApi.Error -> reportError(result)
                    }
                },
                null,
                null,
            )
            client = created
        } catch (error: Exception) {
            _initializationError.value = "TDLib initialization failed: ${error.message ?: "unknown error"}"
            Timber.e(error, "TDLib initialization failed")
        }
    }

    private fun onAuthorizationStateUpdated(state: TdApi.AuthorizationState) {
        _authorizationState.value = state
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> configureTdlib()
            is TdApi.AuthorizationStateReady -> _initializationError.value = null
            else -> Unit
        }
    }

    private fun configureTdlib() {
        try {
            val databaseDirectory = File(context.filesDir, "tdlib").apply { mkdirs() }.absolutePath
            val filesDirectory = File(context.filesDir, "tdlib_files").apply { mkdirs() }.absolutePath
            val databaseKey = secretStore.getOrCreateBytes("telegram_tdlib_database_key", 32)
            val params = TdApi.SetTdlibParameters(
                false,
                databaseDirectory,
                filesDirectory,
                databaseKey,
                true,
                true,
                true,
                false,
                credentials.apiId,
                credentials.apiHash,
                Locale.getDefault().language.ifBlank { "en" },
                android.os.Build.MODEL ?: "Android",
                android.os.Build.VERSION.RELEASE ?: "unknown",
                BuildConfig.VERSION_NAME,
            )
            client?.send(params, defaultHandler)
        } catch (error: Exception) {
            _initializationError.value = "Could not configure Telegram: ${error.message ?: "unknown error"}"
            Timber.e(error, "TDLib configuration failed")
        }
    }

    private fun reportError(error: TdApi.Error) {
        _errors.tryEmit(error)
        Timber.w("TDLib error %d: %s", error.code, error.message)
    }

    fun isConfigured(): Boolean = hasApiCredentials() && nativeLibraryLoaded

    fun isReady(): Boolean = _authorizationState.value is TdApi.AuthorizationStateReady

    suspend fun awaitReady(timeoutMs: Long = 30_000L): Boolean {
        if (isReady()) return true
        if (!isConfigured()) return false
        return withTimeoutOrNull(timeoutMs) {
            authorizationState.first { state ->
                state is TdApi.AuthorizationStateReady || state is TdApi.AuthorizationStateClosed
            } is TdApi.AuthorizationStateReady
        } ?: false
    }

    suspend fun <T : TdApi.Object> sendRequest(function: TdApi.Function<*>): T =
        suspendCancellableCoroutine { continuation ->
            val activeClient = client
            if (activeClient == null) {
                continuation.resumeWith(
                    Result.failure(IllegalStateException(initializationError.value ?: "Telegram is not initialized."))
                )
                return@suspendCancellableCoroutine
            }
            activeClient.send(function) { result ->
                if (!continuation.isActive) return@send
                when (result) {
                    is TdApi.Error -> {
                        reportError(result)
                        continuation.resumeWith(
                            Result.failure(TdlibRequestException(result.code, result.message))
                        )
                    }
                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        continuation.resumeWith(Result.success(result as T))
                    }
                }
            }
        }

    suspend fun sendPhoneNumber(phoneNumber: String) {
        sendRequest<TdApi.Ok>(
            TdApi.SetAuthenticationPhoneNumber(phoneNumber, TdApi.PhoneNumberAuthenticationSettings())
        )
    }

    suspend fun checkAuthenticationCode(code: String) {
        sendRequest<TdApi.Ok>(TdApi.CheckAuthenticationCode(code))
    }

    suspend fun checkAuthenticationPassword(password: String) {
        sendRequest<TdApi.Ok>(TdApi.CheckAuthenticationPassword(password))
    }

    suspend fun logout() {
        sendRequest<TdApi.Ok>(TdApi.LogOut())
    }
}

class TdlibRequestException(
    val code: Int,
    message: String?,
) : Exception(message ?: "Telegram request failed (TDLib $code).")
