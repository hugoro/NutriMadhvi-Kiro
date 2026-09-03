package com.example.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FoodEditLogTest {

    private val entry = FoodEntry(
        name = "tosta mista",
        calories = 310,
        protein = 18.0,
        carbs = 28.0,
        fat = 12.0,
        timestamp = 1_700_000_000_000L,
        fullSummary = "tosta mista",
        mealCategory = MealCategory.SNACK
    )

    @Test
    fun roundTripsTypeAndModel() {
        val logged = entry.appendEditLog(FoodEditType.ADDED, "gemini-3.5-flash", nowMs = 1_000L)

        val decoded = decodeEditLog(logged.editLogJson)
        assertEquals(1, decoded.size)
        assertEquals(1_000L, decoded[0].epochMs)
        assertEquals(FoodEditType.ADDED, decoded[0].type)
        assertEquals("gemini-3.5-flash", decoded[0].model)
    }

    @Test
    fun keepsNewestEntryFirst() {
        val logged = entry
            .appendEditLog(FoodEditType.ADDED, "gemini-3.5-flash", nowMs = 1_000L)
            .appendEditLog(FoodEditType.AUTO, "qwen/qwen3.6-27b", nowMs = 2_000L)

        val decoded = decodeEditLog(logged.editLogJson)
        assertEquals(listOf(2_000L, 1_000L), decoded.map { it.epochMs })
        assertEquals("qwen/qwen3.6-27b", decoded[0].model)
    }

    /** Manual edits have no model behind them; the UI relies on that being null. */
    @Test
    fun manualEditHasNoModel() {
        val logged = entry.appendEditLog(FoodEditType.MANUAL, null, nowMs = 1_000L)

        assertNull(decodeEditLog(logged.editLogJson)[0].model)
    }

    /** Rows written before the model field existed must still decode. */
    @Test
    fun decodesLegacyEntriesWithoutModel() {
        val decoded = decodeEditLog("""[{"ms":1700000000000,"t":"ADDED"}]""")

        assertEquals(1, decoded.size)
        assertEquals(FoodEditType.ADDED, decoded[0].type)
        assertNull(decoded[0].model)
    }

    /** A malformed row must not take the rest of the history down with it. */
    @Test
    fun skipsUnreadableEntriesInsteadOfDroppingHistory() {
        val decoded = decodeEditLog(
            """[{"ms":2,"t":"NOT_A_TYPE"},{"ms":1,"t":"ADDED","m":"gemini-3.5-flash"}]"""
        )

        assertEquals(1, decoded.size)
        assertEquals(1L, decoded[0].epochMs)
    }

    @Test
    fun returnsEmptyForNullOrCorruptJson() {
        assertTrue(decodeEditLog(null).isEmpty())
        assertTrue(decodeEditLog("").isEmpty())
        assertTrue(decodeEditLog("not json").isEmpty())
    }

    /** The log lives inline in the row, so it must not grow without bound. */
    @Test
    fun capsHistoryLength() {
        var logged = entry
        repeat(40) { i ->
            logged = logged.appendEditLog(FoodEditType.AUTO, "gemini-3.5-flash", nowMs = i.toLong())
        }

        val decoded = decodeEditLog(logged.editLogJson)
        assertEquals(30, decoded.size)
        assertEquals("newest write must survive the cap", 39L, decoded[0].epochMs)
    }
}
