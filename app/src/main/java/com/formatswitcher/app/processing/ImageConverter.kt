package com.formatswitcher.app.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import com.formatswitcher.app.OutputFormat
import com.formatswitcher.app.UserFacingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

/**
 * Converts and compresses images fully on-device.
 * Re-encoding drops EXIF metadata, so GPS location and camera details are NOT copied
 * into the new file (a privacy bonus). Rotation is applied to the pixels first, so
 * photos still appear the right way up.
 */
class ImageConverter(private val context: Context) {

    suspend fun convert(uri: Uri, format: OutputFormat, out: File) = withContext(Dispatchers.Default) {
        val bitmap = prepare(decode(uri), format)
        ensureActive()
        FileOutputStream(out).use { stream ->
            if (!bitmap.compress(compressFormat(format), defaultQuality(format), stream)) {
                throw UserFacingException("Couldn't save the image in ${format.title} format.")
            }
        }
    }

    /** Finds the best quality that fits under [targetBytes]; shrinks the picture only if it must. */
    suspend fun compressToSize(
        uri: Uri,
        format: OutputFormat,
        targetBytes: Long,
        out: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Default) {
        var bitmap = prepare(decode(uri), format)
        var round = 0
        while (true) {
            ensureActive()
            onProgress((round / 10f).coerceAtMost(0.95f))

            // Quick win: high quality already fits.
            val high = encode(bitmap, format, 92)
            if (high.size <= targetBytes) {
                out.writeBytes(high)
                return@withContext
            }

            // Binary search for the highest quality that fits.
            var lo = 25
            var hi = 91
            var best: ByteArray? = null
            while (lo <= hi) {
                ensureActive()
                val mid = (lo + hi) / 2
                val bytes = encode(bitmap, format, mid)
                if (bytes.size <= targetBytes) {
                    best = bytes
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            if (best != null) {
                out.writeBytes(best)
                return@withContext
            }

            round++
            if (round > 12 || bitmap.width < 64 || bitmap.height < 64) {
                out.writeBytes(encode(bitmap, format, 25))
                return@withContext
            }
            // Still too big even at low quality: shrink dimensions by 20% and try again.
            val w = (bitmap.width * 0.8f).toInt().coerceAtLeast(1)
            val h = (bitmap.height * 0.8f).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
    }

    private fun decode(uri: Uri): Bitmap {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val pixels = w.toLong() * h
                if (pixels > MAX_PIXELS) {
                    val scale = sqrt(MAX_PIXELS.toDouble() / pixels)
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                }
            }
        } catch (e: OutOfMemoryError) {
            throw UserFacingException("That image is too large for this phone to process.")
        } catch (e: Exception) {
            throw UserFacingException("Couldn't open this image. It may be damaged or in an unsupported format.")
        }
    }

    /** JPG can't store transparency, so transparent areas get a white background instead of black. */
    private fun prepare(src: Bitmap, format: OutputFormat): Bitmap {
        if (format != OutputFormat.JPG || !src.hasAlpha()) return src
        val flat = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)
        src.recycle()
        return flat
    }

    private fun encode(bitmap: Bitmap, format: OutputFormat, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bitmap.compress(compressFormat(format), quality, bos)
        return bos.toByteArray()
    }

    private fun compressFormat(format: OutputFormat): Bitmap.CompressFormat = when (format) {
        OutputFormat.PNG -> Bitmap.CompressFormat.PNG
        OutputFormat.WEBP -> Bitmap.CompressFormat.WEBP_LOSSY
        else -> Bitmap.CompressFormat.JPEG
    }

    private fun defaultQuality(format: OutputFormat): Int = when (format) {
        OutputFormat.PNG -> 100
        OutputFormat.WEBP -> 90
        else -> 92
    }

    companion object {
        /** 50 megapixels – full resolution for Galaxy S25 photos while staying memory-safe. */
        private const val MAX_PIXELS = 50_000_000L
    }
}
