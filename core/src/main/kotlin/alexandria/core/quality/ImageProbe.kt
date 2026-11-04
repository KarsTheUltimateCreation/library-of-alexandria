package alexandria.core.quality

import alexandria.core.net.HttpError
import alexandria.core.net.exchange
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

data class ImageInfo(val width: Int, val height: Int, val format: String, val bytes: Long?) {
    val pixels: Long get() = width.toLong() * height
    val bytesPerPixel: Double? get() = bytes?.takeIf { it > 0 && pixels > 0 }?.let { it.toDouble() / pixels }
}

/**
 * Throws on HTTP/network failure (the image is unreachable); returns null when the bytes are not a
 * recognised image format.
 *
 * Reads image dimensions from the first bytes of a file (JPEG SOF, PNG IHDR, WebP VP8/VP8L/VP8X, GIF),
 * so quality can be compared without downloading whole images. Uses an HTTP Range request and stops
 * reading after [maxBytes] even if the server ignores the range.
 */
object ImageProbe {

    const val DEFAULT_MAX_BYTES = 64 * 1024

    suspend fun probe(client: OkHttpClient, url: String, headers: Headers = Headers.headersOf(), maxBytes: Int = DEFAULT_MAX_BYTES): ImageInfo? {
        val request = Request.Builder().url(url).headers(headers).header("Range", "bytes=0-${maxBytes - 1}").build()
        // Read inside the call so a cancelled or timed-out probe also stops a body that is still downloading.
        return client.exchange(request) { r ->
            if (!r.isSuccessful) throw HttpError(r.code, url)
            val total = r.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                ?: if (r.code == 200) r.body.contentLength().takeIf { it > 0 } else null
            val source = r.body.source()
            val buffer = ByteArray(maxBytes)
            var read = 0
            while (read < maxBytes) {
                val n = source.read(buffer, read, maxBytes - read)
                if (n <= 0) break
                read += n
            }
            parse(buffer, read)?.copy(bytes = total)
        }
    }

    fun parse(data: ByteArray, length: Int = data.size): ImageInfo? {
        if (length < 12) return null
        fun u8(i: Int) = data[i].toInt() and 0xFF
        fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
        fun le24(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)

        // PNG
        if (u8(0) == 0x89 && u8(1) == 0x50 && u8(2) == 0x4E && u8(3) == 0x47 && length >= 24) {
            return ImageInfo(be32(16), be32(20), "png", null)
        }
        // GIF
        if (u8(0) == 'G'.code && u8(1) == 'I'.code && u8(2) == 'F'.code) {
            return ImageInfo(le16(6), le16(8), "gif", null)
        }
        // WebP
        if (u8(0) == 'R'.code && u8(1) == 'I'.code && u8(2) == 'F'.code && u8(3) == 'F'.code &&
            u8(8) == 'W'.code && u8(9) == 'E'.code && u8(10) == 'B'.code && u8(11) == 'P'.code && length >= 30
        ) {
            val chunk = String(data, 12, 4, Charsets.US_ASCII)
            return when (chunk) {
                "VP8 " -> ImageInfo(le16(26) and 0x3FFF, le16(28) and 0x3FFF, "webp", null)
                "VP8L" -> {
                    val b0 = u8(21); val b1 = u8(22); val b2 = u8(23); val b3 = u8(24)
                    val w = 1 + (((b1 and 0x3F) shl 8) or b0)
                    val h = 1 + (((b3 and 0xF) shl 10) or (b2 shl 2) or ((b1 and 0xC0) shr 6))
                    ImageInfo(w, h, "webp", null)
                }
                "VP8X" -> ImageInfo(1 + le24(24), 1 + le24(27), "webp", null)
                else -> null
            }
        }
        // JPEG: walk segments until a Start Of Frame marker.
        if (u8(0) == 0xFF && u8(1) == 0xD8) {
            var i = 2
            while (i + 9 < length) {
                if (u8(i) != 0xFF) { i++; continue }
                val marker = u8(i + 1)
                if (marker == 0xFF) { i++; continue }
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
                val segLen = be16(i + 2)
                val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (isSof) return ImageInfo(be16(i + 7), be16(i + 5), "jpeg", null)
                if (segLen < 2) return null
                i += 2 + segLen
            }
            return null
        }
        // AVIF/HEIF: look for the 'ispe' box (image spatial extents).
        if (length >= 16 && String(data, 4, 4, Charsets.US_ASCII) == "ftyp") {
            val needle = "ispe".toByteArray(Charsets.US_ASCII)
            for (i in 8 until length - 16) {
                if (data[i] == needle[0] && data[i + 1] == needle[1] && data[i + 2] == needle[2] && data[i + 3] == needle[3]) {
                    return ImageInfo(be32(i + 8), be32(i + 12), "avif", null)
                }
            }
        }
        return null
    }
}
