package com.almus.ytdownloader.engine

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * HARNESS ONLY (not shipped in the app). The sandbox this was built in cannot reach Maven Central, so OkHttp is
 * unavailable; this stand-in implements the same [HttpTransport] port with the JDK HTTP client so the real engine and
 * its tests can run. The app itself keeps using OkHttpTransport.
 */
class OkHttpTransport : HttpTransport {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15)).version(HttpClient.Version.HTTP_1_1).build()

    override suspend fun get(request: TransportRequest): TransportResponse = suspendCancellableCoroutine { cont ->
        val b = try { HttpRequest.newBuilder(URI.create(request.url)).GET().timeout(Duration.ofSeconds(60)) }
        catch (e: IllegalArgumentException) { cont.resumeWithException(DownloadRefused("This is not a valid web address.")); return@suspendCancellableCoroutine }
        request.headers.forEach { (n, v) -> b.header(n, v) }
        val fut = client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofInputStream())
        cont.invokeOnCancellation { fut.cancel(true) }
        fut.whenComplete { resp, err ->
            if (err != null) {
                val c = err.cause ?: err
                if (cont.isActive) cont.resumeWithException(if (c is IOException) c else IOException(c))
            } else if (cont.isActive) cont.resume(Resp(resp)) else resp.body().close()
        }
    }

    private class Resp(private val r: HttpResponse<InputStream>) : TransportResponse {
        override val code: Int get() = r.statusCode()
        override fun header(name: String): String? = r.headers().firstValue(name).orElse(null)
        override val body: InputStream get() = r.body()
        override fun cancel() { runCatching { r.body().close() } }
        override fun close() { runCatching { r.body().close() } }
    }
}
