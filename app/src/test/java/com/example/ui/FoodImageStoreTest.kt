package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FoodImageStoreTest {

    @Test
    fun `large upload is downscaled without modifying original`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = createLargeImage(context, "food-upload")
        val originalBytes = source.readBytes()
        assertTrue(originalBytes.size > 3_000_000)

        val result = FoodImageStore.loadImageBytesAndMime(
            context,
            Uri.fromFile(source).toString()
        )

        assertNotNull(result)
        val (encoded, mime) = requireNotNull(result)
        val uploadBytes = Base64.decode(encoded, Base64.NO_WRAP)
        val uploadBitmap = BitmapFactory.decodeByteArray(uploadBytes, 0, uploadBytes.size)
        assertNotNull(uploadBitmap)
        requireNotNull(uploadBitmap)
        assertEquals("image/jpeg", mime)
        assertTrue(maxOf(uploadBitmap.width, uploadBitmap.height) <= 2_048)
        assertTrue(uploadBytes.size < originalBytes.size)
        assertArrayEquals(originalBytes, source.readBytes())

        uploadBitmap.recycle()
        source.delete()
    }

    @Test
    fun `chat upload decode respects dimension bound without modifying original`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = createLargeImage(context, "chat-upload")
        val originalBytes = source.readBytes()

        val uploadBitmap = ChatImageStore.decodeBitmapWithOrientation(
            source.absolutePath,
            maxDimension = 2_048
        )

        assertNotNull(uploadBitmap)
        requireNotNull(uploadBitmap)
        assertTrue(maxOf(uploadBitmap.width, uploadBitmap.height) <= 2_048)
        assertArrayEquals(originalBytes, source.readBytes())

        uploadBitmap.recycle()
        source.delete()
    }

    private fun createLargeImage(context: Context, prefix: String): File {
        val source = File(context.cacheDir, "$prefix-${System.nanoTime()}.png")
        val width = 2_000
        val height = 2_000
        val pixels = IntArray(width * height) { index ->
            val mixed = index * 1_103_515_245 + 12_345
            (0xFF shl 24) or (mixed and 0x00FFFFFF)
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        source.outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
        return source
    }
}
