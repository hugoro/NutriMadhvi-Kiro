package com.example.ui

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F8 (performance): food photos (chat bubbles, meal history, Análise tab)
 * were decoded on every composition/remount with no cache — 160 photos in
 * files/food_images used to cause chained OOMs. FoodImagePreview now goes
 * through [foodImageBitmapCache], an LRU keyed by path with a byte-budget
 * (allocationByteCount, charged in whole MB, min 1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FoodImageBitmapCacheTest {

    private fun bitmapOfOneMb(): Bitmap =
        // 256 * 1024 px * 4 bytes (ARGB_8888) = exactly 1 MB.
        Bitmap.createBitmap(256, 1024, Bitmap.Config.ARGB_8888)

    @Test
    fun `get before put returns null`() {
        val cache = FoodImageBitmapCache(maxSizeMb = 4)
        assertNull(cache.get("missing"))
    }

    @Test
    fun `roundtrip returns the same bitmap instance`() {
        val cache = FoodImageBitmapCache(maxSizeMb = 4)
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        cache.put("a.png", bmp)
        assertSame(bmp, cache.get("a.png"))
    }

    @Test
    fun `eviction removes oldest when byte budget exceeded`() {
        val cache = FoodImageBitmapCache(maxSizeMb = 1)
        val a = bitmapOfOneMb()
        val b = bitmapOfOneMb()
        cache.put("a.png", a)
        assertNotNull("first entry must be present", cache.get("a.png"))
        // second 1MB entry pushes the total to 2MB > 1MB budget → a evicted
        cache.put("b.png", b)
        assertNull("oldest entry evicted", cache.get("a.png"))
        assertNotNull("newest entry kept", cache.get("b.png"))
    }

    @Test
    fun `smaller entries all fit inside the budget`() {
        val cache = FoodImageBitmapCache(maxSizeMb = 2)
        val small = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888) // 16 KB → charged 1 MB
        // 16 KB bitmaps charge 1 MB each (min 1); 2 of them fit in a 2 MB budget.
        val small2 = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        cache.put("s1", small)
        cache.put("s2", small2)
        assertNotNull(cache.get("s1"))
        assertNotNull(cache.get("s2"))
        assertEquals(2, cache.hitCount())
    }

    @Test
    fun `evictAll clears everything`() {
        val cache = FoodImageBitmapCache(maxSizeMb = 4)
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        cache.put("x", bmp)
        cache.evictAll()
        assertNull(cache.get("x"))
    }
}
