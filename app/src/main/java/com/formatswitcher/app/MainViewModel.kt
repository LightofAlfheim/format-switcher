package com.formatswitcher.app

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.transformer.ExportException
import com.formatswitcher.app.processing.AudioConverter
import com.formatswitcher.app.processing.ImageConverter
import com.formatswitcher.app.processing.MediaStoreSaver
import com.formatswitcher.app.processing.VideoConverter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

sealed interface Status {
    data object Idle : Status
    data class Working(val progress: Float, val label: String) : Status
    data class Done(
        val uri: Uri,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val originalBytes: Long,
        val folder: String,
        val note: String?,
    ) : Status
    data class Failed(val message: String) : Status
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()

    var selected by mutableStateOf<SelectedFile?>(null)
        private set
    var mode by mutableStateOf(Mode.CONVERT)
    var chosenFormat by mutableStateOf<OutputFormat?>(null)
    var chosenPreset by mutableStateOf<SizePreset?>(null)
    var status by mutableStateOf<Status>(Status.Idle)
        private set

    private var job: Job? = null
    private var stageLabel = ""
    private var lastReported = -1f

    init {
        // Clean up anything left behind if the app was closed mid-job.
        File(ctx.cacheDir, "work").deleteRecursively()
    }

    val isWorking: Boolean get() = status is Status.Working

    fun select(uri: Uri) {
        if (isWorking) return
        viewModelScope.launch {
            status = Status.Idle
            try {
                val file = withContext(Dispatchers.IO) { queryFile(uri) }
                selected = file
                chosenFormat = OutputFormat.forKind(file.kind).firstOrNull { !file.isAlready(it) }
                chosenPreset = SizePreset.entries.firstOrNull { !isAlreadyUnder(file, it) }
            } catch (e: UserFacingException) {
                status = Status.Failed(e.message ?: "Couldn't open that file.")
            } catch (e: Exception) {
                status = Status.Failed("Couldn't open that file. Try picking it again.")
            }
        }
    }

    fun isAlreadyUnder(file: SelectedFile, preset: SizePreset): Boolean =
        file.sizeBytes in 1..preset.bytes

    fun clearFile() {
        if (isWorking) return
        selected = null
        status = Status.Idle
    }

    fun startOver() {
        status = Status.Idle
    }

    fun cancel() {
        job?.cancel()
    }

    fun start() {
        val file = selected ?: return
        if (isWorking) return
        val currentMode = mode
        val format = chosenFormat
        val preset = chosenPreset
        if (currentMode == Mode.CONVERT && format == null) return
        if (currentMode == Mode.COMPRESS && preset == null) return

        job = viewModelScope.launch {
            val workDir = File(ctx.cacheDir, "work").apply {
                deleteRecursively()
                mkdirs()
            }
            setStage("Getting ready…")
            try {
                status = if (currentMode == Mode.CONVERT) {
                    convert(file, format!!, workDir)
                } else {
                    compress(file, preset!!, workDir)
                }
            } catch (e: CancellationException) {
                status = Status.Idle
                throw e
            } catch (e: Throwable) {
                status = Status.Failed(friendlyMessage(e))
            } finally {
                workDir.deleteRecursively()
            }
        }
    }

    // --- Work -------------------------------------------------------------------------------

    private suspend fun convert(file: SelectedFile, format: OutputFormat, dir: File): Status.Done {
        val out = File(dir, "output.${format.extension}")
        val progress = ::reportProgress
        when (format) {
            OutputFormat.JPG, OutputFormat.PNG, OutputFormat.WEBP -> {
                setStage("Converting image…")
                ImageConverter(ctx).convert(file.uri, format, out)
            }
            OutputFormat.MP4_H264, OutputFormat.MP4_HEVC -> {
                setStage("Converting video… (long videos take a while)")
                VideoConverter(ctx).convert(file.uri, out, hevc = format == OutputFormat.MP4_HEVC, onProgress = progress)
            }
            OutputFormat.M4A, OutputFormat.VIDEO_TO_M4A -> {
                setStage("Converting audio…")
                AudioConverter(ctx).convert(file.uri, out, AudioConverter.Output.AAC, 192_000, false, progress)
            }
            OutputFormat.WAV, OutputFormat.VIDEO_TO_WAV -> {
                setStage("Converting audio…")
                AudioConverter(ctx).convert(file.uri, out, AudioConverter.Output.WAV, 0, false, progress)
            }
        }
        val name = "${file.baseName}.${format.extension}"
        return save(out, name, format.mimeType, format.outputKind, file.sizeBytes, null)
    }

    private suspend fun compress(file: SelectedFile, preset: SizePreset, dir: File): Status.Done {
        val target = (preset.bytes * 0.97).toLong() // small safety margin
        val tag = "_${preset.megabytes}MB"
        return when (file.kind) {
            MediaKind.IMAGE -> {
                val format = if (file.isAlready(OutputFormat.WEBP)) OutputFormat.WEBP else OutputFormat.JPG
                val out = File(dir, "output.${format.extension}")
                setStage("Compressing image…")
                ImageConverter(ctx).compressToSize(file.uri, format, target, out, ::reportProgress)
                save(out, "${file.baseName}$tag.${format.extension}", format.mimeType, MediaKind.IMAGE, file.sizeBytes, overNote(out, preset))
            }
            MediaKind.VIDEO -> {
                val out = File(dir, "output.mp4")
                VideoConverter(ctx).compressToSize(file.uri, out, target, ::setStage, ::reportProgress)
                save(out, "${file.baseName}$tag.mp4", "video/mp4", MediaKind.VIDEO, file.sizeBytes, overNote(out, preset))
            }
            MediaKind.AUDIO -> {
                val out = File(dir, "output.m4a")
                val seconds = ((file.durationMs ?: 0L) / 1000.0).coerceAtLeast(1.0)
                var bitrate = (target * 8 * 0.95 / seconds).toInt().coerceIn(MIN_AUDIO_BPS, 256_000)
                val converter = AudioConverter(ctx)
                for (pass in 1..2) {
                    setStage(if (pass == 1) "Compressing audio…" else "Fine-tuning the size…")
                    converter.convert(file.uri, out, AudioConverter.Output.AAC, bitrate, bitrate < 64_000, ::reportProgress)
                    if (out.length() <= target || bitrate == MIN_AUDIO_BPS) break
                    bitrate = (bitrate * (target.toDouble() / out.length()) * 0.9).toInt().coerceAtLeast(MIN_AUDIO_BPS)
                }
                save(out, "${file.baseName}$tag.m4a", "audio/mp4", MediaKind.AUDIO, file.sizeBytes, overNote(out, preset))
            }
        }
    }

    private fun overNote(out: File, preset: SizePreset): String? =
        if (out.length() > preset.bytes) {
            "This file is very long, so it couldn't get all the way under ${preset.label} without " +
                "becoming unwatchable. This is the smallest version that still looks/sounds okay."
        } else {
            null
        }

    private suspend fun save(
        out: File,
        name: String,
        mime: String,
        kind: MediaKind,
        originalBytes: Long,
        note: String?,
    ): Status.Done {
        if (!out.exists() || out.length() == 0L) throw UserFacingException("Something went wrong – the result was empty.")
        setStage("Saving…")
        val uri = MediaStoreSaver.save(ctx, out, name, mime, kind)
        return Status.Done(uri, name, mime, out.length(), originalBytes, kind.folder, note)
    }

    private fun setStage(label: String) {
        stageLabel = label
        lastReported = -1f
        status = Status.Working(0f, label)
    }

    private fun reportProgress(p: Float) {
        if (p - lastReported < 0.01f && p < 1f) return
        lastReported = p
        status = Status.Working(p.coerceIn(0f, 1f), stageLabel)
    }

    // --- Helpers ----------------------------------------------------------------------------

    private fun queryFile(uri: Uri): SelectedFile {
        var name = "file"
        var size = -1L
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }

        var mime = ctx.contentResolver.getType(uri)
        if (mime == null || mime == "application/octet-stream") {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }
        val m = mime?.lowercase(Locale.US) ?: ""
        val kind = when {
            m.startsWith("image/") -> MediaKind.IMAGE
            m.startsWith("video/") -> MediaKind.VIDEO
            m.startsWith("audio/") -> MediaKind.AUDIO
            else -> throw UserFacingException("That file type isn't supported. Pick a photo, video or audio file.")
        }

        var duration: Long? = null
        if (kind != MediaKind.IMAGE) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (_: Exception) {
            } finally {
                try {
                    r.release()
                } catch (_: Exception) {
                }
            }
        }
        return SelectedFile(uri, name, size, m, kind, duration)
    }

    private fun friendlyMessage(e: Throwable): String = when (e) {
        is UserFacingException -> e.message ?: "Something went wrong."
        is ExportException ->
            "Couldn't process this video (${e.errorCodeName}). It may use a format your phone can't handle."
        is OutOfMemoryError -> "This file is too large for the phone to process."
        is SecurityException -> "The app lost permission to read that file. Please pick it again."
        else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        private const val MIN_AUDIO_BPS = 32_000
    }
}
