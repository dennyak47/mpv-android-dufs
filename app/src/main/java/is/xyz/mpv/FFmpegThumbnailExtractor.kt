package `is`.xyz.mpv

import android.graphics.Bitmap

internal object FFmpegThumbnailExtractor {
    fun extract(
        source: String,
        timestampMs: Long = DEFAULT_TIMESTAMP,
        maxWidth: Int,
        maxHeight: Int,
        timeoutMs: Long,
    ): Bitmap? {
        require(maxWidth > 0 && maxHeight > 0)
        return MPVLib.extractThumbnail(source, timestampMs, maxWidth, maxHeight, timeoutMs)
    }

    private const val DEFAULT_TIMESTAMP = -1L
}
