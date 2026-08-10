package com.castcharm.android

// Central definition of DataStore keys added for feature toggles and small
// pieces of persistent state. Keeping them in one file — rather than scattered
// as top-level vals across screens — makes it easy to see the surface of
// preferences at a glance and to reason about migration.
//
// Anything on the auth surface (api_key, server_url, has_authenticated_before)
// lives in AuthStore / AppSessionManager instead, since those are used from
// worker threads before Application.onCreate() has run.

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

// Downloads: when true, DownloadScheduler enqueues work with NetworkType.UNMETERED
// so a phone on cellular waits for Wi-Fi before starting a download.
val WIFI_ONLY_DOWNLOADS_KEY = booleanPreferencesKey("wifi_only_downloads")

// Playback: when true, PlayerService installs a SilenceSkippingAudioProcessor
// so gaps in the audio are trimmed on the fly.
val SKIP_SILENCE_KEY = booleanPreferencesKey("skip_silence")

// Notifications: opt-in per user; SyncWorker posts a summary notification when
// new episodes are pulled from the server.
val NOTIFY_NEW_EPISODES_KEY = booleanPreferencesKey("notify_new_episodes")

// Bookkeeping for new-episode notifications — the highest episode id we've
// already surfaced to the user. Anything above this value is genuinely new
// and eligible for notification.
val LAST_NOTIFIED_EPISODE_ID_KEY = longPreferencesKey("last_notified_episode_id")

// Bandwidth tracking: cumulative bytes for the current month. Reset when the
// stored month string doesn't match today's YYYY-MM.
val BANDWIDTH_BYTES_KEY = longPreferencesKey("bandwidth_bytes_current_month")
val BANDWIDTH_MONTH_KEY = stringPreferencesKey("bandwidth_month")
