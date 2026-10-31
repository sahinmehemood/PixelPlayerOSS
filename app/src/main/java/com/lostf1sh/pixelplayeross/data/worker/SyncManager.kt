package com.lostf1sh.pixelplayeross.data.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.utils.mediaStoreGenerationsChanged
import com.lostf1sh.pixelplayeross.utils.readExternalMediaStoreGenerations
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import com.lostf1sh.pixelplayeross.data.observer.MediaStoreObserver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Data class representing the progress of the sync operation.
 */
data class SyncProgress(
    val isRunning: Boolean = false,
    val currentCount: Int = 0,
    val totalCount: Int = 0,
    val isCompleted: Boolean = false,
    val phase: SyncPhase = SyncPhase.IDLE
) {
    enum class SyncPhase {
        IDLE,
        FETCHING_MEDIASTORE,
        PROCESSING_FILES,
        SAVING_TO_DATABASE,
        SCANNING_LRC,
        CLEANING_CACHE,
        COMPLETING
    }

    val progress: Float
        get() = if (totalCount > 0) currentCount.toFloat() / totalCount else 0f

    val hasProgress: Boolean
        get() = totalCount > 0
    }

@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val mediaStoreObserver: MediaStoreObserver,
    private val localLibraryChangeTracker: LocalLibraryChangeTracker
) {
    private val workManager = WorkManager.getInstance(context)
    private val sharingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mediaStoreAutoSyncJob: Job? = null
    private val autoSyncLock = Any()
    // Delayed follow-up once the consecutive follow-up cap is hit; see scheduleBackoffFollowUpSync.
    private var cappedFollowUpJob: Job? = null
    @Volatile
    private var lastForegroundSyncTime = 0L
    // Serializes the background snapshot (onStop) and the foreground comparison (onStart), and
    // keeps their MediaStore binder calls off the main thread.
    private val foregroundCheckDispatcher = Dispatchers.IO.limitedParallelism(1)
    @Volatile
    private var backgroundMediaStoreGenerations: Map<String, Long>? = null
    @Volatile
    private var currentSyncWorkId: UUID? = null

    val isSyncing: Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
            .map { workInfos ->
                val isRunning = workInfos.any { it.state == WorkInfo.State.RUNNING }
                val isFreshlyEnqueued = workInfos.any {
                    it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0
                }
                isRunning || isFreshlyEnqueued
            }
            .distinctUntilChanged()
            .shareIn(
                scope = sharingScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                replay = 1
            )

    /**
     * Emits once each time the library sync finishes in a FAILED state with no other
     * run still active. Used to surface a one-shot "sync failed" message to the user,
     * which the [syncProgress] flow alone cannot do (it silently reverts to idle).
     */
    val syncFailed: Flow<Unit> =
        workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
            .map { workInfos ->
                latestForegroundSyncWork(workInfos)?.state == WorkInfo.State.FAILED
            }
            .distinctUntilChanged()
            .filter { it }
            .map { }
            .shareIn(
                scope = sharingScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                replay = 0
            )

    private val started = AtomicBoolean(false)

    /**
     * Starts background observation (storage-change auto-sync, app-foreground catch-up)
     * and schedules periodic maintenance. Called once from PixelPlayerApplication.onCreate
     * so the launch site is explicit and greppable instead of hidden in DI construction.
     * Idempotent — extra calls are no-ops.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        observeStorageChanges()
        observeSyncCompletion()
        observeAppForeground()
        schedulePeriodicMaintenance()
    }

    /**
     * Schedules the once-a-day heavy maintenance (LRC/cache). Uses a dedicated unique
     * name distinct from [SyncWorker.WORK_NAME], so it never drives the foreground sync
     * indicator. KEEP preserves the existing schedule across launches.
     */
    private fun schedulePeriodicMaintenance() {
        workManager.enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_MAINTENANCE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            SyncWorker.periodicMaintenanceWork()
        )
    }

    /**
     * Flow that exposes the detailed sync progress including song count.
     */
    val syncProgress: Flow<SyncProgress> =
        workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
            .map { workInfos ->
                val runningWork = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                val succeededWork = workInfos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED }
                val enqueuedWork = workInfos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }

                when {
                    runningWork != null -> {
                        val current = runningWork.progress.getInt(SyncWorker.PROGRESS_CURRENT, 0)
                        val total = runningWork.progress.getInt(SyncWorker.PROGRESS_TOTAL, 0)
                        val phaseOrdinal = runningWork.progress.getInt(SyncWorker.PROGRESS_PHASE, 0)
                        val phase = try {
                            SyncProgress.SyncPhase.entries[phaseOrdinal]
                        } catch (e: IndexOutOfBoundsException) {
                            SyncProgress.SyncPhase.IDLE
                        }
                        SyncProgress(
                            isRunning = true,
                            currentCount = current,
                            totalCount = total,
                            isCompleted = false,
                            phase = phase
                        )
                    }
                    succeededWork != null -> {
                        val total = readCompletedSongCount(succeededWork)
                        SyncProgress(
                            isRunning = false,
                            currentCount = total,
                            totalCount = total,
                            isCompleted = true,
                            phase = SyncProgress.SyncPhase.COMPLETING
                        )
                    }
                    enqueuedWork != null -> {
                        if (enqueuedWork.runAttemptCount == 0) {
                            SyncProgress(isRunning = true, isCompleted = false, phase = SyncProgress.SyncPhase.IDLE)
                        } else {
                            SyncProgress()
                        }
                    }
                    else -> SyncProgress()
                }
            }
            .distinctUntilChanged()
            .shareIn(
                scope = sharingScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                replay = 1
            )

    /**
     * Emits `true` while the worker is in the early "library changes" phases —
     * scanning MediaStore for added/removed/modified files and writing them to the
     * unified DB. This is what powers the pull-to-refresh indicator: the UI only
     * needs to confirm that local additions/deletions have landed.
     */
    val isFetchingChanges: Flow<Boolean> = syncProgress
        .map { progress ->
            progress.isRunning && progress.phase in CHANGE_PHASES
        }
        .distinctUntilChanged()
        .shareIn(
            scope = sharingScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            replay = 1
        )

    /**
     * Emits `true` while the worker is performing background maintenance that does
     * not gate the user's pull-to-refresh gesture: LRC scanning, album-art cache
     * cleanup. This worker never synchronizes remote music providers. This drives the slim linear
     * indicator under [LibraryActionRow].
     */
    val isPerformingMaintenance: Flow<Boolean> = syncProgress
        .map { progress ->
            progress.isRunning && progress.phase in MAINTENANCE_PHASES
        }
        .distinctUntilChanged()
        .shareIn(
            scope = sharingScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            replay = 1
        )

    fun sync() {
        sharingScope.launch {
            val now = System.currentTimeMillis()
            val lastSyncTimestamp = userPreferencesRepository.getLastSyncTimestamp()
            val shouldRunSync =
                lastSyncTimestamp <= 0L || (now - lastSyncTimestamp) >= MIN_SYNC_INTERVAL_MS

            if (!shouldRunSync) {
                val ageSeconds = (now - lastSyncTimestamp) / 1000
                Timber.tag(TAG).d("Skipping startup sync (last sync ${ageSeconds}s ago)")
                return@launch
            }

            Timber.tag(TAG).i("Startup sync requested - Scheduling Incremental Sync")
            enqueueSyncWork(
                request = SyncWorker.incrementalSyncWork(),
                policy = ExistingWorkPolicy.KEEP,
                notifyObserver = false
            )
        }
    }

    /**
     * Performs an incremental sync, only processing files that have changed
     * since the last sync. Much faster for large libraries with few changes.
     * This is the recommended sync method for pull-to-refresh actions.
     */
    fun incrementalSync() {
        Timber.tag(TAG).i("Incremental sync requested - Scheduling incremental worker")
        enqueueSyncWork(
            // Explicit user request (pull-to-refresh): run now even during playback.
            request = SyncWorker.incrementalSyncWork(
                runMaintenance = false,
                bypassPlaybackDeferral = true
            ),
            policy = ExistingWorkPolicy.REPLACE
        )
    }

    /**
     * Performs a full library rescan, ignoring the last sync timestamp.
     * Use this when the user explicitly wants to force a complete rescan.
     */
    fun fullSync(deepScan: Boolean = true) {
        Timber.tag(TAG).i("Full sync requested - Scheduling full sync worker")
        enqueueSyncWork(
            request = SyncWorker.fullSyncWork(deepScan = deepScan),
            policy = ExistingWorkPolicy.REPLACE
        )
    }

    /**
     * Rebuilds local MediaStore-backed songs from scratch while preserving Telegram channel and message caches.
     * Local imported lyrics, favorites, and user metadata edits are removed for the rebuilt songs.
     * Use when local library data is corrupted or songs are missing.
     */
    fun rebuildDatabase() {
        Timber.tag(TAG).i("Rebuild database requested - Scheduling rebuild worker")
        enqueueSyncWork(
            request = SyncWorker.rebuildDatabaseWork(),
            policy = ExistingWorkPolicy.REPLACE
        )
    }

    /**
     * Schedules a fresh local incremental sync, replacing any pending foreground sync work.
     * The worker detects directory-rule version changes, so this still becomes a full local pass
     * when the caller just changed the selected folders.
     */
    fun forceRefresh() {
        Timber.tag(TAG).i("Manual local refresh requested - Scheduling incremental worker")
        enqueueSyncWork(
            // Explicit user request (refresh button, folder changes, end of setup): run now
            // even during playback.
            request = SyncWorker.incrementalSyncWork(
                runMaintenance = false,
                bypassPlaybackDeferral = true
            ),
            policy = ExistingWorkPolicy.REPLACE
        )
    }

    private fun readCompletedSongCount(workInfo: WorkInfo): Int {
        val intValue = workInfo.outputData.getInt(SyncWorker.OUTPUT_TOTAL_SONGS, -1)
        if (intValue >= 0) return intValue

        return workInfo.outputData
            .getLong(SyncWorker.OUTPUT_TOTAL_SONGS, 0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun latestForegroundSyncWork(workInfos: List<WorkInfo>): WorkInfo? {
        val activeWork = workInfos.firstOrNull {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        }
        if (activeWork != null) {
            currentSyncWorkId = activeWork.id
            return activeWork
        }

        return currentSyncWorkId?.let { id ->
            workInfos.firstOrNull { it.id == id }
        }
    }

    private fun observeStorageChanges() {
        sharingScope.launch {
            mediaStoreObserver.externalMediaStoreChanges.collect {
                // Recorded before the debounce: a sync that is already running when this
                // change lands then gets a follow-up even if the debounced request is dropped.
                localLibraryChangeTracker.onMediaStoreChanged()
                scheduleLocalAutoSync()
            }
        }
    }

    /**
     * Storage-change and catch-up syncs use KEEP, so a request made while a sync is running is
     * dropped, and that sync may already have read MediaStore. When the sync finishes and
     * MediaStore changed after it started reading, schedule one follow-up local sync.
     */
    private fun observeSyncCompletion() {
        sharingScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
                // Keep the work id so a new run that finishes in the same state as the
                // previous one is still seen as a separate completion.
                .map { workInfos -> workInfos.map { it.id to it.state } }
                .distinctUntilChanged()
                .collect { work ->
                    val states = work.map { (_, state) -> state }
                    if (states.any { !it.isFinished }) return@collect
                    if (!localLibraryChangeTracker.hasUnscannedChanges()) {
                        localLibraryChangeTracker.resetFollowUps()
                        return@collect
                    }
                    if (!userPreferencesRepository.initialSetupDoneFlow.first()) return@collect

                    val followUps = localLibraryChangeTracker.consecutiveFollowUps()
                    when (
                        followUpSyncDecision(
                            states = states,
                            hasUnscannedChanges = true,
                            consecutiveFollowUps = followUps
                        )
                    ) {
                        FollowUpSyncDecision.NONE -> Unit
                        FollowUpSyncDecision.NOW -> {
                            localLibraryChangeTracker.recordFollowUp()
                            Timber.tag(TAG).i(
                                "MediaStore changed during the last sync - scheduling a follow-up local sync"
                            )
                            // Picks up files that landed during the previous sync, often one the
                            // user is waiting on; a cheap delta scan, so it also runs during playback.
                            enqueueBypassLocalSync()
                        }
                        FollowUpSyncDecision.AFTER_BACKOFF -> scheduleBackoffFollowUpSync(followUps)
                    }
                }
        }
    }

    /**
     * Past the follow-up cap MediaStore changed during every sync, as during a long copy. The
     * last of those changes must not be dropped, so check again once after
     * [FOLLOW_UP_CAP_BACKOFF_MS]. The counter stays at the cap until a sync finishes with nothing
     * left to scan, so a feedback loop costs one sync per backoff period. Only called from the
     * [observeSyncCompletion] collector, which also owns [cappedFollowUpJob].
     */
    private fun scheduleBackoffFollowUpSync(followUps: Int) {
        if (cappedFollowUpJob?.isActive == true) return
        Timber.tag(TAG).w(
            "MediaStore keeps changing during syncs ($followUps follow-ups in a row) - checking again in ${FOLLOW_UP_CAP_BACKOFF_MS / 1_000}s"
        )
        cappedFollowUpJob = sharingScope.launch {
            delay(FOLLOW_UP_CAP_BACKOFF_MS)
            // A sync that ran in the meantime may already have picked the changes up.
            if (!localLibraryChangeTracker.hasUnscannedChanges()) return@launch
            if (!userPreferencesRepository.initialSetupDoneFlow.first()) return@launch
            Timber.tag(TAG).i("Scheduling a delayed follow-up local sync")
            enqueueBypassLocalSync()
        }
    }

    private fun scheduleLocalAutoSync() {
        synchronized(autoSyncLock) {
            mediaStoreAutoSyncJob?.cancel()
            mediaStoreAutoSyncJob = sharingScope.launch {
                runLocalAutoSyncAfterDebounce()
            }
        }
    }

    private suspend fun runLocalAutoSyncAfterDebounce() {
        delay(MEDIASTORE_CHANGE_DEBOUNCE_MS)
        if (!userPreferencesRepository.initialSetupDoneFlow.first()) {
            Timber.tag(TAG).d("Skipping storage-change sync: initial setup not finished")
            return
        }
        Timber.tag(TAG).i("Storage change detected - scheduling local incremental sync")
        // Auto-add of a just-downloaded/added file: the user is looking at the
        // library expecting it to appear, so bypass the playback-active deferral.
        enqueueBypassLocalSync()
    }

    private fun observeAppForeground() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                sharingScope.launch(foregroundCheckDispatcher) {
                    val changedWhileAway = mediaStoreGenerationsChanged(
                        before = backgroundMediaStoreGenerations,
                        after = readExternalMediaStoreGenerations(context)
                    )
                    if (changedWhileAway) {
                        Timber.tag(TAG).i("MediaStore changed while the app was in the background")
                        // A sync that is still running gets a follow-up for this change too.
                        localLibraryChangeTracker.onMediaStoreChanged()
                    }
                    maybeRunForegroundCatchUpSync(mediaStoreChangedWhileAway = changedWhileAway)
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                // The ContentObserver stops listening now; remember where MediaStore was so the
                // next onStart can tell whether anything changed in between.
                sharingScope.launch(foregroundCheckDispatcher) {
                    backgroundMediaStoreGenerations =
                        readExternalMediaStoreGenerations(context).takeIf { it.isNotEmpty() }
                }
            }
        })
    }

    /**
     * Fast, maintenance-free incremental sync triggered when the app returns to the
     * foreground. Catches files MediaStore indexed while we were backgrounded (the
     * ContentObserver is only registered in the foreground). Guarded by an in-memory
     * cooldown so quick minimize/restore cycles don't pile up redundant work, unless
     * MediaStore changed while the app was in the background.
     */
    private suspend fun maybeRunForegroundCatchUpSync(mediaStoreChangedWhileAway: Boolean) {
        val now = System.currentTimeMillis()
        if (
            !shouldRunForegroundCatchUp(
                nowMs = now,
                lastCatchUpMs = lastForegroundSyncTime,
                cooldownMs = FOREGROUND_SYNC_COOLDOWN_MS,
                mediaStoreChangedWhileAway = mediaStoreChangedWhileAway
            )
        ) {
            Timber.tag(TAG).d("Skipping foreground catch-up sync (cooldown active)")
            return
        }
        if (!userPreferencesRepository.initialSetupDoneFlow.first()) {
            Timber.tag(TAG).d("Skipping foreground catch-up sync: initial setup not finished")
            return
        }
        lastForegroundSyncTime = now
        Timber.tag(TAG).i("Foreground catch-up - scheduling local incremental sync")
        // User just returned to the app; surface anything indexed while backgrounded
        // without waiting for playback to stop.
        enqueueBypassLocalSync()
    }

    /**
     * Enqueues a maintenance-free local delta scan that runs even during playback. KEEP merges it
     * into sync work that is already pending, except for a sync that is only waiting out its
     * playback deferral (see [playbackDeferredSyncWorkIds]): KEEP would leave the scan stuck
     * behind it for up to about 15 minutes, so that work is cancelled first. Its maintenance pass,
     * if it had one, is left to the daily periodic maintenance. Cancelling by id rather than
     * enqueueing with REPLACE never cancels sync work another caller enqueued in the meantime.
     */
    private suspend fun enqueueBypassLocalSync() {
        val request = SyncWorker.incrementalSyncWork(
            runMaintenance = false,
            bypassPlaybackDeferral = true
        )
        val deferredWorkIds = playbackDeferredSyncWorkIds(
            workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME).first()
        )
        if (deferredWorkIds.isNotEmpty()) {
            Timber.tag(TAG).i("Replacing a sync that is waiting for playback to stop")
            deferredWorkIds.forEach { workManager.cancelWorkById(it) }
        }
        enqueueSyncWork(
            request = request,
            policy = ExistingWorkPolicy.KEEP,
            notifyObserver = false
        )
    }

    private fun enqueueSyncWork(
        request: OneTimeWorkRequest,
        policy: ExistingWorkPolicy,
        notifyObserver: Boolean = true
    ) {
        currentSyncWorkId = request.id
        workManager.enqueueUniqueWork(
            SyncWorker.WORK_NAME,
            policy,
            request
        )
        if (notifyObserver) {
            mediaStoreObserver.forceRescan()
        }
    }

    companion object {
        private const val TAG = "SyncManager"
        private const val MIN_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val MEDIASTORE_CHANGE_DEBOUNCE_MS = 1_500L
        private const val FOREGROUND_SYNC_COOLDOWN_MS = 60_000L
        private const val FOLLOW_UP_CAP_BACKOFF_MS = 30_000L

        private val CHANGE_PHASES = setOf(
            SyncProgress.SyncPhase.IDLE,
            SyncProgress.SyncPhase.FETCHING_MEDIASTORE,
            SyncProgress.SyncPhase.PROCESSING_FILES,
            SyncProgress.SyncPhase.SAVING_TO_DATABASE
        )

        private val MAINTENANCE_PHASES = setOf(
            SyncProgress.SyncPhase.SCANNING_LRC,
            SyncProgress.SyncPhase.CLEANING_CACHE,
            SyncProgress.SyncPhase.COMPLETING
        )
    }
}
