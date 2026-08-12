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
                if (episode.sync_pending_played) {
                    // Set, not toggle. A flush replays a decision the phone already
                    // made, so it has to be idempotent — this used to flip whatever
                    // the server happened to hold, which drove the state backwards
                    // whenever the server already agreed.
                    api.setPlayed(episode.id, episode.played)
                    dao.updatePlayedStatus(
                        episode.id,
                        episode.played,
                        episode.last_played_at ?: System.currentTimeMillis(),
                        pending = false
                    )
                }

                if (episode.sync_pending_progress) {
                    // Clamped once and used for both, so a row written before this
                    // existed is healed rather than replayed: sending the clamped
                    // value while writing back the original would leave the local
                    // copy permanently disagreeing with the server.
                    val position = clampProgressSeconds(episode.play_position_seconds)
                    api.updateProgress(episode.id, progressRequest(position))
                    dao.updateProgress(
                        episode.id,
                        position,
                        episode.last_played_at ?: System.currentTimeMillis(),
                        pending = false
                    )
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