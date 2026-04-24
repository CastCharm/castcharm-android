package com.castcharm.android.data.api

import android.content.Context
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
        val normalizedBaseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        if (normalizedBaseUrl == baseUrl && api != null && httpClient != null && cookieJar != null) {
            return
        }

        baseUrl = normalizedBaseUrl
        cookieJar = PersistentCookieJar(context)

        val logging = HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.HEADERS)
        }

        httpClient = OkHttpClient.Builder()
            .cookieJar(cookieJar!!)
            .addInterceptor(SessionStateInterceptor())
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

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

/**
 * Centralized network/session reporting:
 *
 * - successful responses reset transient failure tracking
 * - connectivity-style failures contribute to an unreachable streak
 * - explicit 401 / 403 emit "auth invalid"
 */
private class SessionStateInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return try {
            val response = chain.proceed(chain.request())

            CastCharmApp.reportServerReachable()

            if (response.code == 401 || response.code == 403) {
                CastCharmApp.reportAuthInvalid()
            }

            response
        } catch (t: Throwable) {
            if (isConnectivityFailure(t) && !isCancellationFailure(t)) {
                CastCharmApp.reportServerUnreachable()
            }
            throw t
        }
    }

    private fun isCancellationFailure(t: Throwable): Boolean {
        if (t is InterruptedIOException && t !is SocketTimeoutException) {
            return true
        }

        val message = t.message.orEmpty()
        return message.contains("canceled", ignoreCase = true) ||
                message.contains("cancelled", ignoreCase = true)
    }

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

/**
 * We persist cookies to disk as JSON so sessions survive app restarts.
 */
class PersistentCookieJar(private val context: Context) : CookieJar {
    private val cookieFile = File(context.filesDir, "cookies.json")
    private val cookies = mutableMapOf<String, MutableList<Cookie>>()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, SerializedCookie::class.java)
    private val adapter = moshi.adapter<List<SerializedCookie>>(listType)

    init {
        loadCookies()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        this.cookies[host] = cookies.toMutableList()
        saveCookies()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val hostCookies = cookies[host] ?: return emptyList()

        val now = System.currentTimeMillis()
        val validCookies = hostCookies.filter { it.expiresAt > now }

        if (validCookies.size != hostCookies.size) {
            cookies[host] = validCookies.toMutableList()
            saveCookies()
        }

        return validCookies
    }

    private fun saveCookies() {
        try {
            val allCookies = mutableListOf<SerializedCookie>()
            for ((host, hostCookies) in cookies) {
                for (cookie in hostCookies) {
                    allCookies.add(SerializedCookie.from(cookie, host))
                }
            }
            cookieFile.writeText(adapter.toJson(allCookies))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadCookies() {
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

    fun clear() {
        cookies.clear()
        cookieFile.delete()
    }
}

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