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
        private const val API_ID_SECRET = "telegram_api_id"
        private const val API_HASH_SECRET = "telegram_api_hash"
        private const val DATABASE_KEY_SECRET = "telegram_tdlib_database_key"

        @Volatile
        private var nativeLibraryLoaded = false

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

    @Volatile
    private var client: Client? = null

    @Volatile
    private var apiCredentials: TelegramApiCredentials? = loadStoredOrBuildCredentials()

    private val defaultHandler = Client.ResultHandler { result ->
        if (result is TdApi.Error) reportError(result)
    }

    init {
        initializeClient()
    }

    private fun loadStoredOrBuildCredentials(): TelegramApiCredentials? {
        return try {
            val storedId = secretStore.getString(API_ID_SECRET)
            val storedHash = secretStore.getString(API_HASH_SECRET)
            if (!storedId.isNullOrBlank() || !storedHash.isNullOrBlank()) {
                TelegramApiCredentials.parse(storedId.orEmpty(), storedHash.orEmpty())
            } else {
                TelegramApiCredentials.fromCompiledConfig(
                    BuildConfig.TELEGRAM_API_ID,
                    BuildConfig.TELEGRAM_API_HASH,
                )
            }
        } catch (error: Exception) {
            Timber.w(error, "Could not read stored Telegram credentials")
            null
        }
    }

    /**
     * Stores user-owned API credentials using the existing Android Keystore-backed
     * encrypted store, then creates TDLib exactly once. No rebuild or GitHub secret
     * is needed when credentials are entered from the app.
     */
    @Synchronized
    fun configureApiCredentials(credentials: TelegramApiCredentials) {
        check(nativeLibraryLoaded) {
            "TDLib native library is unavailable for this device."
        }
        check(client == null) {
            "Telegram is already initialized. Use Change API credentials to close the session before replacing them."
        }

        secretStore.putString(API_ID_SECRET, credentials.apiId.toString())
        secretStore.putString(API_HASH_SECRET, credentials.apiHash)
        apiCredentials = credentials
        _initializationError.value = null
        initializeClient()
        check(client != null) {
            _initializationError.value ?: "TDLib could not be initialized."
        }
    }

    /**
     * Clears a saved API ID/hash so the user can correct an entry that was valid in
     * format but rejected by Telegram. The TDLib instance is closed before its local
     * database and encryption key are removed.
     */
    suspend fun resetApiCredentials() {
        val activeClient = client
        if (activeClient != null) {
            if (_authorizationState.value is TdApi.AuthorizationStateReady) {
                sendRequest<TdApi.Ok>(TdApi.LogOut())
            } else if (_authorizationState.value !is TdApi.AuthorizationStateClosed) {
                sendRequest<TdApi.Ok>(TdApi.Close())
            }

            val closed = withTimeoutOrNull(10_000L) {
                authorizationState.first { it is TdApi.AuthorizationStateClosed }
            }
            check(closed != null) {
                "TDLib did not finish closing. Try again before changing API credentials."
            }
        }

        client = null
        apiCredentials = null
        secretStore.putString(API_ID_SECRET, "0")
        secretStore.putString(API_HASH_SECRET, "")
        secretStore.remove(DATABASE_KEY_SECRET)
        File(context.filesDir, "tdlib").deleteRecursively()
        File(context.filesDir, "tdlib_files").deleteRecursively()
        _authorizationState.value = null
        _initializationError.value =
            "Telegram API credentials are not configured. Enter your API ID and API hash to continue."
    }

    @Synchronized
    private fun initializeClient() {
        if (client != null) return
        if (!nativeLibraryLoaded) {
            _initializationError.value =
                "TDLib's native library is not available for this device architecture."
            return
        }
        if (apiCredentials == null) {
            _initializationError.value =
                "Telegram API credentials are not configured. Enter your API ID and API hash to continue."
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
            _initializationError.value =
                "TDLib initialization failed: ${error.message ?: "unknown error"}"
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
            val credentials = apiCredentials
                ?: throw IllegalStateException("Telegram API credentials are missing.")

            val databaseDirectory = File(context.filesDir, "tdlib").apply { mkdirs() }.absolutePath
            val filesDirectory = File(context.filesDir, "tdlib_files").apply { mkdirs() }.absolutePath
            val databaseKey = secretStore.getOrCreateBytes(DATABASE_KEY_SECRET, 32)
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
            _initializationError.value =
                "Could not configure Telegram: ${error.message ?: "unknown error"}"
            Timber.e(error, "TDLib configuration failed")
        }
    }

    private fun reportError(error: TdApi.Error) {
        _errors.tryEmit(error)
        Timber.w("TDLib error %d: %s", error.code, error.message)
    }

    fun hasApiCredentials(): Boolean = apiCredentials != null

    fun isNativeLibraryLoaded(): Boolean = nativeLibraryLoaded

    fun isConfigured(): Boolean = apiCredentials != null && nativeLibraryLoaded

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
                    Result.failure(
                        IllegalStateException(
                            initializationError.value ?: "Telegram is not initialized.",
                        ),
                    ),
                )
                return@suspendCancellableCoroutine
            }
            activeClient.send(function) { result ->
                if (!continuation.isActive) return@send
                when (result) {
                    is TdApi.Error -> {
                        reportError(result)
                        continuation.resumeWith(
                            Result.failure(TdlibRequestException(result.code, result.message)),
                        )
                    }
                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        continuation.resumeWith(Result.success(result as T))
                    }
                }
            }
        }

    suspend fun requestQrCodeAuthentication() {
        check(_authorizationState.value is TdApi.AuthorizationStateWaitPhoneNumber) {
            "QR sign-in can only be started while Telegram is waiting for a phone number."
        }
        sendRequest<TdApi.Ok>(TdApi.RequestQrCodeAuthentication())
    }

    suspend fun sendPhoneNumber(phoneNumber: String) {
        sendRequest<TdApi.Ok>(
            TdApi.SetAuthenticationPhoneNumber(
                phoneNumber,
                TdApi.PhoneNumberAuthenticationSettings(),
            ),
        )
    }

    suspend fun checkAuthenticationCode(code: String) {
        sendRequest<TdApi.Ok>(TdApi.CheckAuthenticationCode(code))
    }

    suspend fun checkAuthenticationPassword(password: String) {
        sendRequest<TdApi.Ok>(TdApi.CheckAuthenticationPassword(password))
    }

    suspend fun logout() {
        check(isReady()) { "Telegram is not signed in." }

        // TDLib LogOut closes the current client and emits AuthorizationStateClosed.
        // Waiting for that state before clearing the reference prevents new requests
        // from being sent to a client that has already released its resources.
        sendRequest<TdApi.Ok>(TdApi.LogOut())
        val closed = withTimeoutOrNull(15_000L) {
            authorizationState.first { it is TdApi.AuthorizationStateClosed }
        }
        check(closed != null) {
            "Telegram did not finish signing out. Check your connection and try again."
        }

        // Keep the user's API credentials, but recreate the TDLib client so the
        // next sign-in can start from AuthorizationStateWaitPhoneNumber.
        synchronized(this) {
            client = null
            _authorizationState.value = null
            _initializationError.value = null
            initializeClient()
        }
        check(client != null) {
            _initializationError.value ?: "Telegram could not restart after sign-out."
        }
    }
}

class TdlibRequestException(
    val code: Int,
    message: String?,
) : Exception(message ?: "Telegram request failed (TDLib $code).")
