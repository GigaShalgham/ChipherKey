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
 * v1.3.0 (user ruling on the 1.2.0 approach — "the extension must carry
 * the Turnypt theme: not .cki and not the source's own image extension,
 * and Turnypt must be suggested for opening files with that extension"):
 * encrypted images are named in the TNP family, `<name>.tnpi`, with the
 * dedicated `application/x-turnypt-image` MIME. What is encrypted is
 * still decided by CONTENT (the CKF1 magic), never by the name, and every
 * `.cki` file ever written keeps opening exactly as before.
 */
object ImageFiles {

    /**
     * v1.3.0: the Turnypt-ecosystem extension for encrypted images —
     * the file family of the TNP1:/TNS1: wire prefixes. An encrypted
     * photo is saved as `photo.jpg.tnpi`; opening any `.tnpi` file
     * suggests Turnypt.
     */
    const val ENCRYPTED_IMAGE_EXTENSION: String = "tnpi"

    /** The MIME type paired with [.ENCRYPTED_IMAGE_EXTENSION]. */
    const val ENCRYPTED_IMAGE_MIME: String = "application/x-turnypt-image"

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

    /** True when [name] carries the Turnypt image suffix (".tnpi"). */
    fun hasEncryptedImageExtension(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.US) == ENCRYPTED_IMAGE_EXTENSION

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
     * The suggested name for an ENCRYPTED image (v1.3.0):
     *
     *  - an image source joins the TNP family: `photo.jpg` →
     *    `photo.jpg.tnpi` (the original name is kept intact inside, so
     *    decrypting restores it exactly);
     *  - an extension-less name with a known image MIME derives the real
     *    image extension first: "IMG_001" + image/png → `IMG_001.png.tnpi`;
     *  - names already carrying `.tnpi` or the legacy `.cki` pass through
     *    unchanged (never double suffixed);
     *  - anything not recognizable as an image keeps the legacy
     *    `<name>.cki` suffix — those files still open through the .cki
     *    filters as before.
     */
    fun suggestedEncryptedName(displayName: String?, mime: String? = null): String {
        val base = displayName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return "image.$ENCRYPTED_IMAGE_EXTENSION"
        if (base.endsWith(".${Ckf1.FILE_EXTENSION}", ignoreCase = true)) return base
        if (hasEncryptedImageExtension(base)) return base
        val derived = extensionForMime(mime)
        val imageBase = when {
            hasImageExtension(base) -> base
            derived != null -> "$base.$derived"
            else -> null
        }
        return if (imageBase != null) {
            "$imageBase.$ENCRYPTED_IMAGE_EXTENSION"
        } else {
            "$base.${Ckf1.FILE_EXTENSION}"
        }
    }

    /**
     * "photo.jpg.tnpi" -> "photo.jpg"; "photo.jpg.cki" -> "photo.jpg";
     * a v1.2.0-era real-extension name ("photo.jpg") and anything else
     * pass through unchanged.
     */
    fun suggestedOriginalName(displayName: String?): String {
        val base = displayName?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return "file"
        return when {
            base.endsWith(".${Ckf1.FILE_EXTENSION}", ignoreCase = true) ->
                base.dropLast(Ckf1.FILE_EXTENSION.length + 1).ifBlank { "file" }
            base.endsWith(".$ENCRYPTED_IMAGE_EXTENSION", ignoreCase = true) ->
                base.dropLast(ENCRYPTED_IMAGE_EXTENSION.length + 1).ifBlank { "file" }
            else -> base
        }
    }

    /**
     * The suggested SAVE name for a decrypted image (v1.3.0): the original
     * name with the encrypted suffix stripped. When what remains carries
     * no extension at all (a renamed source, say "photo.tnpi"), the
     * extension comes from the DECRYPTED CONTENT's sniffed MIME — the one
     * honest witness of what the plaintext actually is
     * ("photo" + image/jpeg -> "photo.jpg").
     */
    fun suggestedPlaintextName(displayName: String?, sniffedMime: String?): String {
        val name = suggestedOriginalName(displayName)
        if (name.substringAfterLast('.', "").isNotEmpty()) return name
        return extensionForMime(sniffedMime)?.let { "$name.$it" } ?: name
    }

    /**
     * The MIME an ENCRYPTED output shares with (v1.3.0): `.tnpi` files
     * carry the Turnypt image type; everything else (`.cki` and the
     * v1.2.0-era real-extension files) carries the honest generic
     * x-cipherkey type — it is Turnypt ciphertext, never a real image.
     */
    fun encryptedShareMime(name: String?): String =
        if (hasEncryptedImageExtension(name ?: "")) ENCRYPTED_IMAGE_MIME else Ckf1.MIME_TYPE

    /**
     * True for the image MIME family (drives the preview vs. save-only UI).
     */
    fun isImageMime(mime: String?): Boolean = mime != null && mime.startsWith("image/", ignoreCase = true)

    /**
     * v1.2.0: reads the first bytes of [uri] and reports whether the content
     * carries the CKF1 magic — the one honest way to tell an encrypted file
     * from a plain one, whatever the name says. Never throws; unreadable
     * content is simply "not CKF1".
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
