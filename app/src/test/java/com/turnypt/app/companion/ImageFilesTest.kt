package com.turnypt.app.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6.0: the pure CKF1 naming/mime helpers (the SAF query and bitmap
 * functions are device-side; these are the rules that must never drift).
 *
 * v1.3.0: encrypted images carry the Turnypt-ecosystem extension (user
 * ruling: "not .cki and not the source's own image extension — the
 * extension must carry the Turnypt theme") — the rules below are the
 * contract.
 */
class ImageFilesTest {

    @Test
    fun `encrypted images join the tnp family`() {
        assertEquals("photo.jpg.tnpi", ImageFiles.suggestedEncryptedName("photo.jpg"))
        assertEquals("IMG_001.png.tnpi", ImageFiles.suggestedEncryptedName("/storage/emu/0/DCIM/IMG_001.png"))
        assertEquals("shot.webp.tnpi", ImageFiles.suggestedEncryptedName("shot.webp"))
        assertEquals("cat.gif.tnpi", ImageFiles.suggestedEncryptedName("cat.gif"))
    }

    @Test
    fun `extension-less image names derive the real extension from the mime`() {
        assertEquals("IMG_001.png.tnpi", ImageFiles.suggestedEncryptedName("IMG_001", "image/png"))
        assertEquals("snap.jpg.tnpi", ImageFiles.suggestedEncryptedName("snap", "image/jpeg"))
        assertEquals("motion.gif.tnpi", ImageFiles.suggestedEncryptedName("motion", "image/gif"))
    }

    @Test
    fun `already encrypted names are not double suffixed`() {
        assertEquals("photo.jpg.tnpi", ImageFiles.suggestedEncryptedName("photo.jpg.tnpi"))
        assertEquals("photo.jpg.cki", ImageFiles.suggestedEncryptedName("photo.jpg.cki"))
        // Case preserved: the passthrough never rewrites the name.
        assertEquals("photo.jpg.CKI", ImageFiles.suggestedEncryptedName("photo.jpg.CKI"))
        assertEquals("photo.jpg.TNPI", ImageFiles.suggestedEncryptedName("photo.jpg.TNPI"))
    }

    @Test
    fun `non-image sources keep the legacy cki suffix`() {
        assertEquals("notes.txt.cki", ImageFiles.suggestedEncryptedName("notes.txt"))
        assertEquals("data.bin.cki", ImageFiles.suggestedEncryptedName("data.bin", "application/octet-stream"))
        // An extension-less name with no image mime is not recognizable
        // as an image — the legacy generic suffix is the honest answer.
        assertEquals("IMG_001.cki", ImageFiles.suggestedEncryptedName("IMG_001"))
    }

    @Test
    fun `blank and null names fall back to image`() {
        assertEquals("image.tnpi", ImageFiles.suggestedEncryptedName(null))
        assertEquals("image.tnpi", ImageFiles.suggestedEncryptedName(""))
        assertEquals("image.tnpi", ImageFiles.suggestedEncryptedName("/"))
    }

    @Test
    fun `suggested original name strips the suffix once`() {
        assertEquals("photo.jpg", ImageFiles.suggestedOriginalName("photo.jpg.tnpi"))
        assertEquals("photo.jpg", ImageFiles.suggestedOriginalName("/tmp/photo.jpg.tnpi"))
        assertEquals("backup", ImageFiles.suggestedOriginalName("backup.TNPI"))
        assertEquals("photo.jpg", ImageFiles.suggestedOriginalName("photo.jpg.cki"))
        assertEquals("backup", ImageFiles.suggestedOriginalName("backup.CKI"))
    }

    @Test
    fun `v120-era real-extension names pass through as the decrypt save name`() {
        // v1.2.0 wrote encrypted images under their real image extension;
        // those files still decrypt (the sniff routes them), and the save
        // name is the name they already carry.
        assertEquals("photo.jpg", ImageFiles.suggestedOriginalName("photo.jpg"))
        assertEquals("IMG_001.png", ImageFiles.suggestedOriginalName("IMG_001.png"))
    }

    @Test
    fun `non-encrypted names pass through`() {
        assertEquals("notes.txt", ImageFiles.suggestedOriginalName("notes.txt"))
        assertEquals("file", ImageFiles.suggestedOriginalName(null))
    }

    @Test
    fun `plaintext save names derive a missing extension from the content`() {
        // A renamed source ("photo.tnpi") leaves "photo" — the sniffed
        // plaintext is the one honest witness of the extension to restore.
        assertEquals("photo.jpg", ImageFiles.suggestedPlaintextName("photo.tnpi", "image/jpeg"))
        assertEquals("photo.webp", ImageFiles.suggestedPlaintextName("photo.tnpi", "image/webp"))
        // Nothing sniffable: keep the bare name.
        assertEquals("photo", ImageFiles.suggestedPlaintextName("photo.tnpi", null))
        // Names that already carry an extension are never rewritten.
        assertEquals("photo.jpg", ImageFiles.suggestedPlaintextName("photo.jpg.tnpi", "image/png"))
        assertEquals("notes.txt", ImageFiles.suggestedPlaintextName("notes.txt", "image/jpeg"))
        // A null display name still gets the content-derived extension:
        // the sniffed plaintext is the witness ("file" -> "file.jpg").
        assertEquals("file.jpg", ImageFiles.suggestedPlaintextName(null, "image/jpeg"))
    }

    @Test
    fun `encrypted share mime follows the name`() {
        assertEquals(
            "application/x-turnypt-image",
            ImageFiles.encryptedShareMime("photo.jpg.tnpi"),
        )
        assertEquals(
            "application/x-turnypt-image",
            ImageFiles.encryptedShareMime("backup.TNPI"),
        )
        // Everything else — legacy .cki and v1.2.0-era real-extension
        // files — is Turnypt ciphertext, never a real image.
        assertEquals(
            "application/x-cipherkey",
            ImageFiles.encryptedShareMime("notes.txt.cki"),
        )
        assertEquals(
            "application/x-cipherkey",
            ImageFiles.encryptedShareMime("photo.jpg"),
        )
        assertEquals(
            "application/x-cipherkey",
            ImageFiles.encryptedShareMime(null),
        )
    }

    @Test
    fun `image mime family detection`() {
        assertTrue(ImageFiles.isImageMime("image/jpeg"))
        assertTrue(ImageFiles.isImageMime("IMAGE/PNG"))
        assertFalse(ImageFiles.isImageMime("application/pdf"))
        assertFalse(ImageFiles.isImageMime(null))
    }

    @Test
    fun `image extension detection`() {
        assertTrue(ImageFiles.hasImageExtension("photo.jpg"))
        assertTrue(ImageFiles.hasImageExtension("photo.JPEG"))
        assertTrue(ImageFiles.hasImageExtension("a.b.png"))
        assertFalse(ImageFiles.hasImageExtension("photo.cki"))
        assertFalse(ImageFiles.hasImageExtension("photo.tnpi"))
        assertFalse(ImageFiles.hasImageExtension("noext"))
        assertFalse(ImageFiles.hasImageExtension(""))
        // The Turnypt suffix is its own predicate.
        assertTrue(ImageFiles.hasEncryptedImageExtension("photo.jpg.tnpi"))
        assertTrue(ImageFiles.hasEncryptedImageExtension("PHOTO.TNPI"))
        assertFalse(ImageFiles.hasEncryptedImageExtension("photo.jpg"))
        assertFalse(ImageFiles.hasEncryptedImageExtension("photo.cki"))
        assertFalse(ImageFiles.hasEncryptedImageExtension("noext"))
        assertFalse(ImageFiles.hasEncryptedImageExtension(""))
    }

    @Test
    fun `name to mime and back`() {
        assertEquals("image/jpeg", ImageFiles.mimeForName("photo.jpg"))
        assertEquals("image/png", ImageFiles.mimeForName("/x/IMG.png"))
        assertEquals("image/webp", ImageFiles.mimeForName("shot.webp"))
        assertNull(ImageFiles.mimeForName("notes.txt"))
        assertNull(ImageFiles.mimeForName(null))
        assertEquals("jpg", ImageFiles.extensionForMime("image/jpeg"))
        assertEquals("png", ImageFiles.extensionForMime("image/PNG"))
        assertNull(ImageFiles.extensionForMime("application/pdf"))
        assertNull(ImageFiles.extensionForMime(null))
    }

    @Test
    fun `size formatting stays human`() {
        assertEquals("512 B", ImageFiles.formatSize(512))
        assertEquals("1 KB", ImageFiles.formatSize(1024))
        assertEquals("512 KB", ImageFiles.formatSize(512 * 1024))
        assertEquals("1.5 MB", ImageFiles.formatSize(1_572_864))
        assertEquals("1.0 GB", ImageFiles.formatSize(1_073_741_824))
    }
}
