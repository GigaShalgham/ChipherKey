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
 * v1.2.0: encrypted images keep their real image extension (the user
 * report: "images are still saved with the .cki extension") — the rules
 * below are the contract.
 */
class ImageFilesTest {

    @Test
    fun `encrypted image keeps its real image extension`() {
        assertEquals("photo.jpg", ImageFiles.suggestedEncryptedName("photo.jpg"))
        assertEquals("IMG_001.png", ImageFiles.suggestedEncryptedName("/storage/emu/0/DCIM/IMG_001.png"))
        assertEquals("shot.webp", ImageFiles.suggestedEncryptedName("shot.webp"))
        assertEquals("cat.gif", ImageFiles.suggestedEncryptedName("cat.gif"))
    }

    @Test
    fun `extension-less image names derive the extension from the mime`() {
        assertEquals("IMG_001.png", ImageFiles.suggestedEncryptedName("IMG_001", "image/png"))
        assertEquals("snap.jpg", ImageFiles.suggestedEncryptedName("snap", "image/jpeg"))
        assertEquals("motion.gif", ImageFiles.suggestedEncryptedName("motion", "image/gif"))
    }

    @Test
    fun `already encrypted names are not double suffixed`() {
        assertEquals("photo.jpg.cki", ImageFiles.suggestedEncryptedName("photo.jpg.cki"))
        // Case preserved: the passthrough never rewrites the name.
        assertEquals("photo.jpg.CKI", ImageFiles.suggestedEncryptedName("photo.jpg.CKI"))
    }

    @Test
    fun `non-image sources keep the legacy cki suffix`() {
        assertEquals("notes.txt.cki", ImageFiles.suggestedEncryptedName("notes.txt"))
        assertEquals("data.bin.cki", ImageFiles.suggestedEncryptedName("data.bin", "application/octet-stream"))
    }

    @Test
    fun `blank and null names fall back to image`() {
        assertEquals("image.cki", ImageFiles.suggestedEncryptedName(null))
        assertEquals("image.cki", ImageFiles.suggestedEncryptedName(""))
        assertEquals("image.cki", ImageFiles.suggestedEncryptedName("/"))
    }

    @Test
    fun `suggested original name strips cki once`() {
        assertEquals("photo.jpg", ImageFiles.suggestedOriginalName("photo.jpg.cki"))
        assertEquals("photo.jpg", ImageFiles.suggestedOriginalName("/tmp/photo.jpg.cki"))
        assertEquals("backup", ImageFiles.suggestedOriginalName("backup.CKI"))
    }

    @Test
    fun `camouflaged encrypted images pass through as the decrypt save name`() {
        // v1.2.0: an encrypted photo saved as "photo.jpg" decrypts back to a
        // suggested save name of "photo.jpg" — the correct image extension.
        assertEquals("photo.jpg", ImageFiles.suggestedOriginalName("photo.jpg"))
        assertEquals("IMG_001.png", ImageFiles.suggestedOriginalName("IMG_001.png"))
    }

    @Test
    fun `non-cki names pass through`() {
        assertEquals("notes.txt", ImageFiles.suggestedOriginalName("notes.txt"))
        assertEquals("file", ImageFiles.suggestedOriginalName(null))
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
        assertFalse(ImageFiles.hasImageExtension("noext"))
        assertFalse(ImageFiles.hasImageExtension(""))
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
