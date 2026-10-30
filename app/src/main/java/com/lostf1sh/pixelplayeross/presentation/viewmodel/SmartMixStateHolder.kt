package com.lostf1sh.pixelplayeross.presentation.viewmodel

import android.content.Context
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.DailyMixManager
import com.lostf1sh.pixelplayeross.data.ai.AiPersonalizationRepository
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.playlist.NlpPlaylistGenerator
import com.lostf1sh.pixelplayeross.data.playlist.nlp.MixSuggestion
import com.lostf1sh.pixelplayeross.data.playlist.nlp.MixSuggestionEngine
import com.lostf1sh.pixelplayeross.data.playlist.nlp.MixSuggestionPlays
import com.lostf1sh.pixelplayeross.data.preferences.MixPreferences
import com.lostf1sh.pixelplayeross.data.preferences.PlaylistPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.di.AppScope
import com.lostf1sh.pixelplayeross.presentation.components.requestText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** The composer's thinking animation stays on screen at least this long before a preview. */
internal const val MIX_MIN_THINKING_MS = 4_000L

/** Where the Smart Mix composer was opened from, which decides its primary action. */
enum class SmartMixTarget {
    /** Library: the preview can be played or saved as a playlist. */
    Library,
    /** Daily Mix: the preview can replace today's Daily Mix (with undo) or be saved. */
    DailyMix,
}

/**
 * State of the Smart Mix composer, shared by every entry point (Library, Daily Mix) and the
 * single host that renders the sheet. A generated mix is only a preview: playing it, saving it
 * as a playlist or using it for today are separate, explicit actions.
 */
@Singleton
class SmartMixStateHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val generator: NlpPlaylistGenerator,
    private val aiPersonalizationRepository: AiPersonalizationRepository,
    private val musicRepository: MusicRepository,
    private val dailyMixManager: DailyMixManager,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    private val dailyMixStateHolder: DailyMixStateHolder,
    private val mixPreferences: MixPreferences,
    @AppScope private val scope: CoroutineScope,
) {
    private val _target = MutableStateFlow<SmartMixTarget?>(null)
    /** Null while the composer is closed. */
    val target: StateFlow<SmartMixTarget?> = _target.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _mixPreview = MutableStateFlow<MixPreview?>(null)
    val mixPreview: StateFlow<MixPreview?> = _mixPreview.asStateFlow()

    private val _mixActionInProgress = MutableStateFlow(false)
    val mixActionInProgress: StateFlow<Boolean> = _mixActionInProgress.asStateFlow()

    private val _dailyMixUndoId = MutableStateFlow<Long?>(null)
    val dailyMixUndoId: StateFlow<Long?> = _dailyMixUndoId.asStateFlow()

    /** The composer's suggestion rows; the last set is kept so a reopened composer shows it at once. */
    private val _mixSuggestions = MutableStateFlow<List<List<MixSuggestion>>>(emptyList())
    val mixSuggestions: StateFlow<List<List<MixSuggestion>>> = _mixSuggestions.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** One-off confirmations for the host to show. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var suggestionsJob: Job? = null
    private var generationJob: Job? = null
    private var mixRequestId = 0L
    private var dailyMixUndo: Pair<List<Song>, List<String>>? = null

    fun open(target: SmartMixTarget) {
        if (_mixActionInProgress.value) return
        refreshMixSuggestions()
        resetMixPreview()
        _target.value = target
    }

    fun dismiss() {
        if (_mixActionInProgress.value) return
        resetMixPreview()
        _target.value = null
    }

    fun editMixRequest() {
        if (!_mixActionInProgress.value) {
            _mixPreview.value = null
            _error.value = null
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Rebuilds the suggestions from the library, listening history, recent requests and the
     * time of day. Runs off the main thread; a failure keeps the previous rows.
     */
    private fun refreshMixSuggestions() {
        suggestionsJob?.cancel()
        suggestionsJob = scope.launch(Dispatchers.Default) {
            try {
                val songs = musicRepository.getAllSongsOnce()
                val plays = dailyMixManager.getAllEngagementStats()
                    .mapValues { (_, stats) -> MixSuggestionPlays(stats.playCount, stats.lastPlayedTimestamp) }
                // Only offer what would actually produce a mix from this library.
                val probe = generator.createMixProbe(songs)
                val resources = context.resources
                _mixSuggestions.value = MixSuggestionEngine.suggest(
                    songs, plays, mixPreferences.recentMixRequests(), System.currentTimeMillis(),
                    canMix = { probe.canMix(it.requestText(resources)) },
                    textOf = { it.requestText(resources) },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "Mix suggestions failed")
            }
        }
    }

    private fun resetMixPreview() {
        generationJob?.cancel()
        generationJob = null
        mixRequestId++
        _mixPreview.value = null
        _error.value = null
        _isGenerating.value = false
    }

    /**
     * Generates a preview of up to [maxSongs] songs. The length is an upper bound filled with
     * every match: both bounds are [maxSongs], so an artist request is not cut to a random few.
     */
    fun generate(prompt: String, maxSongs: Int) {
        if (prompt.isBlank() || _isGenerating.value || _mixActionInProgress.value) return
        val target = _target.value ?: return
        val length = maxSongs.coerceIn(1, MAX_MIX_LENGTH)
        val requestId = ++mixRequestId
        _mixPreview.value = null
        _isGenerating.value = true
        _error.value = null

        generationJob = scope.launch {
            // Started alongside generation, so even an instant offline result shows the thinking
            // animation; cancellation cancels the timer too.
            val thinkingWindow = launch(start = CoroutineStart.UNDISPATCHED) { delay(MIX_MIN_THINKING_MS) }
            try {
                // AI is opt-in. If it is not configured, is rate-limited, or returns invalid IDs,
                // the existing fully-offline generator remains the reliable fallback.
                val aiSongs = aiPersonalizationRepository.generateMix(prompt, length)
                val songs = (aiSongs ?: generator.generate(prompt, length, length))
                    .distinctBy { it.id }
                    .take(length)
                thinkingWindow.join()
                if (requestId != mixRequestId) return@launch
                if (songs.isEmpty()) {
                    _error.value = context.getString(R.string.mix_no_matches)
                    return@launch
                }
                val request = prompt.replace(DIRECTIVE, " ").trim()
                val requestedCount = REQUESTED_LENGTH.find(prompt)?.groupValues?.get(1)?.toIntOrNull()
                // Remembered only once it produced a mix, so suggestions never offer a dead end.
                scope.launch {
                    try {
                        mixPreferences.recordMixRequest(request)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Timber.tag(TAG).w(error, "Recording mix request failed")
                    }
                }
                _mixPreview.value = MixPreview(
                    requestId = requestId,
                    request = request,
                    suggestedName = request.replace(WHITESPACE, " ").take(MAX_NAME_LENGTH)
                        .replaceFirstChar { it.titlecase() },
                    songs = songs.toImmutableList(),
                    requestedCount = requestedCount,
                    isDailyMix = target == SmartMixTarget.DailyMix,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                thinkingWindow.join()
                if (requestId == mixRequestId) {
                    Timber.tag(TAG).e(error, "Mix preview failed")
                    _error.value = context.getString(R.string.mix_no_matches)
                }
            } finally {
                if (requestId == mixRequestId) _isGenerating.value = false
            }
        }
    }

    fun saveMixPreview(name: String) {
        val preview = _mixPreview.value ?: return
        if (_mixActionInProgress.value || preview.savedName != null || name.isBlank()) return
        _mixActionInProgress.value = true
        _error.value = null
        scope.launch {
            try {
                val existingNames = playlistPreferencesRepository.getPlaylistsOnce().map { it.name.trim() }.toSet()
                val resolvedName = uniqueName(name.trim(), existingNames)
                val playlist = playlistPreferencesRepository.createPlaylist(
                    name = resolvedName,
                    songIds = preview.songs.map { it.id },
                )
                // Generated playlists are exposure for later mixes, never evidence of taste.
                try {
                    mixPreferences.markSmartMixPlaylist(playlist.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.tag(TAG).w(error, "Marking Smart Mix playlist failed")
                }
                if (_mixPreview.value?.requestId == preview.requestId) {
                    _mixPreview.value = preview.copy(savedName = resolvedName)
                }
                _messages.tryEmit(context.getString(R.string.mix_saved, resolvedName))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "Saving mix failed")
                _error.value = context.getString(R.string.mix_save_failed)
            } finally {
                _mixActionInProgress.value = false
            }
        }
    }

    /** Closes the composer and hands back the preview to play, or null when there is none. */
    fun takeMixPreviewForPlayback(): MixPreview? {
        val preview = _mixPreview.value ?: return null
        if (_mixActionInProgress.value || preview.songs.isEmpty()) return null
        dismiss()
        return preview
    }

    /** Replaces today's Daily Mix with the preview; [undoDailyMixUpdate] restores the previous one. */
    fun applyMixPreviewForToday() {
        val preview = _mixPreview.value ?: return
        if (_mixActionInProgress.value || !preview.isDailyMix) return
        dailyMixUndo = dailyMixStateHolder.dailyMixSongs.value.toList() to preview.songs.map { it.id }
        dailyMixStateHolder.setDailyMixSongs(preview.songs)
        dismiss()
        _dailyMixUndoId.value = preview.requestId
    }

    fun undoDailyMixUpdate(undoId: Long) {
        if (_dailyMixUndoId.value != undoId) return
        val undo = dailyMixUndo ?: return
        // Do not overwrite a newer automatic refresh or an edit made after this replacement.
        if (dailyMixStateHolder.dailyMixSongs.value.map { it.id } == undo.second) {
            dailyMixStateHolder.setDailyMixSongs(undo.first)
        }
        clearDailyMixUndo(undoId)
    }

    fun clearDailyMixUndo(undoId: Long) {
        if (_dailyMixUndoId.value == undoId) {
            _dailyMixUndoId.value = null
            dailyMixUndo = null
        }
    }

    /** Forgets recent requests and the freshness memory; suggestions rebuild on next open. */
    suspend fun clearHistory() {
        generator.clearHistory()
        suggestionsJob?.cancel()
        _mixSuggestions.value = emptyList()
    }

    private fun uniqueName(requested: String, existingNames: Set<String>): String {
        val normalizedExisting = existingNames.mapTo(HashSet()) { it.lowercase() }
        val base = requested.ifBlank { context.getString(R.string.mix_title) }
        if (base.lowercase() !in normalizedExisting) return base
        var counter = 2
        while ("$base $counter".lowercase() in normalizedExisting) counter++
        return "$base $counter"
    }

    private companion object {
        const val TAG = "SmartMix"
        const val MAX_MIX_LENGTH = 100
        const val MAX_NAME_LENGTH = 60
        val DIRECTIVE = Regex("(?i)\\[(?:Target BPM|Target Length):[^\\]]*\\]")
        val REQUESTED_LENGTH = Regex("(?i)\\[Target Length:\\s*(\\d+) songs\\]")
        val WHITESPACE = Regex("\\s+")
    }
}
