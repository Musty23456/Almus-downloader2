package com.almus.ytdownloader.engine

import java.io.Closeable
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.StateFlow

/** Where downloads are remembered. Room in the app, memory in tests. */
interface EngineStore {
    /** Saves a new download and returns its id (the id in the given object is ignored). */
    suspend fun insert(download: EngineDownload): Long
    suspend fun update(download: EngineDownload)
    suspend fun all(): List<EngineDownload>
    suspend fun delete(id: Long)
}

data class PublishedFile(val uri: String)

/** Where unfinished and finished files live. */
interface FinalStorage {
    /** Folder for partial files. Private to the app. */
    fun partDir(): File

    /** Free bytes where partial files are written, or -1 when unknown. */
    fun freeBytes(): Long

    /** Moves a complete file to its final public place and returns where. Removes [part] on success. */
    suspend fun publish(part: File, fileName: String, mimeType: String?, subfolder: String?): PublishedFile

    suspend fun deletePublished(uri: String): Boolean
}

/**
 * [kind] and [downstreamKbps] (0 = unknown) are hints for choosing how many connections to use; the two
 * original fields alone keep working.
 */
data class NetworkStatus(
    val online: Boolean,
    val metered: Boolean,
    val kind: NetworkKind = NetworkKind.Unknown,
    val downstreamKbps: Int = 0,
)

interface NetworkMonitor {
    val status: StateFlow<NetworkStatus>
}

/** Settings the engine reads each time it decides something. */
interface EngineSettings {
    /** How many downloads may run at the same time. */
    val maxConcurrent: Int

    /** When true, no transfer runs on a metered (mobile data) connection. */
    val wifiOnly: Boolean

    /** Master switch for segmented (multi-connection) downloads. Off means every download uses one connection. */
    val segmentedDownloads: Boolean get() = true

    /** The user's limit on connections per download; 0 lets the engine decide from the network. */
    val maxConnectionsPerDownload: Int get() = 0
}

/** One HTTP GET. Redirects are NOT followed by the transport; the engine checks every hop itself. */
class TransportRequest private constructor(val url: String, val headers: Map<String, String>, val ownConnection: Boolean) {
    class Builder(private val url: String) {
        private val headers = LinkedHashMap<String, String>()
        private var ownConnection = false

        /**
         * Asks for a connection that is not shared with other requests (no HTTP/2 multiplexing), so that several
         * ranged requests really travel over several TCP connections. Only a hint: a transport may always ignore it.
         */
        fun ownConnection(value: Boolean = true): Builder {
            ownConnection = value
            return this
        }

        /** Only plain headers are ever added (see the source guard test): no cookies, no login data. */
        fun addRequestHeader(name: String, value: String): Builder {
            headers[name] = value
            return this
        }

        fun build(): TransportRequest = TransportRequest(url, LinkedHashMap(headers), ownConnection)
    }
}

interface TransportResponse : Closeable {
    val code: Int
    fun header(name: String): String?
    val body: InputStream

    /** Aborts the transfer from any thread, which also wakes a blocked read. */
    fun cancel()
}

interface HttpTransport {
    suspend fun get(request: TransportRequest): TransportResponse
}

/** Thrown when the engine refuses to continue for a reason that retrying cannot fix (unsafe redirect and similar). */
class DownloadRefused(message: String) : Exception(message)
