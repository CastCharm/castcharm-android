package com.castcharm.android.data.repository

// Playlists on the phone. The server owns them; this keeps a copy in Room so
// every screen (and Android Auto) reads the same local state, online or off.
//
// Membership rule, used by every caller:
//   manual   → the cached member rows, in their saved order
//   smart    → online: the cached server members re-filtered live by the rules
//              (so a played episode leaves the list the moment Room updates);
//              offline (localOnly): the rules evaluated over the files on this
//              phone, so a list the server has never seen this phone's state
//              for still shows what is actually playable.

import android.util.Log
import com.castcharm.android.data.db.entities.DownloadOwnerEntity
import com.castcharm.android.download.DownloadOwners
import com.castcharm.android.CastCharmApp
import com.castcharm.android.data.api.CastCharmApi
import com.castcharm.android.data.api.models.AddToPlaylistRequest
import com.castcharm.android.data.api.models.CreatePlaylistRequest
import com.castcharm.android.data.api.models.PlaylistOut
import com.castcharm.android.data.api.models.ReorderRequest
import com.castcharm.android.data.api.models.SmartRules
import com.castcharm.android.data.api.models.UpdatePlaylistRequest
import com.castcharm.android.data.db.AppDatabase
import com.castcharm.android.data.db.entities.EpisodeEntity
import com.castcharm.android.data.db.entities.PlaylistEntity
import com.castcharm.android.download.DownloadScheduler
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class PlaylistRepository(private val db: AppDatabase) {
    private val dao = db.playlistDao()
    private val episodeDao = db.episodeDao()

    private fun apiOrNull(): CastCharmApi? =
        if (!CastCharmApp.isOfflineMode && CastCharmApp.apiClient.isInitialized) CastCharmApp.apiClient.getApi() else null

    fun rulesOf(pl: PlaylistEntity?): SmartRules? =
        pl?.rules_json?.let { json -> runCatching { rulesAdapter.fromJson(json) }.getOrNull() }

    // ---- reads ---------------------------------------------------------

    fun playlistsFlow(): Flow<List<PlaylistEntity>> = dao.getAll()
    fun playlistFlow(id: Int): Flow<PlaylistEntity?> = dao.get(id)
    fun systemPlaylistFlow(): Flow<PlaylistEntity?> = dao.getSystem()
    suspend fun systemPlaylistOnce(): PlaylistEntity? = dao.getSystemOnce()
    suspend fun playlistOnce(id: Int): PlaylistEntity? = dao.getOnce(id)

    /** Live members of a playlist. See the rule at the top of the file. */
    fun membersFlow(id: Int, localOnly: Boolean): Flow<List<EpisodeEntity>> =
        combine(dao.get(id), dao.memberEpisodes(id), episodeDao.getDownloadedEpisodes()) { pl, members, onPhone ->
            resolveMembers(pl, members, onPhone, localOnly)
        }

    suspend fun membersOnce(id: Int, localOnly: Boolean): List<EpisodeEntity> {
        val pl = dao.getOnce(id)
        val members = dao.memberEpisodesOnce(id)
        val onPhone = if (localOnly || pl?.isSmart == true) episodeDao.getDownloadedEpisodesOnce() else emptyList()
        return resolveMembers(pl, members, onPhone, localOnly)
    }

    private fun resolveMembers(pl: PlaylistEntity?, members: List<EpisodeEntity>, onPhone: List<EpisodeEntity>, localOnly: Boolean): List<EpisodeEntity> {
        if (pl == null) return emptyList()
        val rules = rulesOf(pl)
        return when {
            pl.isSmart && rules != null && localOnly -> rules.evaluate(onPhone, localOnly = true)
            pl.isSmart && rules != null -> rules.evaluate(members, localOnly = false, ignoreFeeds = true)
            localOnly -> members.filter { it.local_path != null }
            else -> members
        }
    }

    /** Members that are files on this phone (Downloads tab, offline play). */
    fun localMembersFlow(id: Int): Flow<List<EpisodeEntity>> = membersFlow(id, localOnly = true)
    suspend fun localMembersOnce(id: Int): List<EpisodeEntity> = membersOnce(id, localOnly = true)

    // ---- server sync -----------------------------------------------------

    /** Pull the playlist list and every member list. Safe to call often; a
     *  no-op offline. */
    suspend fun refreshAll(maxMembers: Int = 30): List<PlaylistEntity> {
        val api = apiOrNull() ?: return dao.getAllOnce()
        val remote = api.getPlaylists()
        val now = System.currentTimeMillis()
        val existing = dao.getAllOnce().associateBy { it.id }
        val entities = remote.mapIndexed { i, p -> p.toEntity(i, existing[p.id]?.updated_at ?: 0L) }
        dao.upsertAll(entities)
        dao.deleteNotIn(remote.map { it.id }.ifEmpty { listOf(-1) })
        remote.take(maxMembers).forEach { p ->
            runCatching { refreshMembers(p.id, api) }
                .onFailure { Log.w("PlaylistRepository", "members refresh failed for ${p.id}", it) }
        }
        return dao.getAllOnce().also { Log.d("PlaylistRepository", "refreshed ${it.size} playlists at $now") }
    }

    suspend fun refreshMembers(id: Int, api: CastCharmApi? = apiOrNull()): List<EpisodeEntity> {
        val a = api ?: return dao.memberEpisodesOnce(id)
        val remote = a.getPlaylistEpisodes(id)
        EpisodeRepository(a, episodeDao).mergeRemoteEpisodes(remote)
        dao.replaceMembers(id, remote.map { it.id })
        dao.getOnce(id)?.let { dao.upsertAll(listOf(it.copy(updated_at = System.currentTimeMillis()))) }
        return dao.memberEpisodesOnce(id)
    }

    suspend fun refreshOne(id: Int) {
        val api = apiOrNull() ?: return
        val remote = api.getPlaylists().find { it.id == id } ?: run { dao.delete(id); return }
        val existing = dao.getOnce(id)
        dao.upsertAll(listOf(remote.toEntity(existing?.sort_order ?: 0, existing?.updated_at ?: 0L)))
        refreshMembers(id, api)
    }

    // ---- writes (server first, then the local copy) -----------------------

    suspend fun create(name: String, description: String?, type: String, rules: SmartRules?): PlaylistOut {
        val api = CastCharmApp.apiClient.getApi()
        val created = api.createPlaylist(CreatePlaylistRequest(name = name, description = description, type = type, rules = rules))
        refreshAll()
        return created
    }

    suspend fun update(id: Int, name: String?, description: String?, rules: SmartRules?) {
        CastCharmApp.apiClient.getApi().updatePlaylist(id, UpdatePlaylistRequest(name = name, description = description, rules = rules))
        refreshOne(id)
    }

    suspend fun delete(id: Int) {
        CastCharmApp.apiClient.getApi().deletePlaylist(id)
        dao.delete(id)
    }

    suspend fun addEpisode(id: Int, episodeId: Int) {
        CastCharmApp.apiClient.getApi().addToPlaylist(id, AddToPlaylistRequest(episodeId))
        refreshMembers(id)
    }

    suspend fun removeEpisode(id: Int, episodeId: Int) {
        CastCharmApp.apiClient.getApi().removeFromPlaylist(id, episodeId)
        refreshMembers(id)
    }

    suspend fun reorder(id: Int, episodeIds: List<Int>) {
        CastCharmApp.apiClient.getApi().reorderPlaylist(id, ReorderRequest(episodeIds))
        dao.replaceMembers(id, episodeIds)
    }

    /** Save every member that the server has and this phone lacks. Returns
     *  how many downloads were queued. Honours Wi-Fi-only via the scheduler. */
    suspend fun downloadAll(id: Int, limit: Int = 100): Int {
        val pl = dao.getOnce(id) ?: return 0
        val owner = ownerFor(pl)
        val members = membersOnce(id, localOnly = false)
        val scheduler = DownloadScheduler(CastCharmApp.instance)
        var queued = 0
        for (ep in members) {
            if (ep.status != "downloaded" && ep.local_path == null) continue
            if (ep.local_path != null) {
                // Already here: just add this playlist's claim on it.
                db.downloadOwnerDao().add(DownloadOwnerEntity(ep.id, owner)); continue
            }
            if (queued >= limit) break
            runCatching { scheduler.scheduleDownload(ep.id, owner) }.onSuccess { queued++ }
        }
        return queued
    }

    /** The owner key a playlist's downloads are filed under. Get Caught Up,
     *  by hand or automatically, is one owner so cleanup can reclaim it. */
    fun ownerFor(pl: PlaylistEntity): String = if (pl.is_system) DownloadOwners.CATCHUP else DownloadOwners.playlist(pl.id)

    /**
     * For every downloaded file: owner key → wording. A file with two or more
     * entries is shared; each screen drops its own owner and shows the rest.
     */
    fun ownerNamesFlow(): Flow<Map<Int, Map<String, String>>> =
        combine(db.downloadOwnerDao().all(), dao.getAll(), db.feedDao().getAllFeeds(), episodeDao.getDownloadedEpisodes()) { owners, playlists, feeds, onPhone ->
            val plName = playlists.associate { it.id to it.name }
            val feedTitle = feeds.associate { it.id to it.title }
            val epFeed = onPhone.associate { it.id to it.feed_id }
            owners.filter { it.episode_id in epFeed }.groupBy { it.episode_id }.mapValues { (epId, rows) ->
                rows.associate { r -> r.owner to DownloadOwners.displayName(r.owner, feedTitle[epFeed[epId]]) { plName[it] } }
            }
        }

    /** Files on this phone filed under a given owner. */
    fun ownedEpisodesFlow(owner: String): Flow<List<EpisodeEntity>> =
        combine(db.downloadOwnerDao().all(), episodeDao.getDownloadedEpisodes()) { owners, onPhone ->
            val ids = owners.filter { it.owner == owner }.map { it.episode_id }.toSet()
            onPhone.filter { it.id in ids }
        }

    companion object {
        private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        private val rulesAdapter = moshi.adapter(SmartRules::class.java)

        fun rulesToJson(rules: SmartRules?): String? = rules?.let { rulesAdapter.toJson(it) }

        fun PlaylistOut.toEntity(sortOrder: Int, updatedAt: Long) = PlaylistEntity(
            id = id, name = name, description = description, type = type,
            rules_json = rulesToJson(rules), is_system = is_system,
            total_seconds = total_seconds, sort_order = sortOrder, updated_at = updatedAt,
        )
    }
}
