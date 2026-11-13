package com.turnypt.app.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
 *
 * v1.2.0 (user report: "images are still saved with the .cki extension"):
 * encrypted IMAGES now keep the source's real image extension — the file
 * looks like (and is handled as) a normal image, and Turnypt — registered
 * as an opener for those image extensions — sniffs the CKF1 magic to offer
 * the decrypt. Which file is encrypted is decided by CONTENT (the magic
 * bytes), never by the name, so the camouflage never hides the file from
 * the app itself.
 */
object ImageFiles {

    /**
     * The image extensions Turnypt supports (and registers to open):
     * extension -> the MIME the system expects for it.
     */
    val IMAGE_EXTENSIONS: Map<String, String> = linkedMapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
    )

    /** True when [name] ends with one of the supported image extensions. */
    fun hasImageExtension(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return ext.isNotEmpty() && ext in IMAGE_EXTENSIONS
    }

    /** "photo.jpg" -> "image/jpeg"; null for unknown/absent extensions. */
    fun mimeForName(name: String?): String? {
        val base = name?.substringAfterLast('/') ?: return null
        val ext = base.substringAfterLast('.', "").lowercase(Locale.US)
        return IMAGE_EXTENSIONS[ext]
    }

    /** "image/jpeg" -> "jpg"; null for anything unsupported. */
    fun extensionForMime(mime: String?): String? = when (mime?.lowercase(Locale.US)) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        "image/heic", "image/heif" -> "heic"
        else -> null
    }

    /**
     * The suggested name for an ENCRYPTED image (v1.2.0):
     *
     *  - "photo.jpg" -> "photo.jpg" — the image keeps its real extension
     *    (the camouflage: it opens through Turnypt like any image);
     *  - a name with no extension but a known image MIME derives it:
     *    "IMG_001" + image/png -> "IMG_001.png";
     *  - anything that is not recognizable as an image keeps the legacy
     *    ".cki" suffix — those files still open through the .cki filters.
     */
    fun suggestedEncryptedName(displayName: String?, mime: String? = null): String {
        val base = displayName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return "image.${Ckf1.FILE_EXTENSION}"
        if (hasImageExtension(base)) return base
        if (base.endsWith(".${Ckf1.FILE_EXTENSION}", ignoreCase = true)) return base
        val derived = extensionForMime(mime)
        return if (derived != null) "$base.$derived" else "$base.${Ckf1.FILE_EXTENSION}"
    }

    /**
     * "photo.jpg.cki" -> "photo.jpg"; a camouflaged encrypted image
     * ("photo.jpg") already carries the right extension and passes through
     * unchanged; anything else -> the name as-is.
     */
    fun suggestedOriginalName(displayName: String?): String {
        val base = displayName?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return "file"
        return if (base.endsWith(".${Ckf1.FILE_EXTENSION}", ignoreCase = true)) {
            base.dropLast(Ckf1.FILE_EXTENSION.length + 1).ifBlank { "file" }
        } else {
            base
        }
    }

    /**
     * True for the image MIME family (drives the preview vs. save-only UI).
     */
    fun isImageMime(mime: String?): Boolean = mime != null && mime.startsWith("image/", ignoreCase = true)

    /**
     * v1.2.0: reads the first bytes of [uri] and reports whether the content
     * carries the CKF1 magic — the one honest way to tell an encrypted image
     * (which now looks like a normal image) from a plain one. Never throws;
     * unreadable content is simply "not CKF1".
     */
    fun sniffsCkf1(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(4)
            var filled = 0
            while (filled < 4) {
                val read = input.read(head, filled, 4 - filled)
                if (read == -1) break
                filled += read
            }
            filled == 4 && Ckf1.hasMagic(head)
        } ?: false
    }.getOrDefault(false)

    /** The MIME a content:// uri advertises (null when untyped/unknown). */
    fun queryMimeType(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.getType(uri)
    }.getOrNull()

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
 * pickers, sharesheet sends), resolve display names and allocate private
 * cache files. v1.2.0: an INTERFACE (its own doc promised "injectable so
 * flows stay testable" — the raw-Context uses that leaked into
 * ToolsViewModel are behind it now), so the whole ViewModel is
 * JVM-testable with an in-memory fake and no Android framework.
 */
interface ContentResolverStreams {

    fun read(uri: Uri): InputStream?

    fun write(uri: Uri): OutputStream?

    /** The display name a content:// uri advertises (null when unknown). */
    fun queryDisplayName(uri: Uri): String?

    /**
     * A fresh private cache file for a decrypted payload (the caller owns
     * wiping it; each call yields a new file).
     */
    fun newDecryptCacheFile(): File
}

/** The Android-backed [ContentResolverStreams] (SAF/ContentResolver). */
@Singleton
class AndroidContentResolverStreams @Inject constructor(
    @ApplicationContext private val context: Context,
) : ContentResolverStreams {

    override fun read(uri: Uri): InputStream? = runCatching {
        context.contentResolver.openInputStream(uri)
    }.getOrNull()

    override fun write(uri: Uri): OutputStream? = runCatching {
        context.contentResolver.openOutputStream(uri, "w")
    }.getOrNull()

    override fun queryDisplayName(uri: Uri): String? =
        ImageFiles.queryDisplayName(context, uri)

    override fun newDecryptCacheFile(): File =
        File(context.cacheDir, "ck_decrypt_${System.currentTimeMillis()}.bin")
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ContentResolverStreamsModule {

    @Binds
    @Singleton
    abstract fun bindContentResolverStreams(
        impl: AndroidContentResolverStreams,
    ): ContentResolverStreams
}
