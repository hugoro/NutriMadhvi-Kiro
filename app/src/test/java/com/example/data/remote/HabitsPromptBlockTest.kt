package com.example.data.remote

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ChatDatabase
import com.example.data.local.FoodHabit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the habit-injection contract that the 2026-07-18 fix introduced:
 * [GeminiRepository.buildHabitsPromptBlock] must (a) surface each habit's
 * trigger keywords to the model, and (b) flag a habit as [LIKELY MATCH] when
 * one of its trigger keywords appears in the meal hint — the deterministic
 * pre-match that stops the model from silently ignoring a registered habit
 * (the "Café com leite" → pea-protein-not-dairy bug).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HabitsPromptBlockTest {

    private lateinit var db: ChatDatabase
    private lateinit var repo: GeminiRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = GeminiRepository(
            db.chatDao, db.foodDao, db.activityDao, db.foodHabitDao,
            db.targetSnapshotDao, db.bmrSnapshotDao, db.bodyCompositionSnapshotDao,
            db.dailyActivitySnapshotDao, db.userInsightDao, db.predictedQaDao
        )
    }

    @After
    fun tearDown() = db.close()

    /** The real "Café" habit from Hugo's device (id=7, trigger just "Café"). */
    private val cafeHabit = FoodHabit(
        id = 7,
        trigger = "Café",
        descriptor = "2 colheres de café soluvel com 200ml de água e 20ml leite proteico vly à base de lentilhas com 0% matéria gorda",
        mealCategoryHint = null
    )

    /** True when the emitted habit LINE (starts with "- id=") carries the flag,
     *  ignoring the instructional prose that also mentions "[LIKELY MATCH]". */
    private fun String.habitLineFlagged(): Boolean =
        lineSequence().any { it.trimStart().startsWith("- id=") && it.contains("[LIKELY MATCH") }

    @Test
    fun `block surfaces trigger keywords to the model`() {
        val block = repo.buildHabitsPromptBlock(listOf(cafeHabit), promptHint = null)
        assertTrue("trigger keyword must be emitted", block.contains("triggers=[Café]"))
        assertTrue("descriptor must be emitted", block.contains("leite proteico vly"))
    }

    @Test
    fun `habit is flagged LIKELY MATCH when its trigger appears in the meal hint`() {
        val block = repo.buildHabitsPromptBlock(
            listOf(cafeHabit),
            promptHint = "Café com leite e bolachas"
        )
        assertTrue(
            "café habit must be flagged when hint mentions café",
            block.habitLineFlagged()
        )
    }

    @Test
    fun `habit is NOT flagged when the hint has no matching trigger`() {
        val block = repo.buildHabitsPromptBlock(
            listOf(cafeHabit),
            promptHint = "Salada de frango grelhado"
        )
        assertFalse(
            "no false-positive match for an unrelated meal",
            block.habitLineFlagged()
        )
        // …but the habit is still offered (descriptor present) — matching is soft.
        assertTrue(block.contains("triggers=[Café]"))
    }

    @Test
    fun `multi-keyword trigger matches on any keyword`() {
        val weetabix = cafeHabit.copy(id = 12, trigger = "Weetabix; Weetabix com leite")
        val block = repo.buildHabitsPromptBlock(
            listOf(weetabix),
            promptHint = "Tigela de weetabix"
        )
        assertTrue(block.habitLineFlagged())
        assertTrue(block.contains("triggers=[Weetabix, Weetabix com leite]"))
    }

    @Test
    fun `empty habits list yields empty block`() {
        assertTrue(repo.buildHabitsPromptBlock(emptyList(), "anything").isEmpty())
    }

    @Test
    fun `pre-match is applied through the DAO snapshot path`() = runBlocking {
        db.foodHabitDao.insertHabit(cafeHabit.copy(id = 0))
        val selected = repo.selectHabitsForPrompt(
            repo.getActiveHabitsSnapshot(), categoryHint = null
        )
        val block = repo.buildHabitsPromptBlock(selected, promptHint = "café com leite e bolachas")
        assertTrue(block.habitLineFlagged())
    }
}
