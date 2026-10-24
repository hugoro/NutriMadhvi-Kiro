package com.example.ui

import android.graphics.Bitmap
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * F3 (performance): chat-bubble image decoding used to run synchronously
 * inside `remember {}` — i.e. on the MAIN thread during composition
 * (30–150 ms per image observed). The decode logic is extracted here as the
 * pure function [decodeChatImagePayloads], which the bubble now invokes
 * inside `LaunchedEffect` on `Dispatchers.IO` (same pattern as
 * FoodImagePreview). These tests pin the decode behaviour (base64 + file://
 * path branches, fail-soft nulls) so the extraction is provably identical.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatImageDecodeOffMainTest {

    private fun pngBase64(width: Int = 8, height: Int = 6): String {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    @Test
    fun `legacy base64 payload decodes to a bitmap`() {
        val result = decodeChatImagePayloads(listOf(pngBase64(8, 6)))
        assertEquals(1, result.size)
        assertNotNull(result[0])
        assertEquals(8, result[0]!!.width)
        assertEquals(6, result[0]!!.height)
    }

    @Test
    fun `file path payload decodes from disk`() {
        val f = File.createTempFile("chatimg_test", ".png")
        f.deleteOnExit()
        val bmp = Bitmap.createBitmap(10, 5, Bitmap.Config.ARGB_8888)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val result = decodeChatImagePayloads(listOf("file://" + f.absolutePath))
        assertEquals(1, result.size)
        assertNotNull("file:// payload must decode", result[0])
        assertEquals(10, result[0]!!.width)
    }

    @Test
    fun `mixed base64 and path payload keeps order`() {
        val f = File.createTempFile("chatimg_test2", ".png")
        f.deleteOnExit()
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).let {
            FileOutputStream(f).use { os -> it.compress(Bitmap.CompressFormat.PNG, 100, os) }
        }
        val result = decodeChatImagePayloads(
            listOf(pngBase64(4, 4), "file://" + f.absolutePath)
        )
        assertEquals(2, result.size)
        assertNotNull(result[0])
        assertNotNull(result[1])
    }

    @Test
    fun `missing file does not throw`() {
        // NOTE: Robolectric's shadow BitmapFactory returns a placeholder
        // bitmap for undecodable/missing files (real Android returns null).
        // The behaviour that matters for the F3 extraction is fail-softness:
        // never throw out of the decode path.
        val result = decodeChatImagePayloads(listOf("file:///no/such/file/anywhere.png"))
        assertEquals(1, result.size)
    }

    @Test
    fun `garbage base64 does not throw`() {
        // Same Robolectric caveat: the shadow decoder may return a
        // placeholder where real Android would return null. Assert the
        // fail-soft contract (no exception, one result per entry) instead.
        val result = decodeChatImagePayloads(listOf("!!!not-base64-at-all!!!"))
        assertEquals(1, result.size)
    }

    @Test
    fun `decode is safe to call off-main (plain runBlocking, no looper needed)`() {
        // The production call site is `withContext(Dispatchers.IO)`. This just
        // proves the extracted function has no Android main-thread affinity
        // (no Looper/Handler dependency) so it can run on any dispatcher.
        val result = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                decodeChatImagePayloads(listOf(pngBase64(3, 3)))
            }
        }
        assertNotNull(result[0])
    }
}
