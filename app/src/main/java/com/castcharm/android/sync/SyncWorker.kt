package com.castcharm.android.sync

// SyncWorker flushes offline-accumulated changes (played status, playback progress)
// from the local DB to the server. It runs in two scenarios:
//   - Periodically (every hour) via the PeriodicWorkRequest scheduled in MainActivity.
//   - Immediately after login or reconnect via the OneTimeWorkRequest enqueued by
//     AppSessionManager.enqueueImmediateSync().
//
// If any episode sync fails, the worker returns Result.retry() so WorkManager
// schedules another attempt with exponential backoff. Successfully synced episodes
// have their sync_pending_* flags cleared so they are not re-sent on the next run.
//
// The ApiClient lazy-initialization at the top of doWork() handles the case where
// WorkManager runs the worker after a device restart before the app process has
// started — the ApiClient would not yet be initialized from the normal flow.

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.setPlayed
import com.castcharm.android.data.api.models.clampProgressSeconds
import com.castcharm.android.data.api.models.progressRequest
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.download.LocalArtwork
import com.castcharm.android.dataStore
import com.castcharm.android.notifications.NewEpisodesNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Don't try to sync while explicitly in offline mode — the user chose
        // to work offline. Return success() rather than retry(): offline mode
        // isn't a failure, and a one-time worker enqueued while offline
        // shouldn't burn backoff attempts. The periodic worker will run again
        // on its next scheduled tick regardless.
        if (CastCharmApp.isOfflineMode) {
            Log.d("SyncWorker", "Skipping sync because offline mode is active")
            return@withContext Result.success()
        }

        val db = AppDatabase.getDatabase(applicationContext)
        val dao = db.episodeDao()

        // Lazy ApiClient initialization: if the app process restarted (e.g., after
        // a device reboot) the ApiClient may not yet be initialized. Read the saved
        // server URL from DataStore and initialize it before proceeding.
        try {
            if (!CastCharmApp.apiClient.isInitialized) {
                val savedUrl = applicationContext.dataStore.data
                    .first()[stringPreferencesKey("server_url")]

                if (savedUrl.isNullOrBlank()) {
                    Log.w("SyncWorker", "No saved server URL; cannot initialize ApiClient")
                    return@withContext Result.retry()
                }

                CastCharmApp.apiClient.initialize(savedUrl)
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Failed to initialize ApiClient for sync", e)
            return@withContext Result.retry()
        }

        val api = try {
            CastCharmApp.apiClient.getApi()
        } catch (e: Exception) {
            Log.e("SyncWorker", "ApiClient unavailable during sync", e)
            return@withContext Result.retry()
        }

        // Piggyback: check for new episodes on the server and post a summary
        // notification if the user opted in. Runs regardless of whether there
        // are pending flushes so the notification cadence tracks feed activity,
        // not the phone's outbound queue.
        runCatching { NewEpisodesNotifier.maybeNotify(applicationContext, api) }
            .onFailure { Log.w("SyncWorker", "New-episodes check failed", it) }

        // Housekeeping that needs the server: re-link any downloaded files the
        // local table lost track of, and restart any stalled download queue.
        runCatching { com.castcharm.android.download.StorageManager(applicationContext).reconcileOrphanFiles(api) }
            .onFailure { Log.w("SyncWorker", "Orphan file reconcile failed", it) }
        runCatching { com.castcharm.android.download.DownloadScheduler(applicationContext).kickQueue() }
        // Piggyback: make sure every feed with episodes downloaded to this device
        // also has its cover stored locally. New downloads store it as they
        // finish, but anything already on the phone would otherwise never
        // acquire one — and artwork is only of any use offline if it is fetched
        // while there is still a network. Runs before the early return below,
        // because having nothing to flush is the normal case, not a reason to
        // skip this. Bounded by the number of feeds, and each call is a no-op
        // once the file exists.
        runCatching {
            val downloaded = dao.getDownloadedEpisodesOnce()
            downloaded.map { it.feed_id }.distinct()
                .forEach { LocalArtwork.ensureFeed(applicationContext, it) }
            downloaded.forEach { LocalArtwork.ensureEpisode(applicationContext, it.id) }
        }.onFailure { Log.w("SyncWorker", "Cover art backfill failed", it) }

        // Fetch all episodes with pending sync flags set.
        val pending = dao.getPendingSyncEpisodes()
        if (pending.isEmpty()) {
            Log.d("SyncWorker", "No pending episode sync work")
            return@withContext Result.success()
        }

        Log.d("SyncWorker", "Syncing ${pending.size} episodes")

        var allSuccessful = true

        // Process each pending episode independently so a single failure does not
        // prevent other episodes from being synced.
        pending.forEach { episode ->
            try {
                // Flush played status first (so progress doesn't mark it unplayed
                // if the episode was toggled played while offline).
                // Only the flag is cleared, and only if the row still holds the
                // value that was sent. Rewriting the value here would revert any
                // progress the player wrote while this flush was in flight.
                if (episode.sync_pending_played) {
                    // Set, not toggle: a flush replays a decision the phone already
                    // made, so it has to be idempotent. Only the flag is cleared, and
                    // only if the row still holds the value that was sent — rewriting
                    // the value would revert progress the player wrote meanwhile.
                    api.setPlayed(episode.id, episode.played)
                    dao.clearPendingPlayedIf(episode.id, episode.played)
                }

                if (episode.sync_pending_progress) {
                    val position = clampProgressSeconds(episode.play_position_seconds)
                    api.updateProgress(episode.id, progressRequest(position))
                    dao.clearPendingProgressIf(episode.id, episode.play_position_seconds)
                }
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 404) {
                    // The episode no longer exists on the server; nothing to flush.
                    Log.w("SyncWorker", "Episode ${episode.id} gone from server; dropping pending changes")
                    dao.clearPendingFlags(episode.id)
                } else {
                    Log.e("SyncWorker", "Failed to sync episode ${episode.id}: HTTP ${e.code()}")
                    allSuccessful = false
                }
            } catch (e: Exception) {
                Log.e("SyncWorker", "Failed to sync episode ${episode.id}", e)
                allSuccessful = false
            }
        }

        // Return retry() if any episode failed — WorkManager will retry the whole
        // worker (only the episodes still flagged sync_pending_* will be re-sent).
        if (allSuccessful) Result.success() else Result.retry()
    }
}