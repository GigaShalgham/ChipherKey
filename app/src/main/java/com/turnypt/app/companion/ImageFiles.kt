package com.turnypt.app.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import com.turnypt.core.crypto.Ckf1

/**
 * v0.6.0: pure helpers around the CKF1 file name/mime conventions and
 * image preview decoding — kept free of framework state so they are
 * trivially testable.
 */
object ImageFiles {

    /** "photo.jpg" -> "photo.jpg.cki" (path stripped, collisions left to SAF). */
    fun suggestedEncryptedName(displayName: String?): String {
        val base = displayName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "image"
        return if (base.endsWith(".${Ckf1.FILE_EXTENSION}", ignoreCase = true)) base
        else "$base.${Ckf1.FILE_EXTENSION}"
    }

    /** "photo.jpg.cki" -> "photo.jpg"; anything else -> the name as-is. */
    fun suggestedOriginalName(displayName: String?): String {
        val base = displayName?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return "file"
        return if (base.endsWith(".${Ckf1.FILE_EXTENSION}", ignoreCase = true)) {
            base.dropLast(Ckf1.FILE_EXTENSION.length + 1).ifBlank { "file" }
        } else {
            base
        }
    }

    /** True for the image MIME family (drives the preview vs. save-only UI). */
    fun isImageMime(mime: String?): Boolean = mime != null && mime.startsWith("image/", ignoreCase = true)

    /** Human-readable size ("1.4 MB", "2.1 GB") for progress/result lines. */
    fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    /**
     * Decodes a downsampled preview bitmap from [file] (never larger than
     * [maxDimension] px on the long side) so a 50 MP photo never lands in
     * the heap at full size. Returns null when the file is not decodable
     * as an image.
     */
    fun decodePreview(file: File, maxDimension: Int = 1280): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= maxDimension ||
            bounds.outHeight / (sample * 2) >= maxDimension
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
    }

    /** Sniffs the mime type of a decoded-plaintext file ("image/jpeg" …). */
    fun sniffMime(file: File): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outMimeType
    }

    /**
     * The display name a content:// uri advertises (SAF providers expose
     * OpenableColumns); null when the provider does not answer.
     */
    fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
    }.getOrNull()

    /** The byte size a content:// uri advertises, when known. */
    fun querySize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                } else {
                    null
                }
            }
    }.getOrNull()
}

/**
 * The one chokepoint through which ViewModels open content streams (SAF
 * pickers, sharesheet sends). Injectable so flows stay testable and the
 * ContentResolver never leaks into state logic.
 */
@Singleton
class ContentResolverStreams @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun read(uri: Uri): InputStream? = runCatching {
        context.contentResolver.openInputStream(uri)
    }.getOrNull()

    fun write(uri: Uri): OutputStream? = runCatching {
        context.contentResolver.openOutputStream(uri, "w")
    }.getOrNull()
}
