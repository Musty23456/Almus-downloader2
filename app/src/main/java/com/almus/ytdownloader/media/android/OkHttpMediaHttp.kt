package com.almus.ytdownloader.media.android

import com.almus.ytdownloader.media.MediaHttp
import com.almus.ytdownloader.media.MediaNetworkException
import com.almus.ytdownloader.media.MediaReply
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The only network code of the catalog services. It talks to two public metadata services over https and nothing
 * else: archive.org/metadata/... and commons.wikimedia.org/w/api.php. No redirects are followed, nothing is sent
 * except a descriptive User-Agent and Accept, no credentials, and answers are size-limited.
 */
class OkHttpMediaHttp(
    private val client: OkHttpClient = defaultClient(),
) : MediaHttp {

    override suspend fun get(url: String): MediaReply = withContext(Dispatchers.IO) {
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || !parsed.isHttps || !isOfficialService(parsed.host, parsed.encodedPath)) {
            throw MediaNetworkException("This address is not an official catalog service.")
        }
        val request = Request.Builder().url(parsed).get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val bytes = response.peekBody(MAX_BODY_BYTES + 1L).bytes()
                if (bytes.size > MAX_BODY_BYTES) throw MediaNetworkException("The answer is too large.")
                MediaReply(response.code, String(bytes, Charsets.UTF_8))
            }
        } catch (e: IOException) {
            throw MediaNetworkException("Could not reach the service.", e)
        }
    }

    companion object {
        private const val USER_AGENT = "AlmusDownloader/0.14 (media catalog; official public APIs only)"
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024

        fun isOfficialService(host: String, path: String): Boolean =
            (host == "archive.org" && path.startsWith("/metadata/") && path.length > "/metadata/".length) ||
                (host == "commons.wikimedia.org" && path == "/w/api.php")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
