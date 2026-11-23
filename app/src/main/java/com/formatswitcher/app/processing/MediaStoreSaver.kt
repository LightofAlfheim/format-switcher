package com.formatswitcher.app.processing

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.formatswitcher.app.MediaKind
import com.formatswitcher.app.UserFacingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saves finished files as NEW items in your Gallery / Music (Pictures, Movies or Music →
 * "Format Switcher"). Uses Android's MediaStore, so no storage permission is needed and the
 * original file is never touched.
 */
object MediaStoreSaver {

    suspend fun save(
        context: Context,
        file: File,
        displayName: String,
        mimeType: String,
        kind: MediaKind,
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = when (kind) {
            MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaKind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, kind.folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw UserFacingException("Couldn't create the output file. Is your storage full?")
        try {
            resolver.openOutputStream(uri)?.use { outStream ->
                file.inputStream().use { it.copyTo(outStream, 1 shl 16) }
            } ?: throw UserFacingException("Couldn't write the output file.")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            if (e is UserFacingException) throw e
            throw UserFacingException("Couldn't save the file. Is your storage full?")
        }
    }
}
