package com.almus.ytdownloader.engine

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/**
 * [HttpTransport] backed by OkHttp. Redirects are switched off here because [HttpSession] checks every hop.
 * Cancelling the coroutine, or calling [TransportResponse.cancel], aborts the socket so a blocked read wakes up.
 */
class OkHttpTransport(private val client: OkHttpClient = defaultClient()) : HttpTransport {
    /** Same client (same pool and dispatcher) but HTTP/1.1 only: every concurrent request then gets its own TCP connection. */
    private val http1Client: OkHttpClient by lazy { client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build() }

    override suspend fun get(request: TransportRequest): TransportResponse = suspendCancellableCoroutine { cont ->
        val builder = try {
            Request.Builder().url(request.url).get()
        } catch (_: IllegalArgumentException) {
            cont.resumeWithException(DownloadRefused("This is not a valid web address."))
            return@suspendCancellableCoroutine
        }
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val call = (if (request.ownConnection) http1Client else client).newCall(builder.build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(OkResponse(call, response)) else response.close()
            }
        })
    }

    private class OkResponse(private val call: Call, private val response: Response) : TransportResponse {
        override val code: Int get() = response.code
        override fun header(name: String): String? = response.header(name)
        override val body: InputStream get() = response.body?.byteStream() ?: ByteArray(0).inputStream()
        override fun cancel() = call.cancel()
        override fun close() = response.close()
    }

    companion object {
        /** OkHttp's default is 5 simultaneous calls per host, which would silently queue the segments of one download. */
        private fun newDispatcher() = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(newDispatcher())
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
