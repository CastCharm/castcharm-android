package com.castcharm.android.notifications

// NewEpisodesNotifier owns the "new_episodes" NotificationChannel and posts a
// summary notification when SyncWorker pulls episodes with IDs above the
// previously-notified high-water mark. Kept separate from SyncWorker so the
// worker stays focused on flushing pending changes.

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import com.castcharm.android.LAST_NOTIFIED_EPISODE_ID_KEY
import com.castcharm.android.MainActivity
import com.castcharm.android.NOTIFY_NEW_EPISODES_KEY
import com.castcharm.android.R
import com.castcharm.android.data.api.CastCharmApi
import com.castcharm.android.dataStore
import kotlinx.coroutines.flow.first

const val NEW_EPISODES_CHANNEL_ID = "new_episodes"
private const val NEW_EPISODES_NOTIFICATION_ID = 4001

object NewEpisodesNotifier {

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NEW_EPISODES_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NEW_EPISODES_CHANNEL_ID,
            "New episodes",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Fires when a background sync finds new episodes on your server."
        }
        manager.createNotificationChannel(channel)
    }

    // Fetches the newest episodes from the server, compares against the stored
    // high-water mark, and posts one summary notification if any are new. The
    // high-water mark is updated regardless of whether a notification is posted
    // (e.g., permission denied) so the user isn't spammed once permission is
    // granted later.
    suspend fun maybeNotify(context: Context, api: CastCharmApi) {
        val prefs = context.dataStore.data.first()
        val enabled = prefs[NOTIFY_NEW_EPISODES_KEY] ?: false
        if (!enabled) return

        val lastNotifiedId = prefs[LAST_NOTIFIED_EPISODE_ID_KEY] ?: 0L

        val recent = runCatching {
            api.getAllEpisodes(limit = 50, offset = 0, order = "desc")
        }.getOrNull() ?: return

        if (recent.isEmpty()) return

        val maxId = recent.maxOf { it.id.toLong() }
        // Seed the high-water mark on first run without posting a notification —
        // otherwise the very first sync after enabling would announce "50 new
        // episodes" that the user already knows about.
        if (lastNotifiedId == 0L) {
            context.dataStore.edit { it[LAST_NOTIFIED_EPISODE_ID_KEY] = maxId }
            return
        }

        val newEpisodes = recent.filter { it.id.toLong() > lastNotifiedId }
        if (newEpisodes.isEmpty()) return

        val count = newEpisodes.size
        val feedTitles = newEpisodes.map { it.feed_title.orEmpty() }.filter { it.isNotBlank() }.distinct()
        val title = if (count == 1) "1 new episode" else "$count new episodes"
        val body = when {
            feedTitles.isEmpty() -> "Open CastCharm to listen."
            feedTitles.size == 1 -> "From ${feedTitles.first()}"
            feedTitles.size <= 3 -> "From ${feedTitles.joinToString(", ")}"
            else -> "From ${feedTitles.take(2).joinToString(", ")} and ${feedTitles.size - 2} more"
        }

        // Always advance the high-water mark first; if notification posting
        // fails (permission revoked mid-flight) we still don't re-notify next
        // cycle.
        context.dataStore.edit { it[LAST_NOTIFIED_EPISODE_ID_KEY] = maxId }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NEW_EPISODES_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NEW_EPISODES_NOTIFICATION_ID, notification)
        }
    }
}
