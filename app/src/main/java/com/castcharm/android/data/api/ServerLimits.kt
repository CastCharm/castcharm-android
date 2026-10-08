package com.castcharm.android.data.api

import android.util.Log
import com.castcharm.android.CastCharmApp
import retrofit2.HttpException

/**
 * The request ceilings the connected server enforces, as reported by /api/limits.
 *
 * These used to be constants in the app. That works until a server is upgraded
 * and the numbers diverge: the app keeps sizing requests to what the server used
 * to accept and they start coming back 422, for a reason that appears nowhere in
 * the client. Reading them from the server means there is one definition
 * (app/limits.py) and the app follows it — including when an admin's server is
 * newer, older, or configured differently from whatever this build assumed.
 *
 * The defaults below are only a fallback, for a server too old to publish limits
 * or unreachable at the moment of asking. They are deliberately the conservative
 * end of what any recent server allows, so acting on them is always safe; a
 * server that permits more will say so and the app will use it.
 */
data class ServerLimits(
    val maxPageSize: Int = 1_000,
    val maxIdsInUrl: Int = 200,
    val maxBulkIds: Int = 200,
    val maxIndexIds: Int = 50_000,
    val maxSearchLen: Int = 200,
    val maxRequestBytes: Long = 10L * 1024 * 1024,
) {
    /**
     * Ids per request when addressing episodes by id.
     *
     * Takes the smaller of what the server accepts and what SQLite will bind —
     * the same lists become IN () clauses locally, and older Android builds cap
     * that at 999 variables. A server limit is not permission to exceed a local one.
     */
    val idsPerRequest: Int get() = minOf(maxIdsInUrl, SQLITE_BIND_CEILING)

    val bulkIdsPerRequest: Int get() = minOf(maxBulkIds, SQLITE_BIND_CEILING)

    /**
     * Largest page to ask a list endpoint for.
     *
     * One below the server's ceiling, because the has-more probe in
     * EpisodeRepository asks for one more than it wants: requesting exactly the
     * maximum would send maximum+1 and be rejected outright rather than answered
     * with a full page.
     */
    val safePageSize: Int get() = (maxPageSize - 1).coerceAtLeast(1)

    /**
     * [desired] rows, or as many as this server will actually serve.
     *
     * For callers whose page size is a product decision — "show up to a thousand
     * queued downloads" — rather than a mirror of a server constant. They say what
     * they want and get a shorter list against a stricter server, instead of a 422
     * they never anticipated.
     */
    fun pageSize(desired: Int): Int = desired.coerceIn(1, safePageSize)

    /**
     * Replaces any nonsensical value with the built-in default.
     *
     * Moshi fills in absent fields from the defaults above, but it cannot defend
     * against a server — or something in front of one — that answers with a zero.
     * Taken at face value that would make safePageSize 1 and turn the episode list
     * into one request per episode, so a bad answer is worse here than no answer
     * at all. Every field is independently checked; a server can be wrong about
     * one without discarding the rest.
     */
    private fun sanitised(): ServerLimits {
        val fallback = ServerLimits()
        return ServerLimits(
            // At least 2: safePageSize subtracts one and must stay positive.
            maxPageSize = maxPageSize.takeIf { it >= 2 } ?: fallback.maxPageSize,
            maxIdsInUrl = maxIdsInUrl.takeIf { it >= 1 } ?: fallback.maxIdsInUrl,
            maxBulkIds = maxBulkIds.takeIf { it >= 1 } ?: fallback.maxBulkIds,
            maxIndexIds = maxIndexIds.takeIf { it >= 1 } ?: fallback.maxIndexIds,
            maxSearchLen = maxSearchLen.takeIf { it >= 1 } ?: fallback.maxSearchLen,
            maxRequestBytes = maxRequestBytes.takeIf { it >= 1 } ?: fallback.maxRequestBytes,
        )
    }

    companion object {
        // Below SQLite's 999-variable limit on older Android builds, with room to
        // spare for the rest of a statement.
        private const val SQLITE_BIND_CEILING = 400

        // How long a good answer is trusted. Limits change only when a server is
        // upgraded, so this is about eventually noticing, not about being current.
        private const val TTL_MS = 30L * 60 * 1000

        // How long to wait after a failed attempt. Short, because a failure is
        // usually transient — a request that landed before login finished, or a
        // moment of no connectivity — and leaving the app on cautious defaults for
        // half an hour because of one 401 is its own small bug.
        private const val RETRY_MS = 60L * 1000

        @Volatile
        private var cached: ServerLimits = ServerLimits()

        @Volatile
        private var nextAttemptAtMs: Long = 0L

        /** The limits to size requests by. Never null, never blocks. */
        val current: ServerLimits get() = cached

        /**
         * Re-reads the server's limits if the cached copy has gone stale.
         *
         * Safe to call from anywhere on any code path — it is one small request at
         * most twice an hour, and it never throws. A server without /api/limits
         * (or an unreachable one) simply leaves the conservative defaults in place,
         * which is why nothing has to know whether this succeeded.
         */
        suspend fun ensureFresh(api: CastCharmApi) {
            if (CastCharmApp.isOfflineMode) return
            val now = System.currentTimeMillis()
            if (now < nextAttemptAtMs) return

            // The short floor goes on before the request, so a server that fails
            // this endpoint every time is retried on a schedule rather than on
            // every call that happens to consult a limit. Success extends it to
            // the full TTL.
            nextAttemptAtMs = now + RETRY_MS
            try {
                val out = api.getLimits()
                cached = ServerLimits(
                    maxPageSize = out.max_page_size,
                    maxIdsInUrl = out.max_ids_in_url,
                    maxBulkIds = out.max_bulk_ids,
                    maxIndexIds = out.max_index_ids,
                    maxSearchLen = out.max_search_len,
                    maxRequestBytes = out.max_request_bytes,
                ).sanitised()
                nextAttemptAtMs = now + TTL_MS
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    // This server predates the endpoint. That will not change until
                    // it is upgraded and reconnected — which calls reset() — so back
                    // off fully rather than asking again every minute for the life
                    // of the session.
                    Log.i("ServerLimits", "Server has no /api/limits; using defaults")
                    nextAttemptAtMs = now + TTL_MS
                } else {
                    Log.i("ServerLimits", "Could not read server limits (HTTP ${e.code()}); using defaults")
                }
            } catch (e: Exception) {
                Log.i("ServerLimits", "Could not read server limits; using defaults", e)
            }
        }

        /** Drops the cache so the next [ensureFresh] re-reads — used when the server changes. */
        fun reset() {
            cached = ServerLimits()
            nextAttemptAtMs = 0L
        }
    }
}
