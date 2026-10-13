package com.castcharm.android.download

// "Always download 'Get Caught Up' items to device": while online, refresh the
// built-in playlist and queue a phone download for every member that is on the
// server but not on this phone, so the backlog is here if the user goes offline.
// Runs from SyncWorker (hourly), the immediate sync after login/reconnect and
// the Home tab refresh. The scheduler's duplicate guard makes repeats harmless,
// and its Wi-Fi-only/concurrency rules apply as for any download.

import android.content.Context
import android.util.Log
import com.castcharm.android.AUTO_DOWNLOAD_CATCHUP_KEY
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.repository.PlaylistRepository
import com.castcharm.android.dataStore
import com.castcharm.android.diag.DiagLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

object CatchUpDownloader {
    private const val PER_RUN_LIMIT = 50

    suspend fun isEnabled(context: Context): Boolean =
        context.dataStore.data.map { it[AUTO_DOWNLOAD_CATCHUP_KEY] ?: false }.first()

    /** Returns the number of downloads queued, or -1 when skipped. */
    suspend fun run(context: Context): Int {
        if (!isEnabled(context)) return -1
        if (CastCharmApp.isOfflineMode || !CastCharmApp.apiClient.isInitialized) return -1
        val repo = PlaylistRepository(AppDatabase.getDatabase(context))
        return try {
            repo.refreshAll(maxMembers = 0)
            val system = repo.systemPlaylistOnce() ?: return -1
            repo.refreshMembers(system.id)
            val queued = repo.downloadAll(system.id, limit = PER_RUN_LIMIT)
            if (queued > 0) DiagLog.log("catchup", "queued $queued download(s) for '${system.name}'")
            // Files this feature saved that have since left the list (played,
            // aged out, rules changed) are let go — unless something else still
            // wants them, in which case only the Get Caught Up claim is dropped.
            val db = AppDatabase.getDatabase(context)
            val current = repo.membersOnce(system.id, localOnly = false).map { it.id }.toSet()
            val storage = StorageManager(context)
            var freed = 0
            for (id in db.downloadOwnerDao().episodesOwnedBy(DownloadOwners.CATCHUP)) {
                if (id in current) continue
                if (storage.releaseOwner(id, DownloadOwners.CATCHUP)) freed++
            }
            if (freed > 0) DiagLog.log("catchup", "removed $freed file(s) that left '${system.name}' and had no other owner")
            queued
        } catch (e: Exception) {
            Log.w("CatchUpDownloader", "Get Caught Up download pass failed", e)
            -1
        }
    }
}
