package com.turnypt.app.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6.0: the pure CKF1 naming/mime helpers (the SAF query and bitmap
 * functions are device-side; these are the rules that must never drift).
 */
class ImageFilesTest {

    @Test
    fun `suggested encrypted name appends cki`() {
        assertEquals("photo.jpg.cki", ImageFiles.suggestedEncryptedName("photo.jpg"))
        assertEquals("IMG_001.png.cki", ImageFiles.suggestedEncryptedName("/storage/emu/0/DCIM/IMG_001.png"))
    }

    @Test
    fun `already encrypted names are not double suffixed`() {
        assertEquals("photo.jpg.cki", ImageFiles.suggestedEncryptedName("photo.jpg.cki"))
        // Case preserved: the passthrough never rewrites the name.
        assertEquals("photo.jpg.CKI", ImageFiles.suggestedEncryptedName("photo.jpg.CKI"))
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
    fun `size formatting stays human`() {
        assertEquals("512 B", ImageFiles.formatSize(512))
        assertEquals("1 KB", ImageFiles.formatSize(1024))
        assertEquals("512 KB", ImageFiles.formatSize(512 * 1024))
        assertEquals("1.5 MB", ImageFiles.formatSize(1_572_864))
        assertEquals("1.0 GB", ImageFiles.formatSize(1_073_741_824))
    }
}
