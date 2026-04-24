package com.castcharm.android.sync

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.models.ProgressRequest
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (CastCharmApp.isOfflineMode) {
            Log.d("SyncWorker", "Skipping sync because offline mode is active")
            return@withContext Result.retry()
        }

        val db = AppDatabase.getDatabase(applicationContext)
        val dao = db.episodeDao()

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

        val pending = dao.getPendingSyncEpisodes()
        if (pending.isEmpty()) {
            Log.d("SyncWorker", "No pending episode sync work")
            return@withContext Result.success()
        }

        Log.d("SyncWorker", "Syncing ${pending.size} episodes")

        var allSuccessful = true

        pending.forEach { episode ->
            try {
                if (episode.sync_pending_played) {
                    api.togglePlayed(episode.id)
                    dao.updatePlayedStatus(
                        episode.id,
                        episode.played,
                        episode.last_played_at ?: System.currentTimeMillis(),
                        pending = false
                    )
                }

                if (episode.sync_pending_progress) {
                    api.updateProgress(
                        episode.id,
                        ProgressRequest(episode.play_position_seconds)
                    )
                    dao.updateProgress(
                        episode.id,
                        episode.play_position_seconds,
                        episode.last_played_at ?: System.currentTimeMillis(),
                        pending = false
                    )
                }
            } catch (e: Exception) {
                Log.e("SyncWorker", "Failed to sync episode ${episode.id}", e)
                allSuccessful = false
            }
        }

        if (allSuccessful) Result.success() else Result.retry()
    }
}