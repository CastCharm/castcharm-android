package com.castcharm.android.data.api.models

// FeedOut is the Moshi-deserialized response from the feeds list endpoint.
// Denormalized count fields (episode_count, unplayed_count, downloaded_count) are
// pre-computed by the server so the Android app doesn't have to query for them.
//
// auto_download_new is nullable: null means "use the global default" and non-null
// overrides it for this feed specifically.
// primary_feed_id links alternate feeds (e.g., a bonus feed) to their primary feed.
// active=false hides the feed from the default view but preserves its data.
data class FeedOut(
    val id: Int,
    val url: String,
    val title: String?,
    val description: String?,
    val image_url: String?,
    val custom_image_url: String?,
    val website_url: String?,
    val author: String?,
    val language: String?,
    val category: String?,
    val podcast_group: String?,
    val primary_feed_id: Int?,
    val auto_download_new: Boolean?,
    val active: Boolean,
    val last_checked: String?,
    val last_error: String?,
    val episode_count: Int = 0,
    val unplayed_count: Int = 0,
    val downloaded_count: Int = 0,
    val created_at: String,
    val updated_at: String,
    // 'newest' (ordinary podcast) or 'oldest' (listen in order — a story/serial).
    val play_order: String? = null,
    // What "Continue" would play; only present for play_order == "oldest".
    val next_up: NextUpOut? = null,
)

data class NextUpOut(
    val episode_id: Int,
    val seq_number: Int? = null,
    val title: String? = null,
    val position_seconds: Int = 0,
    val resume: Boolean = false,
)

// Body for PUT api/feeds/{id}. Moshi omits nulls, so only the fields set here
// reach the server (which applies exclude_unset semantics).
data class FeedUpdateRequest(
    val play_order: String? = null,
)
