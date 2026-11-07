package com.almus.ytdownloader.engine

import java.io.InputStream

class FakeResponse(
    override val code: Int,
    private val headers: Map<String, String> = emptyMap(),
    private val bytes: ByteArray = ByteArray(0),
) : TransportResponse {
    @Volatile var closed = false

    override fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    override val body: InputStream get() = bytes.inputStream()
    override fun cancel() {}
    override fun close() {
        closed = true
    }
}

class FakeTransport(private val script: (String) -> FakeResponse) : HttpTransport {
    val requested = mutableListOf<String>()
    val responses = mutableListOf<FakeResponse>()
    val requestHeaders = mutableListOf<Map<String, String>>()

    override suspend fun get(request: TransportRequest): TransportResponse {
        requested += request.url
        requestHeaders += request.headers
        return script(request.url).also { responses += it }
    }
}
