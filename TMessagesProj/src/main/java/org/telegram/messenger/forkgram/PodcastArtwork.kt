package org.telegram.messenger.forkgram

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object PodcastArtwork {
    private const val MAX_SOURCE_SIDE = 8192
    private const val MAX_SOURCE_PIXELS = 32_000_000L
    private const val MAX_DISPLAY_SIDE = 640
    private const val FAILURE_TTL_MS = 180_000L
    private val supportedFormats = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif")
    private val main = Handler(Looper.getMainLooper())
    private val pool = ThreadPoolExecutor(
        2, 2, 20, TimeUnit.SECONDS, ArrayBlockingQueue(22),
        { runnable -> Thread(runnable, "PodcastArtwork") }
    ).apply { allowCoreThreadTimeOut(true) }
    private val images = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val unavailable = LruCache<String, Long>(64)
    private val pending = HashMap<String, Pending>()

    private class Pending(val work: Runnable) {
        val callbacks = LinkedHashMap<Any, (Bitmap?) -> Unit>()
    }

    fun load(primaryUrl: String?, fallbackUrl: String? = null, onLoaded: (Bitmap?) -> Unit): () -> Unit {
        val cancelled = AtomicBoolean()
        val token = Any()
        var listening: String? = null

        fun resolve(url: String?, complete: (Bitmap?) -> Unit) {
            if (cancelled.get()) return
            listening = url
            if (url == null) {
                complete(null)
                return
            }
            enqueue(url, token) { bitmap ->
                listening = null
                if (!cancelled.get()) complete(bitmap)
            }
        }

        main.post {
            val primary = primaryUrl?.let { PodcastFeeds.url(it)?.toString() }
            val fallback = fallbackUrl?.let { PodcastFeeds.url(it)?.toString() }?.takeUnless { it == primary }
            resolve(primary) { bitmap ->
                if (bitmap != null || fallback == null) onLoaded(bitmap) else resolve(fallback, onLoaded)
            }
        }
        return {
            cancelled.set(true)
            main.post {
                val url = listening ?: return@post
                val request = pending[url] ?: return@post
                request.callbacks.remove(token)
                if (request.callbacks.isEmpty() && pool.remove(request.work)) pending.remove(url)
            }
        }
    }

    private fun enqueue(url: String, token: Any, onLoaded: (Bitmap?) -> Unit) {
        images.get(url)?.let {
            onLoaded(it)
            return
        }
        val failed = unavailable.get(url)
        if (failed != null && SystemClock.elapsedRealtime() - failed < FAILURE_TTL_MS) {
            onLoaded(null)
            return
        }
        pending[url]?.let {
            it.callbacks[token] = onLoaded
            return
        }
        val work = Runnable {
            val bitmap = runCatching { decode(PodcastFeeds.fetchArtwork(url)) }.getOrNull()
            main.post {
                val callbacks = pending.remove(url)?.callbacks ?: return@post
                if (bitmap != null) {
                    images.put(url, bitmap)
                    unavailable.remove(url)
                } else {
                    unavailable.put(url, SystemClock.elapsedRealtime())
                }
                callbacks.values.forEach { it(bitmap) }
            }
        }
        val request = Pending(work)
        request.callbacks[token] = onLoaded
        pending[url] = request
        try {
            pool.execute(work)
        } catch (_: RejectedExecutionException) {
            pending.remove(url)
            onLoaded(null)
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (bounds.outMimeType !in supportedFormats || width !in 1..MAX_SOURCE_SIDE ||
            height !in 1..MAX_SOURCE_SIDE || width.toLong() * height > MAX_SOURCE_PIXELS) return null

        var sample = 1
        while ((maxOf(width, height) + sample - 1) / sample > MAX_DISPLAY_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (maxOf(bitmap.width, bitmap.height) <= MAX_DISPLAY_SIDE) return bitmap
        bitmap.recycle()
        return null
    }
}
