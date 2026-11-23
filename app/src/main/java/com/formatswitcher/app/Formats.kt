package com.formatswitcher.app

import android.net.Uri
import java.util.Locale

enum class MediaKind(val emoji: String, val label: String, val folder: String) {
    IMAGE("🖼️", "Image", "Pictures/Format Switcher"),
    VIDEO("🎬", "Video", "Movies/Format Switcher"),
    AUDIO("🎵", "Audio", "Music/Format Switcher"),
}

enum class Mode { CONVERT, COMPRESS }

enum class OutputFormat(
    val title: String,
    val subtitle: String,
    val extension: String,
    val mimeType: String,
    val inputKind: MediaKind,
    val outputKind: MediaKind,
) {
    JPG("JPG", "Small photos that open everywhere", "jpg", "image/jpeg", MediaKind.IMAGE, MediaKind.IMAGE),
    PNG("PNG", "Lossless, keeps transparency (bigger files)", "png", "image/png", MediaKind.IMAGE, MediaKind.IMAGE),
    WEBP("WEBP", "Modern and small, great for websites", "webp", "image/webp", MediaKind.IMAGE, MediaKind.IMAGE),

    MP4_H264("MP4 (H.264)", "Plays on every phone, PC and website", "mp4", "video/mp4", MediaKind.VIDEO, MediaKind.VIDEO),
    MP4_HEVC("MP4 (H.265 / HEVC)", "Same quality, roughly half the size", "mp4", "video/mp4", MediaKind.VIDEO, MediaKind.VIDEO),
    VIDEO_TO_M4A("M4A – audio only", "Pull the sound out of the video", "m4a", "audio/mp4", MediaKind.VIDEO, MediaKind.AUDIO),
    VIDEO_TO_WAV("WAV – audio only", "Uncompressed sound from the video", "wav", "audio/x-wav", MediaKind.VIDEO, MediaKind.AUDIO),

    M4A("M4A (AAC)", "Small, high quality, plays everywhere", "m4a", "audio/mp4", MediaKind.AUDIO, MediaKind.AUDIO),
    WAV("WAV", "Uncompressed – best for editing (big files)", "wav", "audio/x-wav", MediaKind.AUDIO, MediaKind.AUDIO);

    companion object {
        fun forKind(kind: MediaKind): List<OutputFormat> = entries.filter { it.inputKind == kind }
    }
}

enum class SizePreset(val label: String, val megabytes: Int, val hint: String) {
    MB8("8 MB", 8, "Smallest – fits strict upload limits"),
    MB10("10 MB", 10, "Good for most chat apps"),
    MB20("20 MB", 20, "Good for email attachments");

    /** Uses 1 MB = 1,000,000 bytes (the stricter reading), so results fit either way. */
    val bytes: Long get() = megabytes * 1_000_000L
}

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val kind: MediaKind,
    val durationMs: Long?,
) {
    val baseName: String
        get() = name.substringBeforeLast('.', name).ifBlank { "file" }
}

/** True when converting to [format] would just give back the same format. */
fun SelectedFile.isAlready(format: OutputFormat): Boolean {
    val m = mimeType.lowercase(Locale.US)
    return when (format) {
        OutputFormat.JPG -> m == "image/jpeg" || m == "image/jpg"
        OutputFormat.PNG -> m == "image/png"
        OutputFormat.WEBP -> m == "image/webp"
        OutputFormat.M4A -> m in setOf("audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac", "audio/mp4a-latm")
        OutputFormat.WAV -> m in setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
        else -> false
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "Unknown size"
    bytes < 1_000_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    bytes < 1_000_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/** An error with a message that is safe and friendly to show directly to the user. */
class UserFacingException(message: String) : Exception(message)
