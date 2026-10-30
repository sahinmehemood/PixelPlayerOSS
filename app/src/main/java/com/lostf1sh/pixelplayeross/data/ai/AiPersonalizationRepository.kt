package com.lostf1sh.pixelplayeross.data.ai

import com.lostf1sh.pixelplayeross.data.DailyMixManager
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.data.security.EncryptedSecretStore
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

data class AiPersonalizationSettings(
    val apiKeyConfigured: Boolean,
    val endpoint: String,
    val model: String,
    val enabled: Boolean,
    val metadataSharingConfirmed: Boolean,
)

@Singleton
class AiPersonalizationRepository @Inject constructor(
    private val secretStore: EncryptedSecretStore,
    private val musicRepository: MusicRepository,
    private val dailyMixManager: DailyMixManager,
) {
    companion object {
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        private const val API_KEY = "ai_personalization_api_key"
        private const val ENDPOINT = "ai_personalization_endpoint"
        private const val MODEL = "ai_personalization_model"
        private const val ENABLED = "ai_personalization_enabled"
        private const val SHARE_CONFIRMED = "ai_metadata_sharing_confirmed"
        private const val MAX_CANDIDATES = 120
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .callTimeout(55, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    fun getSettings(): AiPersonalizationSettings {
        val apiKeyConfigured = !secretStore.getString(API_KEY).isNullOrBlank()
        val sharingConfirmed = secretStore.getString(SHARE_CONFIRMED) == "true"
        return AiPersonalizationSettings(
            apiKeyConfigured = apiKeyConfigured,
            endpoint = secretStore.getString(ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT,
            model = secretStore.getString(MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
            enabled = apiKeyConfigured && sharingConfirmed && secretStore.getString(ENABLED) == "true",
            metadataSharingConfirmed = sharingConfirmed,
        )
    }

    fun saveSettings(
        apiKey: String,
        endpoint: String,
        model: String,
        enabled: Boolean,
        metadataSharingConfirmed: Boolean,
    ) {
        val normalizedEndpoint = normalizeEndpoint(endpoint)
        require(model.isNotBlank()) { "Enter a model name." }
        if (apiKey.isNotBlank()) secretStore.putString(API_KEY, apiKey.trim())
        val hasKey = !secretStore.getString(API_KEY).isNullOrBlank()
        secretStore.putString(ENDPOINT, normalizedEndpoint)
        secretStore.putString(MODEL, model.trim())
        secretStore.putString(SHARE_CONFIRMED, metadataSharingConfirmed.toString())
        secretStore.putString(ENABLED, (enabled && hasKey && metadataSharingConfirmed).toString())
    }

    fun clearApiKey() {
        secretStore.remove(API_KEY)
        secretStore.putString(ENABLED, "false")
    }

    /**
     * Returns a ranked subset of the user's own library, or null when AI is disabled or unusable.
     * A null result is intentionally interpreted as a request to use PixelPlayer's offline mixer.
     */
    suspend fun generateMix(prompt: String, maxSongs: Int): List<Song>? = withContext(Dispatchers.IO) {
        val settings = getSettings()
        if (!settings.enabled || !settings.apiKeyConfigured || !settings.metadataSharingConfirmed) return@withContext null

        try {
            val apiKey = secretStore.getString(API_KEY)?.takeIf { it.isNotBlank() } ?: return@withContext null
            val songs = musicRepository.getAllSongsOnce()
            if (songs.isEmpty()) return@withContext emptyList()

            val engagement = dailyMixManager.getAllEngagementStats()
            val ranked = songs.sortedWith(
                compareByDescending<Song> { it.isFavorite }
                    .thenByDescending { engagement[it.id]?.playCount ?: 0 }
                    .thenByDescending { engagement[it.id]?.lastPlayedTimestamp ?: 0L }
            )
            // Mix strong taste signals with a discovery sample so the model can recommend
            // less-played items too. Only descriptive metadata is sent; paths and audio are not.
            val favouritesAndFrequent = ranked.take(90)
            val discovery = songs.filterNot { candidate -> favouritesAndFrequent.any { it.id == candidate.id } }
                .shuffled()
                .take(30)
            val candidateSongs = (favouritesAndFrequent + discovery).distinctBy { it.id }.take(MAX_CANDIDATES)
            val candidatesById = candidateSongs.associateBy { it.id }
            val catalog = JSONArray()
            candidateSongs.forEach { song ->
                val stats = engagement[song.id]
                catalog.put(
                    JSONObject()
                        .put("id", song.id)
                        .put("title", song.title.take(180))
                        .put("artist", song.displayArtist.take(180))
                        .put("album", song.album.take(180))
                        .put("genre", song.genre.orEmpty().take(120))
                        .put("durationMs", song.duration.coerceAtLeast(0L))
                        .put("favorite", song.isFavorite)
                        .put("playCount", stats?.playCount ?: 0)
                        .put("lastPlayedTimestamp", stats?.lastPlayedTimestamp ?: 0L)
                )
            }

            val userPrompt = """
                Create a music mix for this request: ${prompt.take(500)}

                Return only a JSON object with this exact shape:
                {"songIds":["id-from-catalog-1","id-from-catalog-2"]}

                Select up to $maxSongs distinct IDs, in the order you recommend them.
                Use only IDs from the supplied catalog. Never invent IDs, tracks, artists or albums.
                Consider the user's listening counts and favourites as taste signals, but prioritize
                the request. Mix familiar favourites with relevant discovery where appropriate.
                Do not return explanations, markdown fences, or any other fields.

                Catalog:
                $catalog
            """.trimIndent()
            val content = requestChatCompletion(
                apiKey = apiKey,
                endpoint = settings.endpoint,
                model = settings.model,
                systemPrompt = "You are a careful music curator. You rank only the tracks in the provided catalogue and must return valid JSON.",
                userPrompt = userPrompt,
                maxTokens = (maxSongs * 24).coerceIn(128, 1200),
            )
            val ids = parseSongIds(content).distinct().take(maxSongs)
            val result = ids.mapNotNull(candidatesById::get)
            result.takeIf { it.isNotEmpty() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "AI mix request failed; offline Smart Mix will be used")
            null
        }
    }

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val apiKey = secretStore.getString(API_KEY)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Save an API key before testing the connection.")
        val settings = getSettings()
        requestChatCompletion(
            apiKey = apiKey,
            endpoint = settings.endpoint,
            model = settings.model,
            systemPrompt = "You are a connection test. Reply with the single word OK.",
            userPrompt = "Reply with OK.",
            maxTokens = 12,
        ).trim().take(120).ifBlank { "The provider responded but returned an empty message." }
    }

    private fun normalizeEndpoint(value: String): String {
        val endpoint = value.trim().trimEnd('/')
        val uri = runCatching { URI(endpoint) }.getOrNull()
            ?: throw IllegalArgumentException("Enter a valid API endpoint.")
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        val isLocalHttp = scheme == "http" && host in setOf("localhost", "127.0.0.1", "::1")
        require((scheme == "https" || isLocalHttp) && !host.isNullOrBlank()) {
            "Use an HTTPS endpoint (HTTP is allowed only for localhost)."
        }
        return endpoint
    }

    private fun requestChatCompletion(
        apiKey: String,
        endpoint: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
    ): String {
        val requestJson = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userPrompt))
            )
            .put("temperature", 0.25)
            .put("max_tokens", maxTokens)

        val request = Request.Builder()
            .url("${endpoint.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body
                ?: throw IllegalStateException("AI provider returned an empty response.")
            if (responseBody.contentLength() > 1_000_000L) {
                throw IllegalStateException("AI provider response exceeded the 1 MB safety limit.")
            }
            val body = responseBody.string().takeIf { it.length <= 1_000_000 }
                ?: throw IllegalStateException("AI provider response exceeded the 1 MB safety limit.")
            if (!response.isSuccessful) {
                val providerMessage = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                val suffix = providerMessage.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                throw IllegalStateException("AI provider returned HTTP ${response.code}$suffix")
            }
            val json = JSONObject(body)
            val content = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
            content?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("AI provider returned no message content.")
        }
    }

    internal fun parseSongIds(content: String): List<String> {
        val cleaned = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val json = JSONObject(cleaned)
        val array = json.optJSONArray("songIds") ?: json.optJSONArray("song_ids")
            ?: throw IllegalArgumentException("AI response did not contain songIds.")
        return buildList {
            for (index in 0 until array.length()) {
                val id = array.optString(index).takeIf { it.isNotBlank() } ?: continue
                add(id)
            }
        }
    }
}
