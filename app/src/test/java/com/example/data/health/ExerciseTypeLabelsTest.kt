package com.example.data.health

import com.example.data.local.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression coverage for the fidelity contract: every SDK
 * `PredefinedExerciseType` name whitelisted in `ExerciseTypeLabels.bucket`
 * must (a) map to the historically-correct [ActivityType] bucket and
 * (b) render as a specific, non-uppercase-snake pretty label.
 *
 * The 41 names below reproduce the pre-refactor whitelist previously in
 * `SamsungHealthRepository.mapExerciseType`; a bucket regression here is
 * a silent pie-chart / weekly-volume corruption, so keep the assertions
 * exhaustive rather than sampled.
 */
class ExerciseTypeLabelsTest {

    // Sanity: null / blank / unknown special-cases.
    @Test fun `null returns empty pretty and OTHER bucket`() {
        assertEquals("", ExerciseTypeLabels.pretty(null))
        assertEquals(ActivityType.OTHER, ExerciseTypeLabels.bucket(null))
    }

    @Test fun `blank returns empty pretty and OTHER bucket`() {
        assertEquals("", ExerciseTypeLabels.pretty("   "))
        assertEquals(ActivityType.OTHER, ExerciseTypeLabels.bucket("   "))
    }

    // Fallback path: unknown SDK enum values (a future minor SDK release) must
    // render as sentence-case-with-spaces, never as raw UPPER_SNAKE.
    @Test fun `unknown SDK name falls back to sentence case`() {
        assertEquals("New modality", ExerciseTypeLabels.pretty("NEW_MODALITY"))
        assertEquals("K pop dance", ExerciseTypeLabels.pretty("K_POP_DANCE"))
        assertEquals(ActivityType.OTHER, ExerciseTypeLabels.bucket("NEW_MODALITY"))
    }

    // ─── Bucket-per-name lock (41 whitelist entries) ─────────────────────

    @Test fun `WALKING bucket`() {
        assertEquals(ActivityType.WALKING, ExerciseTypeLabels.bucket("WALKING"))
        assertEquals(ActivityType.WALKING, ExerciseTypeLabels.bucket("HIKING"))
        assertEquals(ActivityType.WALKING, ExerciseTypeLabels.bucket("BACKPACKING"))
    }

    @Test fun `RUNNING bucket`() {
        assertEquals(ActivityType.RUNNING, ExerciseTypeLabels.bucket("RUNNING"))
        assertEquals(ActivityType.RUNNING, ExerciseTypeLabels.bucket("TRACK_RUNNING"))
        assertEquals(ActivityType.RUNNING, ExerciseTypeLabels.bucket("TREADMILL"))
    }

    @Test fun `CYCLING bucket`() {
        assertEquals(ActivityType.CYCLING, ExerciseTypeLabels.bucket("BIKING"))
        assertEquals(ActivityType.CYCLING, ExerciseTypeLabels.bucket("MOUNTAIN_BIKING"))
        assertEquals(ActivityType.CYCLING, ExerciseTypeLabels.bucket("STATIONARY_BIKING"))
    }

    @Test fun `SWIMMING bucket`() {
        assertEquals(ActivityType.SWIMMING, ExerciseTypeLabels.bucket("POOL_SWIMMING"))
        assertEquals(ActivityType.SWIMMING, ExerciseTypeLabels.bucket("OPEN_WATER_SWIMMING"))
        assertEquals(ActivityType.SWIMMING, ExerciseTypeLabels.bucket("AQUA_AEROBICS"))
    }

    @Test fun `YOGA bucket collapses PILATES and STRETCHING`() {
        assertEquals(ActivityType.YOGA, ExerciseTypeLabels.bucket("YOGA"))
        assertEquals(ActivityType.YOGA, ExerciseTypeLabels.bucket("PILATES"))
        assertEquals(ActivityType.YOGA, ExerciseTypeLabels.bucket("STRETCHING"))
    }

    @Test fun `STRENGTH bucket covers every gym variant`() {
        val strength = listOf(
            "WEIGHT_MACHINE", "BENCH_PRESS", "SQUATS", "LUNGES",
            "LEG_PRESSES", "LEG_EXTENSIONS", "LEG_CURLS", "LEG_RAISES",
            "BACK_EXTENSIONS", "LAT_PULLDOWNS", "DEADLIFTS",
            "SHOULDER_PRESSES", "FRONT_RAISES", "LATERAL_RAISES",
            "ARM_CURLS", "ARM_EXTENSIONS",
            "PUSH_UPS", "PULL_UPS", "SIT_UPS", "CRUNCH", "PLANK",
            "BURPEES", "MOUNTAIN_CLIMBERS", "JUMPING_JACKS",
            "CIRCUIT_TRAINING", "AEROBICS",
            "STAIR_CLIMBING", "STAIR_CLIMBING_MACHINE", "STEP_MACHINE",
            "ROWING_MACHINE", "ELLIPTICAL"
        )
        strength.forEach { name ->
            assertEquals(
                "expected $name -> STRENGTH",
                ActivityType.STRENGTH,
                ExerciseTypeLabels.bucket(name)
            )
        }
    }

    @Test fun `unmapped sports fall through to OTHER`() {
        // Ball sports / racquet / dance / combat / winter / etc. — not in
        // any bucket today. Documented behaviour (see ExerciseTypeLabels.bucket).
        listOf(
            "BASKETBALL", "TENNIS", "MARTIAL_ARTS", "BOXING",
            "DANCING", "SKIING", "ROCK_CLIMBING", "TRIATHLON"
        ).forEach { name ->
            assertEquals(
                "expected $name -> OTHER",
                ActivityType.OTHER,
                ExerciseTypeLabels.bucket(name)
            )
        }
    }

    // ─── Pretty-label spot checks ────────────────────────────────────────

    @Test fun `pretty label for the Pilates screenshot scenario`() {
        // The whole point of the refactor — a PILATES session must read
        // "Pilates", not "YOGA" or "Yoga _ Stretch".
        val pretty = ExerciseTypeLabels.pretty("PILATES")
        assertEquals("Pilates", pretty)
        assertNotEquals("Yoga", pretty)
        assertNotEquals("YOGA", pretty)
    }

    @Test fun `pretty label collapses over-capitalisation`() {
        // The failure mode a naive titleCase() would produce:
        //   "STAIR_CLIMBING_MACHINE" -> "Stair Climbing Machine" (three caps).
        // Curated table keeps a single capital.
        assertEquals("Stair climber", ExerciseTypeLabels.pretty("STAIR_CLIMBING_MACHINE"))
        assertEquals("Weight machine", ExerciseTypeLabels.pretty("WEIGHT_MACHINE"))
        assertEquals("Mountain biking", ExerciseTypeLabels.pretty("MOUNTAIN_BIKING"))
        assertEquals("Track running", ExerciseTypeLabels.pretty("TRACK_RUNNING"))
        assertEquals("Open-water swimming", ExerciseTypeLabels.pretty("OPEN_WATER_SWIMMING"))
    }

    @Test fun `pretty labels are stable for every whitelisted name`() {
        // Every SDK name that carries a bucket assignment (i.e. is not
        // OTHER) MUST have an entry in the pretty table — anything else
        // is a hole in the fidelity contract. A hole would fall through
        // to the sentence-case fallback which is legible but not curated.
        val whitelisted = listOf(
            // WALKING
            "WALKING", "HIKING", "BACKPACKING",
            // RUNNING
            "RUNNING", "TRACK_RUNNING", "TREADMILL",
            // CYCLING
            "BIKING", "MOUNTAIN_BIKING", "STATIONARY_BIKING",
            // SWIMMING
            "POOL_SWIMMING", "OPEN_WATER_SWIMMING", "AQUA_AEROBICS",
            // YOGA
            "YOGA", "PILATES", "STRETCHING",
            // STRENGTH (see the block test above for the full 31 names)
            "WEIGHT_MACHINE", "BENCH_PRESS", "SQUATS", "LUNGES",
            "LEG_PRESSES", "LEG_EXTENSIONS", "LEG_CURLS", "LEG_RAISES",
            "BACK_EXTENSIONS", "LAT_PULLDOWNS", "DEADLIFTS",
            "SHOULDER_PRESSES", "FRONT_RAISES", "LATERAL_RAISES",
            "ARM_CURLS", "ARM_EXTENSIONS",
            "PUSH_UPS", "PULL_UPS", "SIT_UPS", "CRUNCH", "PLANK",
            "BURPEES", "MOUNTAIN_CLIMBERS", "JUMPING_JACKS",
            "CIRCUIT_TRAINING", "AEROBICS",
            "STAIR_CLIMBING", "STAIR_CLIMBING_MACHINE", "STEP_MACHINE",
            "ROWING_MACHINE", "ELLIPTICAL"
        )
        whitelisted.forEach { name ->
            val pretty = ExerciseTypeLabels.pretty(name)
            // No underscores; no all-caps token beyond the first character.
            assertEquals(
                "pretty($name) must not contain underscores",
                -1, pretty.indexOf('_')
            )
            assertNotEquals(
                "pretty($name) must not be raw uppercase",
                name, pretty
            )
        }
    }
}
