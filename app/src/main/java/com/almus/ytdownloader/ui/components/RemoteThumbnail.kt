package com.almus.ytdownloader.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small picture from the web (a video thumbnail). No image library: one https request, kept in memory. */
@Composable
fun RemoteThumbnail(url: String?, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = ThumbnailCache.get(url), url) {
        if (url != null && value == null) value = withContext(Dispatchers.IO) { ThumbnailCache.load(url) }
    }
    val shape = RoundedCornerShape(8.dp)
    val image = bitmap
    if (image != null) {
        Image(bitmap = image, contentDescription = null, modifier = modifier.clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

internal object ThumbnailCache {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val TARGET_WIDTH = 480
    private val cache = LruCache<String, ImageBitmap>(40)

    fun get(url: String?): ImageBitmap? = if (url == null) null else cache.get(url)

    /** Only https pictures are fetched. Returns null on any problem; a missing thumbnail is never an error. */
    fun load(url: String): ImageBitmap? {
        if (!url.startsWith("https://", ignoreCase = true)) return null
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (out.size() < MAX_BYTES) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            connection.disconnect()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val image = decoded.asImageBitmap()
            cache.put(url, image)
            image
        } catch (_: Exception) {
            null
        }
    }
}
