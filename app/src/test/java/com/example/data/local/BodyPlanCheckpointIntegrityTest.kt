package com.example.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v40 uniqueness/idempotency (review item 6) + checkpoint lookups
 * (review item 4) against a REAL Room database:
 *  - one checkpoint per snapshot (unique index + transactional idempotent
 *    insert, including a concurrent race where the loser re-reads the winner),
 *  - lookup by checkpoint ID and by snapshot ID (no row-count LIMIT),
 *  - latestBefore for comparison context,
 *  - snapshot-less checkpoints (NULL snapshotId) stay allowed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BodyPlanCheckpointIntegrityTest {

    private lateinit var db: ChatDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun cp(snapshotId: Long?, atMs: Long, phase: String? = "CUT") =
        BodyPlanCheckpointEntity(
            checkpointAtMs = atMs,
            snapshotId = snapshotId,
            phase = phase,
            prioritiesJson = """[{"zone":"waist","direction":"REDUCE","gapPp":1.0,"tissue":"FAT_DOMINANT"}]""",
        )

    @Test
    fun `idempotent insert returns the same id for the same snapshot`() = runBlocking {
        val dao = db.bodyPlanCheckpointDao
        val first = dao.insertIfAbsentBySnapshotId(cp(101L, 1_000L))
        val second = dao.insertIfAbsentBySnapshotId(cp(101L, 2_000L, "BULK"))
        assertEquals("same snapshot → same checkpoint id", first, second)
        dao.recent(10).let { assertEquals(1, it.size) }
        // The first writer's data is what both callers observed.
        assertEquals("CUT", dao.getById(first)?.phase)
    }

    @Test
    fun `concurrent inserts for one snapshot produce exactly one row`() = runBlocking {
        val dao = db.bodyPlanCheckpointDao
        val atMs = (0L..9L).map { 1_000L + it }
        val ids = atMs.map { ms ->
            async(Dispatchers.IO) { dao.insertIfAbsentBySnapshotId(cp(202L, ms, "CUT")) }
        }.awaitAll()
        assertEquals("all racers must observe the same row", ids.toSet().size, 1)
        val rows = dao.recent(10)
        assertEquals("exactly one checkpoint per snapshot", 1, rows.size)
        assertEquals(202L, rows.first().snapshotId)
    }

    @Test
    fun `raw duplicate insert violates the unique index`() = runBlocking {
        val dao = db.bodyPlanCheckpointDao
        dao.insertIfAbsentBySnapshotId(cp(303L, 1_000L))
        try {
            dao.insert(cp(303L, 2_000L))
            fail("a direct second insert for the same snapshot must violate the unique index")
        } catch (e: Exception) {
            // ConstraintViolation / SQLiteConstraintException — the index is
            // the final guard even if the app logic regresses.
            assertTrue(
                "expected a constraint violation, got: ${e::class.simpleName} ${e.message}",
                e.message?.contains("UNIQUE") == true || e.message?.contains("constraint") == true ||
                    e.message?.contains("Constraint") == true || e.message?.contains("index") == true
            )
        }
        assertEquals(1, dao.recent(10).size)
    }

    @Test
    fun `lookups by checkpoint id and by snapshot id have no row-count limit`() = runBlocking {
        val dao = db.bodyPlanCheckpointDao
        // Seed 60 checkpoints for distinct snapshots — far beyond any
        // "newest-50" window — then fetch an OLD one by its snapshot id.
        (0 until 60).forEach { i ->
            dao.insertIfAbsentBySnapshotId(cp(900L + i, 10_000L + i))
        }
        val oldestSnapshotId = 900L
        val found = dao.getBySnapshotId(oldestSnapshotId)
        assertNotNull("an old checkpoint must be reachable by snapshot id (no LIMIT)", found)
        assertEquals(oldestSnapshotId, found!!.snapshotId)
        assertEquals(10_000L, found.checkpointAtMs)
        assertEquals(found.id, dao.getById(found.id)?.id)
        assertNull(dao.getById(999_999L))
        assertNull(dao.getBySnapshotId(42_42L))
    }

    @Test
    fun `latestBefore returns the newest checkpoint strictly before the timestamp`() = runBlocking {
        val dao = db.bodyPlanCheckpointDao
        val a = dao.insertIfAbsentBySnapshotId(cp(401L, 1_000L, "CUT"))
        val b = dao.insertIfAbsentBySnapshotId(cp(402L, 2_000L, "RECOMP"))
        val c = dao.insertIfAbsentBySnapshotId(cp(403L, 3_000L, "BULK"))
        assertEquals(c, dao.latest()?.id)
        assertEquals(b, dao.latestBefore(3_000L)?.id)
        assertEquals(a, dao.latestBefore(2_000L)?.id)
        assertNull(dao.latestBefore(1_000L))
    }

    @Test
    fun `snapshot-less checkpoints remain allowed and distinct`() = runBlocking {
        val dao = db.bodyPlanCheckpointDao
        val x1 = dao.insert(cp(null, 1_000L))
        val x2 = dao.insert(cp(null, 2_000L))
        val x3 = dao.insert(cp(null, 3_000L))
        assertTrue(listOf(x1, x2, x3).toSet().size == 3)
        // …and coexist fine with anchored checkpoints.
        val y = dao.insertIfAbsentBySnapshotId(cp(501L, 1_500L))
        assertEquals(4, dao.recent(10).size)
        assertNotNull(dao.getById(y))
    }
}
