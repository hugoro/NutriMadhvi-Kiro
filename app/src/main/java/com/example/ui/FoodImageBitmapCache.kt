package com.example.ui

import android.graphics.Bitmap
import android.util.LruCache

/**
 * F8 (performance, 2026-10-22): LRU cache for downsampled food-photo
 * bitmaps, shared by every [FoodImagePreview] (chat bubbles, meal history,
 * Análise tab). Without it, each composition/remount re-decoded the file on
 * Dispatchers.IO — with 160 photos in files/food_images that re-decode
 * churn (plus the earlier main-thread decodes) chained OOMs on the S25.
 *
 * Budget: [maxSizeMb] whole MB charged per entry via allocationByteCount
 * (min 1 MB per entry so many tiny bitmaps can't fill it up). Evicted
 * bitmaps are dropped, NEVER recycled — a Compose Image may still hold the
 * last reference.
 */
internal class FoodImageBitmapCache(private val maxSizeMb: Int = 32) {
    private val cache = object : LruCache<String, Bitmap>(maxSizeMb) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            maxOf(1, value.allocationByteCount / (1024 * 1024))
    }

    fun get(path: String): Bitmap? = cache.get(path)

    fun put(path: String, bitmap: Bitmap) {
        cache.put(path, bitmap)
    }

    fun evictAll() {
        cache.evictAll()
    }

    fun hitCount(): Int = cache.hitCount()
}

/** App-wide instance: ~32 MB ≈ 8 downsampled 512-px photos. */
internal val foodImageBitmapCache = FoodImageBitmapCache()
