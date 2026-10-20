package com.example.data.performance

import com.example.data.local.BodyCompositionSnapshotEntity
import com.example.data.local.PerformanceSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [BodyPlanEngine] — the deterministic core of the
 * v39 body plan. The critical invariant under test is the **safety gate**:
 * REDUCE is never assigned to a muscle-dominant zone, with or without
 * composition data.
 */
class BodyPlanEngineTest {

    private fun comp(
        ts: Long,
        weight: Float,
        fat: Float? = null,
        muscle: Float? = null,
    ) = BodyCompositionSnapshotEntity(
        sourceTimestampMs = ts,
        weightKg = weight,
        bodyFatPct = fat,
        muscleMassKg = muscle,
    )

    private fun snap(
        shoulder: Float? = null,
        waist: Float? = null,
        hip: Float? = null,
        thigh: Float? = null,
        chest: Float? = null,
        belly: Float? = null,
        lateralThigh: Float? = null,
    ) = PerformanceSnapshotEntity(
        frontalShoulderRatio = shoulder,
        frontalWaistRatio = waist,
        frontalHipRatio = hip,
        frontalThighRatio = thigh,
        lateralChestRatio = chest,
        lateralBellyRatio = belly,
        lateralThighRatio = lateralThigh,
    )

    private val targets = mapOf(
        "shoulder" to 0.40f,
        "waist" to 0.23f,
        "hip" to 0.33f,
        "thigh" to 0.30f,
        "chest" to 0.24f,
        "belly" to 0.20f,
        "lateralThigh" to 0.22f,
    )

    // ── Phase classification ───────────────────────────────────────────────

    @Test
    fun `phase matrix - trends`() {
        // Fat up, muscle down → CUT
        val cut = BodyPlanEngine.classifyPhase(
            listOf(comp(1, 72f, 21f, 30.2f), comp(2, 72.5f, 22.5f, 30.0f)),
            null,
        )
        assertEquals(BodyPlanEngine.BodyPhase.CUT, cut)

        // Muscle up, fat flat → BULK
        val bulk = BodyPlanEngine.classifyPhase(
            listOf(comp(1, 72f, 21f, 30.0f), comp(2, 72.6f, 21.2f, 30.6f)),
            null,
        )
        assertEquals(BodyPlanEngine.BodyPhase.BULK, bulk)

        // Both up → RECOMP
        val recomp = BodyPlanEngine.classifyPhase(
            listOf(comp(1, 72f, 21f, 30.0f), comp(2, 72.8f, 21.7f, 30.5f)),
            null,
        )
        assertEquals(BodyPlanEngine.BodyPhase.RECOMP, recomp)
    }

    @Test
    fun `phase - flat trend falls back to goal intent, then MAINTAIN`() {
        val flat = listOf(comp(1, 72f, 21f, 30.0f), comp(2, 72f, 21.2f, 30.1f))
        assertEquals(BodyPlanEngine.BodyPhase.MAINTAIN, BodyPlanEngine.classifyPhase(flat, "manter peso"))
        assertEquals(BodyPlanEngine.BodyPhase.CUT, BodyPlanEngine.classifyPhase(flat, "quero perder peso"))
        assertEquals(BodyPlanEngine.BodyPhase.BULK, BodyPlanEngine.classifyPhase(flat, "quero ganhar massa"))
        assertEquals(BodyPlanEngine.BodyPhase.RECOMP, BodyPlanEngine.classifyPhase(flat, "recomposição corporal"))
    }

    @Test
    fun `phase - no usable composition data returns null`() {
        assertNull(BodyPlanEngine.classifyPhase(emptyList(), null))
        assertNull(BodyPlanEngine.classifyPhase(listOf(comp(1, 72f)), null))
        // Weight-only history (no fat/muscle signal):
        assertNull(BodyPlanEngine.classifyPhase(
            listOf(comp(1, 72f), comp(2, 71.5f)),
            null,
        ))
    }

    // ── Safety gate + priority ranking ─────────────────────────────────────

    @Test
    fun `safety gate - reduce only for fat zones, grow only for muscle zones`() {
        // One zone per call so each is guaranteed to be the top priority
        // (max=3) and its direction is asserted cleanly.
        assertEquals(
            BodyPlanEngine.Direction.REDUCE,
            BodyPlanEngine.rankPriorities(snap(waist = 0.27f), targets, true).single().direction,
        )
        assertEquals(
            BodyPlanEngine.Direction.REDUCE,
            BodyPlanEngine.rankPriorities(snap(belly = 0.24f), targets, true).single().direction,
        )
        assertEquals(
            BodyPlanEngine.Direction.GROW,
            BodyPlanEngine.rankPriorities(snap(shoulder = 0.38f), targets, true).single().direction,
        )
        assertEquals(
            BodyPlanEngine.Direction.GROW,
            BodyPlanEngine.rankPriorities(snap(hip = 0.31f), targets, true).single().direction,
        )
        assertEquals(
            BodyPlanEngine.Direction.GROW,
            BodyPlanEngine.rankPriorities(snap(thigh = 0.28f), targets, true).single().direction,
        )

        // THE invariant: no muscle zone is ever REDUCE, across a mixed set.
        val mixed = snap(shoulder = 0.38f, waist = 0.27f, hip = 0.31f, thigh = 0.28f, belly = 0.24f)
        val priorities = BodyPlanEngine.rankPriorities(mixed, targets, hasCompositionData = true)
        for (p in priorities) {
            if (p.tissue == BodyPlanEngine.Tissue.MUSCLE_DOMINANT) {
                assertTrue(
                    "muscle zone ${p.zone} must never be REDUCE",
                    p.direction != BodyPlanEngine.Direction.REDUCE
                )
            }
        }
    }

    @Test
    fun `safety gate - without composition data every direction is HOLD`() {
        val s = snap(shoulder = 0.38f, waist = 0.27f, hip = 0.31f)
        val priorities = BodyPlanEngine.rankPriorities(s, targets, hasCompositionData = false)
        assertTrue("priorities computed from gaps", priorities.isNotEmpty())
        for (p in priorities) {
            assertEquals("no composition data ⇒ HOLD (${p.zone})",
                BodyPlanEngine.Direction.HOLD, p.direction)
            assertEquals(BodyPlanEngine.Tissue.UNKNOWN, p.tissue)
        }
    }

    @Test
    fun `priorities - fat zone below target is HOLD, not grow`() {
        val s = snap(belly = 0.15f) // 5pp below the 0.20 target
        val priorities = BodyPlanEngine.rankPriorities(s, targets, hasCompositionData = true)
        assertEquals(1, priorities.size)
        assertEquals("belly", priorities[0].zone)
        assertEquals(BodyPlanEngine.Direction.HOLD, priorities[0].direction)
    }

    @Test
    fun `priorities - top-N by absolute gap, noise floor applied`() {
        // Waist +4pp (real), shoulder −2pp (real), hip −0.15pp (noise).
        val s = snap(shoulder = 0.38f, waist = 0.27f, hip = 0.3285f)
        val priorities = BodyPlanEngine.rankPriorities(s, targets, hasCompositionData = true, max = 3)
        assertEquals(listOf("waist", "shoulder"), priorities.map { it.zone })
        assertEquals(4.0f, priorities[0].gapPp, 0.01f)
        assertTrue("noise-floor hip (−0.15pp) must be excluded",
            priorities.none { it.zone == "hip" })
    }

    @Test
    fun `zones without a target or without a measurement are skipped`() {
        val noTargets = snap(waist = 0.30f, belly = 0.30f)
        assertTrue(
            BodyPlanEngine.rankPriorities(noTargets, emptyMap(), hasCompositionData = true).isEmpty()
        )
        val noMeasure = snap(waist = 0.30f) // chest/belly/etc unmeasured
        val p = BodyPlanEngine.rankPriorities(noMeasure, targets, hasCompositionData = true)
        assertEquals(listOf("waist"), p.map { it.zone })
    }

    // ── Default (fallback) tactics ─────────────────────────────────────────

    @Test
    fun `default tactics - deterministic, phase-aware, protein from weight`() {
        val priorities = listOf(
            BodyPlanEngine.ZonePriority("waist", BodyPlanEngine.Direction.REDUCE, 4f, BodyPlanEngine.Tissue.FAT_DOMINANT),
        )
        val a = BodyPlanEngine.defaultTactics(BodyPlanEngine.BodyPhase.CUT, priorities, 70f)
        val b = BodyPlanEngine.defaultTactics(BodyPlanEngine.BodyPhase.CUT, priorities, 70f)
        assertEquals("fallback tactics must be deterministic", a, b)

        val categories = a.map { it.category }
        assertTrue(categories.containsAll(listOf("protein", "strength", "steps", "sleep", "zone", "calories")))
        val protein = a.first { it.category == "protein" }
        assertEquals("126", protein.i18nArgs["grams"]) // 70kg × 1.8 g/kg
        val cut = a.first { it.category == "calories" }
        assertEquals("tactic_cut_deficit", cut.i18nKey)
        val zone = a.first { it.category == "zone" }
        assertEquals("waist", zone.i18nArgs["zone"])
        assertEquals("tactic_zone_reduce", zone.i18nKey)
    }

    @Test
    fun `default tactics - all-HOLD priorities add no zone tactic`() {
        val hold = listOf(
            BodyPlanEngine.ZonePriority("waist", BodyPlanEngine.Direction.HOLD, 4f, BodyPlanEngine.Tissue.FAT_DOMINANT),
        )
        val tactics = BodyPlanEngine.defaultTactics(BodyPlanEngine.BodyPhase.MAINTAIN, hold, null)
        assertFalse(tactics.any { it.category == "zone" })
        assertFalse(tactics.any { it.category == "calories" })
    }

    // ── Deltas ──────────────────────────────────────────────────────────────

    @Test
    fun `deltas between checkpoints`() {
        val prev = snap(waist = 0.24f, shoulder = 0.37f)
        val latest = snap(waist = 0.23f, shoulder = 0.38f)
        val d = BodyPlanEngine.deltasBetween(
            latest, prev,
            comp(2, 71.4f, 20.5f, 30.4f), comp(1, 71.8f, 21.3f, 30.0f),
        )
        assertEquals(-1.0f, d.zoneDeltasPp["waist"]!!, 0.01f)
        assertEquals(1.0f, d.zoneDeltasPp["shoulder"]!!, 0.01f)
        assertEquals(-0.4f, d.weightDeltaKg!!, 0.001f)
        assertEquals(-0.8f, d.fatDeltaPp!!, 0.001f)
        assertEquals(0.4f, d.muscleDeltaKg!!, 0.001f)
    }

    @Test
    fun `deltas - missing previous is all empty and null`() {
        val d = BodyPlanEngine.deltasBetween(snap(waist = 0.24f), null, comp(1, 70f), null)
        assertTrue(d.zoneDeltasPp.isEmpty())
        assertNull(d.weightDeltaKg)
    }

    // ── Coach-targets JSON parser ───────────────────────────────────────────

    @Test
    fun `targets parser - valid strict object`() {
        val m = BodyPlanEngine.targetsFromJson(
            """{"shoulder":0.40,"waist":0.23,"hip":0.31,"thigh":0.20,"chest":0.22,"belly":0.18,"lateralThigh":0.19}"""
        )
        assertEquals(7, m.size)
        assertEquals(0.40f, m["shoulder"]!!, 0.0001f)
        assertEquals(0.23f, m["waist"]!!, 0.0001f)
    }

    @Test
    fun `targets parser - tolerates whitespace, rejects malformed input`() {
        val m = BodyPlanEngine.targetsFromJson(""" { "waist" : 0.23 , "belly" : 0.18 } """)
        assertEquals(2, m.size)
        assertTrue(BodyPlanEngine.targetsFromJson("not json").isEmpty())
        assertTrue(BodyPlanEngine.targetsFromJson("""{"waist":0.23""").isEmpty())
        assertTrue(BodyPlanEngine.targetsFromJson("""{"waist":"fat"}""").isEmpty())
        assertTrue(BodyPlanEngine.targetsFromJson("""{"waist":1.5}""").isEmpty()) // out of 0..1
        assertTrue(BodyPlanEngine.targetsFromJson(null).isEmpty())
    }

    // ── v40: persisted-priority parser (defense at the parse boundary) ──────

    @Test
    fun `parsePrioritiesJson - valid rows round-trip`() {
        val json = """[{"zone":"waist","direction":"REDUCE","gapPp":1.8,"tissue":"FAT_DOMINANT"},{"zone":"shoulder","direction":"GROW","gapPp":-1.2,"tissue":"MUSCLE_DOMINANT"}]"""
        val list = BodyPlanEngine.parsePrioritiesJson(json)
        assertEquals(2, list.size)
        assertEquals("waist", list[0].zone)
        assertEquals(BodyPlanEngine.Direction.REDUCE, list[0].direction)
        assertEquals(1.8f, list[0].gapPp, 0.0001f)
        assertEquals(BodyPlanEngine.Tissue.FAT_DOMINANT, list[0].tissue)
        assertEquals(-1.2f, list[1].gapPp, 0.0001f)
    }

    @Test
    fun `parsePrioritiesJson - rejects implausible gapPp at the parse boundary`() {
        // 42pp is not a plausible zone-vs-target gap — a corrupted or
        // hostile row must not drive the plan: the entry is dropped.
        assertTrue(BodyPlanEngine.parsePrioritiesJson(
            """[{"zone":"waist","direction":"REDUCE","gapPp":42.0,"tissue":"FAT_DOMINANT"}]"""
        ).isEmpty())
        // A 29pp gap is still inside the auditable bound and survives.
        val list = BodyPlanEngine.parsePrioritiesJson(
            """[{"zone":"waist","direction":"REDUCE","gapPp":29.0,"tissue":"FAT_DOMINANT"}]"""
        )
        assertEquals(1, list.size)
        assertEquals(29.0f, list[0].gapPp, 0.0001f)
    }

    @Test
    fun `parsePrioritiesJson - drops non-finite and out-of-range gapPp`() {
        // NaN/Inf cannot come from real JSON numbers, but a corrupted/hand-
        // edited row or hostile input must never drive the plan.
        assertTrue(BodyPlanEngine.parsePrioritiesJson(
            """[{"zone":"waist","direction":"REDUCE","gapPp":NaN,"tissue":"FAT_DOMINANT"}]"""
        ).isEmpty())
        assertTrue(BodyPlanEngine.parsePrioritiesJson(
            """[{"zone":"waist","direction":"REDUCE","gapPp":99.0,"tissue":"FAT_DOMINANT"}]"""
        ).isEmpty())
        // Structural garbage aborts parsing at that entry (fail-soft: only
        // valid rows before it survive); unknown zone / unknown direction are
        // parsed and dropped individually.
        val mixed = BodyPlanEngine.parsePrioritiesJson(
            """[{"zone":"waist","direction":"REDUCE","gapPp":1.5,"tissue":"FAT_DOMINANT"},{"zone":"neck","direction":"REDUCE","gapPp":1.0,"tissue":"FAT_DOMINANT"},{"zone":"belly","direction":"SHRINK","gapPp":1.0,"tissue":"FAT_DOMINANT"},"not an object"]"""
        )
        assertEquals(1, mixed.size)
        assertEquals("waist", mixed[0].zone)
        assertTrue(BodyPlanEngine.parsePrioritiesJson(null).isEmpty())
        assertTrue(BodyPlanEngine.parsePrioritiesJson("").isEmpty())
        assertTrue(BodyPlanEngine.parsePrioritiesJson("garbage").isEmpty())
    }

    // ── v40: zone extraction for validation ─────────────────────────────────

    @Test
    fun `zonesOf - maps all seven silhouette zones`() {
        val snap = PerformanceSnapshotEntity(
            frontalShoulderRatio = 0.38f,
            frontalWaistRatio = 0.24f,
            frontalHipRatio = 0.31f,
            frontalThighRatio = 0.21f,
            lateralChestRatio = 0.20f,
            lateralBellyRatio = 0.19f,
            lateralThighRatio = 0.18f,
        )
        val zones = BodyPlanEngine.zonesOf(snap)
        assertEquals(7, zones.size)
        assertEquals(0.24f, zones["waist"]!!, 0.0001f)
        assertEquals(0.20f, zones["chest"]!!, 0.0001f)
        assertEquals(0.18f, zones["lateralThigh"]!!, 0.0001f)

        val empty = BodyPlanEngine.zonesOf(PerformanceSnapshotEntity())
        assertEquals(7, empty.size)
        assertTrue(empty.values.all { it == null })
    }
}
