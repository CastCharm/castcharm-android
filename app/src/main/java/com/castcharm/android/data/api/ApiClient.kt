package com.castcharm.android.data.api

// ApiClient holds the Retrofit/OkHttp instances and is re-initialized whenever the
// user changes the server URL (e.g., after logout + login to a different server).
// It owns:
//   - PersistentCookieJar: persists session cookies to disk across app restarts
//   - SessionStateInterceptor: centralized auth/connectivity event reporting
//   - The Retrofit interface (CastCharmApi)
//
// A re-initialization guard (baseUrl == normalizedBaseUrl && api != null) means that
// calling initialize() with the same URL that is already configured is a no-op, so
// it is safe to call on every screen navigation without rebuilding the HTTP stack.

import android.content.Context
import com.castcharm.android.BuildConfig
import com.castcharm.android.CastCharmApp
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class ApiClient(private val context: Context) {
    private var baseUrl: String = ""
    private var retrofit: Retrofit? = null
    private var api: CastCharmApi? = null
    private var cookieJar: PersistentCookieJar? = null
    private var httpClient: OkHttpClient? = null

    val isInitialized: Boolean
        get() = api != null

    fun initialize(serverUrl: String) {
        // Ensure the base URL always ends with "/" as required by Retrofit.
        val normalizedBaseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        // Short-circuit if already initialized with the same URL.
        if (normalizedBaseUrl == baseUrl && api != null && httpClient != null && cookieJar != null) {
            return
        }

        baseUrl = normalizedBaseUrl
        // Reaching here means the server actually changed — the same-URL case
        // returned above. Limits belong to the server that reported them, so the
        // cached copy is discarded and re-read against the new one rather than
        // sizing requests to a machine we are no longer talking to.
        ServerLimits.reset()
        cookieJar = PersistentCookieJar.getInstance(context)

        // SessionStateInterceptor is added first so it sees both request and response
        // before any other interceptor logs. ApiKeyInterceptor attaches the stored
        // API key, and is a no-op until this device has enrolled one.
        //
        // HttpLoggingInterceptor at HEADERS level echoes the X-API-Key header, so
        // it is only wired in for debug builds. A release APK never emits the key
        // to Logcat.
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar!!)
            .addInterceptor(SessionStateInterceptor())
            .addInterceptor(ApiKeyInterceptor(context))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                setLevel(HttpLoggingInterceptor.Level.HEADERS)
            })
        }
        httpClient = builder.build()

        // KotlinJsonAdapterFactory enables Moshi to serialize/deserialize Kotlin data
        // classes with default parameter values and nullability correctly.
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(httpClient!!)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        api = retrofit!!.create(CastCharmApi::class.java)
    }

    fun getApi(): CastCharmApi {
        return api ?: throw IllegalStateException("ApiClient not initialized. Call initialize() first.")
    }

    fun getBaseUrl(): String = baseUrl

    fun getHttpClient(): OkHttpClient? = httpClient

    fun clearCookies() {
        cookieJar?.clear()
    }
}

// Every HTTP response or failure passes through this interceptor, which delegates
// to AppSessionManager to update connectivity/auth state:
//   - successful response  → reset transient failure counter (server is reachable)
//   - 401                  → report auth invalid (session expired or key revoked)
//   - connectivity failure → contribute to the 3-in-8s unreachable streak
//
// Cancellations are excluded from the unreachable streak because they are caused
// by the app itself (coroutine cancellation, navigation away) and do not indicate
// that the server is down.
private class SessionStateInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return try {
            val response = chain.proceed(chain.request())

            // Server replied — reset failure window regardless of HTTP status code.
            CastCharmApp.reportServerReachable()

            // Session was rejected server-side. Report before returning the response
            // so AppSessionManager can immediately transition to NotLoggedIn.
            //
            // 401 ONLY — deliberately not 403. The server returns 401 for every
            // authentication failure (see AuthMiddleware) and reserves 403 for policy
            // refusals that have nothing to do with the session: an episode whose file
            // sits outside the managed download directory, a disabled external API, a
            // directory listing that is off-limits. Treating those as an expired
            // session logged the user out at random and forced a manual re-login.
            if (response.code == 401) {
                CastCharmApp.reportAuthInvalid()
            }

            response
        } catch (t: Throwable) {
            // Only count genuine connectivity failures, not coroutine cancellations.
            if (isConnectivityFailure(t) && !isCancellationFailure(t)) {
                CastCharmApp.reportServerUnreachable()
            }
            throw t
        }
    }

    // InterruptedIOException that is NOT a SocketTimeoutException indicates that
    // OkHttp cancelled an in-flight request (e.g., the coroutine was cancelled).
    // Message text checks handle additional cancellation signal paths from OkHttp.
    private fun isCancellationFailure(t: Throwable): Boolean {
        if (t is InterruptedIOException && t !is SocketTimeoutException) {
            return true
        }

        val message = t.message.orEmpty()
        return message.contains("canceled", ignoreCase = true) ||
                message.contains("cancelled", ignoreCase = true)
    }

    // Classify the exception type to distinguish connectivity failures (server
    // unreachable / network down) from protocol/application-level errors.
    // ProtocolException is excluded because it means the server replied with
    // malformed HTTP, not that it was unreachable.
    private fun isConnectivityFailure(t: Throwable): Boolean {
        return when (t) {
            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException,
            is SocketTimeoutException,
            is SocketException,
            is SSLException,
            is InterruptedIOException,
            is EOFException -> true

            is ProtocolException -> false

            is IOException -> true

            else -> false
        }
    }
}

// Cookies are persisted to "cookies.json" in the app's internal files directory
// so session tokens survive app restarts. Expired cookies are filtered out both
// on load (so stale tokens are never sent) and on request (so tokens that expire
// while the app is running are lazily evicted and the file is updated).
//
// Consumers call PersistentCookieJar.getInstance(context) to obtain the
// process-wide singleton. The singleton pattern removes the need for an
// in-memory shim in PlayerService and eliminates the risk of two jars fighting
// over the same on-disk file. Every read/write is synchronized on `lock` and
// every persist goes through a write-to-temp + atomic rename so a save can't
// produce a half-written or truncated cookies.json.
class PersistentCookieJar private constructor(context: Context) : CookieJar {
    // Use applicationContext so this singleton is safe to hold long-term
    // without pinning any Activity or Service.
    private val context: Context = context.applicationContext
    private val cookieFile = File(context.filesDir, "cookies.json")
    private val tmpFile = File(context.filesDir, "cookies.json.tmp")
    // In-memory map of hostname → cookie list for fast per-request lookup.
    private val cookies = mutableMapOf<String, MutableList<Cookie>>()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, SerializedCookie::class.java)
    private val adapter = moshi.adapter<List<SerializedCookie>>(listType)
    // Guards the in-memory map and every disk operation.
    private val lock = Any()

    init {
        // Load persisted cookies from disk immediately so the first request after
        // an app restart includes the session cookie without needing a new login.
        loadCookies()
    }

    // Called by OkHttp after a response with Set-Cookie headers. Replaces all
    // cookies for the host and immediately persists to disk.
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        synchronized(lock) {
            this.cookies[host] = cookies.toMutableList()
            saveCookies()
        }
    }

    // Called by OkHttp before each request. Returns only non-expired cookies.
    // If any cookies have expired since the last load, the in-memory map and
    // the on-disk file are both updated to evict them.
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        return synchronized(lock) {
            val hostCookies = cookies[host] ?: return@synchronized emptyList()
            val now = System.currentTimeMillis()
            val validCookies = hostCookies.filter { it.expiresAt > now }
            if (validCookies.size != hostCookies.size) {
                cookies[host] = validCookies.toMutableList()
                saveCookies()
            }
            validCookies
        }
    }

    // Serializes the full cookie map to JSON and overwrites the file via
    // temp-file + rename so a reader (or a concurrent writer) never observes
    // a partially written cookies.json. Caller must hold `lock`.
    //
    // Exception is caught and logged rather than propagated — a failed write
    // means the next app launch will need a fresh login, which is acceptable.
    private fun saveCookies() {
        try {
            val allCookies = mutableListOf<SerializedCookie>()
            for ((host, hostCookies) in cookies) {
                for (cookie in hostCookies) {
                    allCookies.add(SerializedCookie.from(cookie, host))
                }
            }
            val json = adapter.toJson(allCookies)
            tmpFile.writeText(json)
            // renameTo is atomic on POSIX filesystems, which is what Android
            // uses for internal storage. If the rename fails (unlikely), fall
            // back to a plain overwrite so we still persist something.
            if (!tmpFile.renameTo(cookieFile)) {
                cookieFile.writeText(json)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Reads and deserializes the cookie file on startup. Expired cookies are
    // skipped immediately so they don't enter the in-memory map at all.
    private fun loadCookies() {
        synchronized(lock) {
            try {
                if (cookieFile.exists()) {
                    val json = cookieFile.readText()
                    val loaded = adapter.fromJson(json) ?: return
                    val now = System.currentTimeMillis()
                    for (sc in loaded) {
                        if (sc.expiresAt <= now) continue
                        val cookie = sc.toCookie() ?: continue
                        cookies.getOrPut(sc.host) { mutableListOf() }.add(cookie)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Clears all in-memory cookies and deletes the on-disk file. Called during
    // logout so the next app launch starts with no session.
    fun clear() {
        synchronized(lock) {
            cookies.clear()
            cookieFile.delete()
            tmpFile.delete()
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: PersistentCookieJar? = null

        // Process-wide accessor. ContentProviders create their httpClient
        // before Application.onCreate runs, so this must be safe to call
        // without any other app-level init having completed — it only reads
        // context.filesDir, which is available immediately.
        fun getInstance(context: Context): PersistentCookieJar {
            val existing = INSTANCE
            if (existing != null) return existing
            synchronized(this) {
                val doubleChecked = INSTANCE
                if (doubleChecked != null) return doubleChecked
                val created = PersistentCookieJar(context)
                INSTANCE = created
                return created
            }
        }
    }
}

// Plain-data DTO used for JSON serialization of OkHttp Cookie objects.
// OkHttp's Cookie class is not directly serializable, so we map to/from this
// simpler structure. The host field is stored alongside the cookie data because
// OkHttp's Cookie.Builder requires it at reconstruction time.
data class SerializedCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val host: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean
) {
    fun toCookie(): Cookie? {
        return try {
            Cookie.Builder()
                .name(name)
                .value(value)
                .expiresAt(expiresAt)
                .domain(host)
                .path(path)
                .apply {
                    if (secure) secure()
                    if (httpOnly) httpOnly()
                }
                .build()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun from(cookie: Cookie, host: String) = SerializedCookie(
            name = cookie.name,
            value = cookie.value,
            expiresAt = cookie.expiresAt,
            host = host,
            path = cookie.path,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly
        )
    }
}