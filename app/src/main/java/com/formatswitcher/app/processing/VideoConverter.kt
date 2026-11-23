package com.formatswitcher.app.processing

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.formatswitcher.app.UserFacingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * Video conversion and compression using Android's hardware encoders via AndroidX Media3 Transformer.
 * Output is MP4 (H.264 or H.265) with AAC audio.
 */
@OptIn(UnstableApi::class)
class VideoConverter(private val context: Context) {

    data class VideoInfo(val durationMs: Long, val width: Int, val height: Int, val audioBitrate: Int)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Plain format conversion, keeping the original resolution. */
    suspend fun convert(uri: Uri, out: File, hevc: Boolean, onProgress: (Float) -> Unit) {
        transcode(uri, out, if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264, null, null, onProgress)
    }

    /**
     * Squeezes the video under [targetBytes]: works out the bitrate that fits the length,
     * lowers the resolution when that bitrate is low, then re-checks and does up to two
     * more passes if the result is still over.
     */
    suspend fun compressToSize(
        uri: Uri,
        out: File,
        targetBytes: Long,
        onStage: (String) -> Unit,
        onProgress: (Float) -> Unit,
    ) {
        val info = withContext(Dispatchers.IO) { probe(uri) }
        if (info.durationMs <= 0) throw UserFacingException("Couldn't read how long this video is.")
        val seconds = info.durationMs / 1000.0
        var videoBps = ((targetBytes * 8 * 0.93) / seconds - info.audioBitrate).toInt()
        val shortSide = min(info.width, info.height)

        for (attempt in 1..3) {
            videoBps = videoBps.coerceAtLeast(MIN_VIDEO_BPS)
            val targetShort = targetShortSide(videoBps)
            val scale = if (shortSide > targetShort && shortSide > 0) targetShort.toFloat() / shortSide else null
            onStage(if (attempt == 1) "Compressing video…" else "Fine-tuning the size (pass $attempt of 3)…")
            onProgress(0f)
            transcode(uri, out, MimeTypes.VIDEO_H264, videoBps, scale, onProgress)
            val size = out.length()
            if (size <= targetBytes || videoBps == MIN_VIDEO_BPS) return
            videoBps = (videoBps * (targetBytes.toDouble() / size) * 0.9).toInt()
        }
    }

    private fun targetShortSide(videoBps: Int): Int = when {
        videoBps >= 2_000_000 -> 1080
        videoBps >= 1_000_000 -> 720
        videoBps >= 500_000 -> 480
        else -> 360
    }

    fun probe(uri: Uri): VideoInfo {
        var duration = 0L
        var width = 0
        var height = 0
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        var audioBitrate = 0
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioBitrate = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        f.getInteger(MediaFormat.KEY_BIT_RATE)
                    } else {
                        DEFAULT_AUDIO_BPS
                    }
                    break
                }
            }
        } catch (_: Exception) {
            audioBitrate = DEFAULT_AUDIO_BPS
        } finally {
            extractor.release()
        }
        return VideoInfo(duration, width, height, audioBitrate)
    }

    private suspend fun transcode(
        uri: Uri,
        out: File,
        videoMime: String,
        videoBitrate: Int?,
        scale: Float?,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        out.delete()

        val effects = if (scale != null && scale < 0.999f) {
            Effects(emptyList(), listOf(ScaleAndRotateTransformation.Builder().setScale(scale, scale).build()))
        } else {
            Effects.EMPTY
        }
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()

        // H.264 can't carry HDR, so S25 HDR videos are tone-mapped to normal (SDR) colours.
        val hdrMode = if (videoMime == MimeTypes.VIDEO_H264) {
            Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
        } else {
            Composition.HDR_MODE_KEEP_HDR
        }
        val composition = Composition.Builder(EditedMediaItemSequence(edited)).setHdrMode(hdrMode).build()

        val encoderBuilder = DefaultEncoderFactory.Builder(context).setEnableFallback(true)
        if (videoBitrate != null) {
            encoderBuilder.setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(videoBitrate).build()
            )
        }

        coroutineScope {
            suspendCancellableCoroutine<Unit> { cont ->
                var poller: Job? = null
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(videoMime)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderBuilder.build())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            poller?.cancel()
                            onProgress(1f)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            poller?.cancel()
                            out.delete()
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()

                cont.invokeOnCancellation {
                    mainHandler.post {
                        transformer.cancel()
                        out.delete()
                    }
                }

                transformer.start(composition, out.absolutePath)

                poller = launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress / 100f)
                        }
                        delay(300)
                    }
                }
            }
        }
    }

    companion object {
        private const val MIN_VIDEO_BPS = 150_000
        private const val DEFAULT_AUDIO_BPS = 256_000
    }
}
