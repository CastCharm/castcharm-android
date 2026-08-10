package com.castcharm.android.data.api

// AuthStore holds the server-issued API key that authenticates this device.
//
// The key replaces the session cookie as the app's primary credential. A cookie
// carries a fixed expiry stamped at login time which the server never refreshes,
// so it lapses on a schedule no matter how actively the app is used. An API key
// has no expiry at all — it stays valid until revoked, either from the server's
// web UI or by this device on logout.
//
// The value is cached in a @Volatile field because OkHttp interceptors run on
// background threads and must not suspend. keyBlocking() populates that cache on
// first use, which also covers the awkward Android case where a ContentProvider
// (PodcastArtworkProvider) is created *before* Application.onCreate() has run.
//
// Alongside the plaintext key we persist two identifying fields returned by the
// exchange-key endpoint:
//   - id     — the server-side row id, so the client can call PATCH to rename
//              itself without having to look up its own key in the full list.
//   - prefix — the leading "cc_XXXXXXXX" of the plaintext, useful for showing
//              the user which device this is in the "This device" panel.

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.castcharm.android.dataStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking

object AuthStore {
    private val apiKeyKey = stringPreferencesKey("api_key")
    private val apiKeyIdKey = intPreferencesKey("api_key_id")
    private val apiKeyPrefixKey = stringPreferencesKey("api_key_prefix")
    private val apiKeyNameKey = stringPreferencesKey("api_key_name")

    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cachedId: Int? = null

    @Volatile
    private var cachedPrefix: String? = null

    @Volatile
    private var cachedName: String? = null

    // Distinct from "cachedKey != null": once we have read DataStore we stop
    // re-reading it, whether or not a key was actually found.
    @Volatile
    private var loaded: Boolean = false

    val hasKey: Boolean
        get() = cachedKey != null

    val keyId: Int?
        get() = cachedId

    val keyPrefix: String?
        get() = cachedPrefix

    val keyName: String?
        get() = cachedName

    /** Suspending load, called once from Application.onCreate(). */
    suspend fun load(context: Context) {
        if (loaded) return
        val prefs = context.dataStore.data.firstOrNull()
        cachedKey = prefs?.get(apiKeyKey)
        cachedId = prefs?.get(apiKeyIdKey)
        cachedPrefix = prefs?.get(apiKeyPrefixKey)
        cachedName = prefs?.get(apiKeyNameKey)
        loaded = true
    }

    /**
     * Blocking read for OkHttp interceptors. After the first call this is just a
     * volatile field read; the runBlocking only happens once per process, and
     * only when the cache has not been warmed by load() yet.
     */
    fun keyBlocking(context: Context): String? {
        if (loaded) return cachedKey
        return synchronized(this) {
            if (!loaded) runBlocking { load(context) }
            cachedKey
        }
    }

    /**
     * Store a freshly-issued key along with its identifying metadata. id and
     * prefix are always present on enrolment; name is what the server chose to
     * label us (usually derived from the device model).
     */
    suspend fun save(context: Context, key: String, id: Int, prefix: String, name: String) {
        context.dataStore.edit { prefs ->
            prefs[apiKeyKey] = key
            prefs[apiKeyIdKey] = id
            prefs[apiKeyPrefixKey] = prefix
            prefs[apiKeyNameKey] = name
        }
        cachedKey = key
        cachedId = id
        cachedPrefix = prefix
        cachedName = name
        loaded = true
    }

    /**
     * Update the locally-cached name after a successful rename call, without
     * touching the key itself. Avoids a full re-enrolment for a label change.
     */
    suspend fun updateName(context: Context, name: String) {
        context.dataStore.edit { prefs -> prefs[apiKeyNameKey] = name }
        cachedName = name
    }

    suspend fun clear(context: Context) {
        context.dataStore.edit { prefs ->
            prefs.remove(apiKeyKey)
            prefs.remove(apiKeyIdKey)
            prefs.remove(apiKeyPrefixKey)
            prefs.remove(apiKeyNameKey)
        }
        cachedKey = null
        cachedId = null
        cachedPrefix = null
        cachedName = null
        loaded = true
    }
}

/**
 * Attaches the stored API key to every outgoing request.
 *
 * Falls through untouched when no key is stored, so a build that has not yet
 * enrolled — or is talking to a server too old to issue keys — keeps working on
 * cookie auth exactly as before.
 */
class ApiKeyInterceptor(private val context: Context) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val key = AuthStore.keyBlocking(context) ?: return chain.proceed(chain.request())
        return chain.proceed(
            chain.request().newBuilder()
                .header("X-API-Key", key)
                .build()
        )
    }
}
