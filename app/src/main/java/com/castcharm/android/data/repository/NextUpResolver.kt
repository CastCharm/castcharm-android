package com.castcharm.android.data.repository

// Which episode "Continue" should resume for a chronological feed when the
// phone has to decide on its own (server unreachable, or offline mode).
//
// Order of trust:
//   1. what the server last said was next up for this feed (cached on the
//      feed row at every refresh), as long as that episode is still unplayed;
//   2. the most recently touched in-progress episode in the local table;
//   3. the episode the player last had loaded, if it belongs to this feed and
//      is still unplayed — better to resume where we were than to jump;
//   4. the oldest unplayed episode the phone knows about.
//
// The car used to get the fourth answer whenever the first three were missing
// locally, which is how a half-played episode was skipped for the next one.

import com.castcharm.android.data.db.dao.EpisodeDao
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.data.db.entities.FeedEntity

object NextUpResolver {

    data class Choice(val episode: EpisodeEntity, val reason: String)

    suspend fun resolve(
        feed: FeedEntity,
        episodeDao: EpisodeDao,
        lastPlayingEpisodeId: Int?,
        localOnly: Boolean,
    ): Choice? {
        val fromServer = feed.next_up_episode_id?.let { episodeDao.getEpisodeOnce(it) }
        if (fromServer != null && !fromServer.played && !fromServer.hidden &&
            (!localOnly || fromServer.local_path != null)) {
            return Choice(fromServer, "server next-up (cached)")
        }
        episodeDao.getInProgressForFeed(feed.id)?.let {
            if (!localOnly || it.local_path != null) return Choice(it, "local in-progress")
        }
        val last = lastPlayingEpisodeId?.let { episodeDao.getEpisodeOnce(it) }
        if (last != null && last.feed_id == feed.id && !last.played && !last.hidden &&
            (!localOnly || last.local_path != null)) {
            return Choice(last, "last loaded in player")
        }
        val queue = if (localOnly) episodeDao.getInOrderQueueLocal(feed.id) else episodeDao.getInOrderQueue(feed.id)
        return queue.firstOrNull()?.let { Choice(it, "oldest unplayed on phone") }
    }
}
