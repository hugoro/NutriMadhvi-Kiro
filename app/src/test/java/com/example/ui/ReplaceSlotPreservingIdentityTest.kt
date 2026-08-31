package com.example.ui

import com.example.data.local.FoodEntry
import com.example.data.local.MealCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Locks the photo-replace-on-edit fix (2026-07-10). When the user edits an
 * already-saved meal and swaps its photo, the re-analysis produces a fresh
 * FoodEntry with id=0; [replaceSlotPreservingIdentity] must carry over the
 * original row's identity so the save UPDATES that row (new photo) instead of
 * INSERTing a duplicate and leaving the old photo behind.
 */
class ReplaceSlotPreservingIdentityTest {

    private fun saved(
        id: Long,
        image: String,
        ts: Long = 1_000L,
        group: String? = "meal_1_abc",
        cat: MealCategory = MealCategory.BREAKFAST,
        habits: List<Long>? = listOf(7L)
    ) = FoodEntry(
        id = id, name = "old", calories = 100, protein = 1.0, carbs = 1.0, fat = 1.0,
        timestamp = ts, imagePath = image, mealCategory = cat, groupId = group,
        appliedHabitIds = habits
    )

    private fun fresh(image: String) = FoodEntry(
        id = 0L, name = "new analysis", calories = 250, protein = 20.0, carbs = 8.0,
        fat = 5.0, timestamp = 9_999L, imagePath = image, mealCategory = MealCategory.SNACK,
        groupId = null, appliedHabitIds = null, saturatedFatG = 3.2
    )

    @Test
    fun `replacing a saved meal preserves identity and takes the new photo`() {
        val original = saved(id = 42L, image = "file:///food_images/old.jpg")
        val newEntry = fresh("file:///food_images/new.jpg")

        val result = replaceSlotPreservingIdentity(listOf(original), 0, newEntry)
        val e = result.single()

        // Identity carried over → save will updateFood (id != 0), same row.
        assertEquals(42L, e.id)
        assertEquals(1_000L, e.timestamp)
        assertEquals("meal_1_abc", e.groupId)
        assertEquals(MealCategory.BREAKFAST, e.mealCategory)
        assertEquals(listOf(7L), e.appliedHabitIds)

        // New photo + new analysis kept.
        assertEquals("file:///food_images/new.jpg", e.imagePath)
        assertEquals(250, e.calories)
        assertEquals(5.0, e.fat, 0.0)
        assertEquals(3.2, e.saturatedFatG!!, 0.0)
        assertEquals("new analysis", e.name)
    }

    @Test
    fun `id stays non-zero so save updates instead of inserts`() {
        val result = replaceSlotPreservingIdentity(
            listOf(saved(id = 5L, image = "file:///old.jpg")),
            0,
            fresh("file:///new.jpg")
        )
        assertNotEquals(0L, result.single().id)
    }

    @Test
    fun `replacing an unsaved draft slot keeps id zero (insert path unchanged)`() {
        val draft = saved(id = 0L, image = "file:///draft.jpg", group = null, habits = null)
        val result = replaceSlotPreservingIdentity(listOf(draft), 0, fresh("file:///new.jpg"))
        assertEquals(0L, result.single().id)
        assertEquals("file:///new.jpg", result.single().imagePath)
    }

    @Test
    fun `only the targeted slot changes in a multi-item session`() {
        val a = saved(id = 1L, image = "file:///a.jpg")
        val b = saved(id = 2L, image = "file:///b.jpg")
        val result = replaceSlotPreservingIdentity(listOf(a, b), 1, fresh("file:///b_new.jpg"))
        assertEquals("file:///a.jpg", result[0].imagePath) // untouched
        assertEquals(1L, result[0].id)
        assertEquals(2L, result[1].id)                     // identity preserved
        assertEquals("file:///b_new.jpg", result[1].imagePath) // new photo
    }

    @Test
    fun `empty list yields the fresh entry`() {
        val result = replaceSlotPreservingIdentity(emptyList(), 0, fresh("file:///new.jpg"))
        assertEquals("file:///new.jpg", result.single().imagePath)
        assertEquals(0L, result.single().id)
    }
}
