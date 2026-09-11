package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class ThumbnailManager(context: Context) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val diskCacheDir = File(context.cacheDir, CACHE_DIRECTORY)
    private val executor = ThreadPoolExecutor(
        MAX_CONCURRENT_EXTRACTIONS,
        MAX_CONCURRENT_EXTRACTIONS,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(MAX_QUEUED_EXTRACTIONS),
    )
    private val memoryCache = object : LruCache<String, Bitmap>(MAX_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val lock = Any()
    private val inFlight = mutableMapOf<String, InFlight>()
    private val nextCallbackId = AtomicLong()

    init {
        diskCacheDir.mkdirs()
    }

    fun load(
        source: String,
        size: Long,
        modifiedTime: Long,
        callback: (Bitmap?) -> Unit,
    ): Request {
        val key = cacheKey(source, size, modifiedTime)
        memoryCache.get(key)?.let {
            callback(it)
            return Request {}
        }

        val callbackId = nextCallbackId.incrementAndGet()
        synchronized(lock) {
            val existing = inFlight[key]
            if (existing != null) {
                existing.callbacks[callbackId] = callback
                return requestFor(key, callbackId)
            }

            val request = InFlight(mutableMapOf(callbackId to callback))
            inFlight[key] = request
            try {
                executor.purge()
                request.future = executor.submit {
                    val bitmap = loadOrGenerate(key, source)
                    if (bitmap != null)
                        memoryCache.put(key, bitmap)
                    val callbacks = synchronized(lock) {
                        if (inFlight[key] === request) {
                            inFlight.remove(key)
                            request.callbacks.values.toList()
                        } else {
                            emptyList()
                        }
                    }
                    if (callbacks.isNotEmpty()) {
                        mainHandler.post {
                            callbacks.forEach { it(bitmap) }
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                inFlight.remove(key)
                mainHandler.post { callback(null) }
            }
        }
        return requestFor(key, callbackId)
    }

    private fun requestFor(key: String, callbackId: Long) = Request {
        synchronized(lock) {
            val request = inFlight[key] ?: return@synchronized
            request.callbacks.remove(callbackId)
            if (request.callbacks.isEmpty()) {
                request.future?.let {
                    it.cancel(true)
                    (it as? Runnable)?.let(executor::remove)
                }
                inFlight.remove(key)
            }
        }
    }

    private fun loadOrGenerate(key: String, source: String): Bitmap? {
        if (Thread.currentThread().isInterrupted)
            return null

        val cacheFile = cacheFile(key)
        decodeCacheFile(cacheFile)?.let {
            cacheFile.setLastModified(System.currentTimeMillis())
            return it
        }

        val startedAt = System.currentTimeMillis()
        return try {
            val bitmap = FFmpegThumbnailExtractor.extract(
                source = source,
                maxWidth = THUMBNAIL_MAX_WIDTH,
                maxHeight = THUMBNAIL_MAX_HEIGHT,
                timeoutMs = EXTRACTION_TIMEOUT_MS,
            ) ?: return null
            if (Thread.currentThread().isInterrupted)
                return null
            try {
                writeCacheFile(cacheFile, bitmap)
                trimDiskCache()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cache thumbnail", e)
            }
            Log.d(TAG, "Generated thumbnail in ${System.currentTimeMillis() - startedAt} ms")
            bitmap
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: LinkageError) {
            Log.e(TAG, "Native thumbnail extractor is unavailable", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail extraction failed", e)
            null
        }
    }

    private fun decodeCacheFile(file: File): Bitmap? {
        if (!file.isFile)
            return null
        val bitmap = BitmapFactory.decodeFile(file.path)
        if (bitmap == null)
            file.delete()
        return bitmap
    }

    private fun writeCacheFile(file: File, bitmap: Bitmap) {
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) {
                    "Android failed to encode the thumbnail"
                }
                it.fd.sync()
            }
            if (!temporary.renameTo(file))
                throw IllegalStateException("Failed to publish thumbnail cache file")
        } finally {
            temporary.delete()
        }
    }

    private fun trimDiskCache() {
        val files = diskCacheDir.walkTopDown()
            .filter { it.isFile && it.extension == CACHE_EXTENSION }
            .toList()
        var totalSize = files.sumOf { it.length() }
        if (totalSize <= MAX_DISK_BYTES)
            return
        for (file in files.sortedBy { it.lastModified() }) {
            val size = file.length()
            if (file.delete())
                totalSize -= size
            if (totalSize <= MAX_DISK_BYTES)
                break
        }
    }

    private fun cacheFile(key: String): File =
        File(File(diskCacheDir, key.substring(0, 2)), "$key.$CACHE_EXTENSION")

    override fun close() {
        synchronized(lock) {
            inFlight.values.forEach { request ->
                request.future?.let {
                    it.cancel(true)
                    (it as? Runnable)?.let(executor::remove)
                }
            }
            inFlight.clear()
        }
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private class InFlight(
        val callbacks: MutableMap<Long, (Bitmap?) -> Unit>,
        var future: java.util.concurrent.Future<*>? = null,
    )

    class Request internal constructor(private val cancelAction: () -> Unit) {
        fun cancel() = cancelAction()
    }

    companion object {
        private const val TAG = "mpv"
        private const val CACHE_DIRECTORY = "dufs-thumbnails"
        private const val CACHE_EXTENSION = "jpg"
        private const val CACHE_VERSION = 1
        private const val JPEG_QUALITY = 85
        private const val THUMBNAIL_MAX_WIDTH = 480
        private const val THUMBNAIL_MAX_HEIGHT = 270
        private const val EXTRACTION_TIMEOUT_MS = 20_000L
        private const val MAX_CONCURRENT_EXTRACTIONS = 2
        private const val MAX_QUEUED_EXTRACTIONS = 64
        private const val MAX_MEMORY_BYTES = 16 * 1024 * 1024
        private const val MAX_DISK_BYTES = 256L * 1024 * 1024

        private fun cacheKey(source: String, size: Long, modifiedTime: Long): String {
            val value = "$CACHE_VERSION\u0000$source\u0000$size\u0000$modifiedTime" +
                "\u0000$THUMBNAIL_MAX_WIDTH\u0000$THUMBNAIL_MAX_HEIGHT"
            return MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
