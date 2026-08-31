package com.example.data.health

import com.example.data.local.ActivityType

/**
 * Fidelity layer for Samsung Health's [DataType.ExerciseType.PredefinedExerciseType]
 * enum (~110 entries in AAR 1.1.0). Two responsibilities:
 *
 *  1. [pretty] returns a human-readable label for the SDK enum name — e.g.
 *     `"PILATES"` -> `"Pilates"`, `"WEIGHT_MACHINE"` -> `"Weight machine"`,
 *     `"MOUNTAIN_BIKING"` -> `"Mountain biking"`. The whole point is to
 *     preserve the specific modality the user actually did. Curated so that
 *     over-capitalisation ("STAIR_CLIMBING_MACHINE" -> "Stair Climbing Machine")
 *     and awkward token splits are avoided. Language: English only — the label
 *     is short and universally readable; localising 110 gym-vocab entries × 3
 *     languages is churn without comprehension gain (Hugo already reads
 *     "Yoga / Stretch" in English). Unknown SDK names fall back to sentence
 *     case with underscores replaced by spaces so any future enum value
 *     from a minor SDK bump stays legible.
 *
 *  2. [bucket] maps each SDK name onto the app's coarse [ActivityType] enum
 *     (7 values). This is the SAME whitelist behaviour previously in
 *     `SamsungHealthRepository.mapExerciseType` — 41 explicit names, rest
 *     falls through to OTHER. Kept byte-for-byte identical so pie chart /
 *     "By bucket:" LLM summary / TrainingBucket coaching cardinalities
 *     don't regress. If a name later graduates from OTHER to a more precise
 *     bucket, do it in a separate commit + verify against a known screenshot.
 *
 * See memory `[[samsung-health-exercise-type-mapping]]` for the pre-refactor
 * history and `[[aar-api-verification-method]]` for the SDK inspection
 * procedure used to enumerate the 110 values.
 */
internal object ExerciseTypeLabels {

    /**
     * Curated pretty labels for the 110 [PredefinedExerciseType] names in
     * samsung-health-data-api-1.1.0.aar. Keys are the SDK enum `name()` (upper
     * snake). Values are English display strings suitable for row rendering.
     */
    private val PRETTY: Map<String, String> = mapOf(
        // Meta / control (rare in real data but exist in the enum)
        "UNDEFINED" to "Unknown",
        "OTHER" to "Other exercise",
        "BREAK" to "Break",
        "COOL_DOWN" to "Cool-down",
        "WARM_UP" to "Warm-up",
        "TRANSITION" to "Transition",

        // Walking / running
        "WALKING" to "Walking",
        "RUNNING" to "Running",
        "TRACK_RUNNING" to "Track running",
        "TREADMILL" to "Treadmill",
        "HIKING" to "Hiking",
        "BACKPACKING" to "Backpacking",
        "ORIENTEERING" to "Orienteering",

        // Cycling
        "BIKING" to "Biking",
        "MOUNTAIN_BIKING" to "Mountain biking",
        "STATIONARY_BIKING" to "Stationary biking",

        // Swimming / water
        "POOL_SWIMMING" to "Pool swimming",
        "OPEN_WATER_SWIMMING" to "Open-water swimming",
        "AQUA_AEROBICS" to "Aqua aerobics",
        "CANOEING" to "Canoeing",
        "SAILING" to "Sailing",
        "SCUBA_DIVING" to "Scuba diving",
        "SNORKELING" to "Snorkeling",
        "KAYAKING" to "Kayaking",
        "KITESURFING" to "Kitesurfing",
        "RAFTING" to "Rafting",
        "ROWING" to "Rowing",
        "WINDSURFING" to "Windsurfing",
        "YACHTING" to "Yachting",
        "WATER_SKIING" to "Water skiing",

        // Mind–body
        "YOGA" to "Yoga",
        "PILATES" to "Pilates",
        "STRETCHING" to "Stretching",

        // Bodyweight / core / calisthenics
        "PUSH_UPS" to "Push-ups",
        "PULL_UPS" to "Pull-ups",
        "SIT_UPS" to "Sit-ups",
        "CRUNCH" to "Crunches",
        "PLANK" to "Plank",
        "BURPEES" to "Burpees",
        "MOUNTAIN_CLIMBERS" to "Mountain climbers",
        "JUMPING_JACKS" to "Jumping jacks",
        "CIRCUIT_TRAINING" to "Circuit training",
        "AEROBICS" to "Aerobics",
        "JUMP_ROPE" to "Jump rope",
        "HULA_HOOPING" to "Hula hooping",
        "SKATERS" to "Skaters",
        "HIGH_KNEES" to "High knees",

        // Strength — free weights & machines
        "BENCH_PRESS" to "Bench press",
        "SQUATS" to "Squats",
        "LUNGES" to "Lunges",
        "LEG_PRESSES" to "Leg press",
        "LEG_EXTENSIONS" to "Leg extensions",
        "LEG_CURLS" to "Leg curls",
        "LEG_RAISES" to "Leg raises",
        "BACK_EXTENSIONS" to "Back extensions",
        "LAT_PULLDOWNS" to "Lat pulldowns",
        "DEADLIFTS" to "Deadlifts",
        "SHOULDER_PRESSES" to "Shoulder press",
        "FRONT_RAISES" to "Front raises",
        "LATERAL_RAISES" to "Lateral raises",
        "ARM_CURLS" to "Arm curls",
        "ARM_EXTENSIONS" to "Arm extensions",
        "WEIGHT_MACHINE" to "Weight machine",
        "STEP_MACHINE" to "Step machine",
        "STAIR_CLIMBING" to "Stair climbing",
        "STAIR_CLIMBING_MACHINE" to "Stair climber",
        "ROWING_MACHINE" to "Rowing machine",
        "ELLIPTICAL" to "Elliptical",

        // Ball / team sports
        "BASEBALL" to "Baseball",
        "SOFTBALL" to "Softball",
        "CRICKET" to "Cricket",
        "GOLF" to "Golf",
        "BOWLING" to "Bowling",
        "HOCKEY" to "Hockey",
        "RUGBY" to "Rugby",
        "BASKETBALL" to "Basketball",
        "SOCCER" to "Soccer",
        "HANDBALL" to "Handball",
        "AMERICAN_FOOTBALL" to "American football",
        "VOLLEYBALL" to "Volleyball",
        "BEACH_VOLLEYBALL" to "Beach volleyball",
        "FLYING_DISC" to "Flying disc",

        // Racquet
        "SQUASH" to "Squash",
        "TENNIS" to "Tennis",
        "BADMINTON" to "Badminton",
        "TABLE_TENNIS" to "Table tennis",
        "RACQUETBALL" to "Racquetball",

        // Combat
        "BOXING" to "Boxing",
        "MARTIAL_ARTS" to "Martial arts",

        // Dance
        "BALLET" to "Ballet",
        "DANCING" to "Dancing",
        "BALLROOM_DANCING" to "Ballroom dancing",
        "ZUMBA" to "Zumba",

        // Outdoor / adventure
        "ROCK_CLIMBING" to "Rock climbing",
        "ARCHERY" to "Archery",
        "HORSEBACK_RIDING" to "Horseback riding",
        "HANG_GLIDING" to "Hang gliding",
        "INLINE_SKATING" to "Inline skating",
        "ROLLER_SKATING" to "Roller skating",

        // Winter
        "CROSS_COUNTRY_SKIING" to "Cross-country skiing",
        "SKIING" to "Skiing",
        "ALPINE_SKIING" to "Alpine skiing",
        "SNOWBOARDING" to "Snowboarding",
        "ICE_DANCING" to "Ice dancing",
        "ICE_SKATING" to "Ice skating",
        "ICE_HOCKEY" to "Ice hockey",
        "SNOWSHOEING" to "Snowshoeing",

        // Multisport
        "TRIATHLON" to "Triathlon",
        "DUATHLON" to "Duathlon",
        "AQUATHLON" to "Aquathlon",
        "AQUABIKE" to "Aquabike",
        "CROSS_TRIATHLON" to "Cross triathlon",
        "CROSS_DUATHLON" to "Cross duathlon"
    )

    /**
     * Human-readable label for a Samsung Health `PredefinedExerciseType.name`.
     * Null/blank/UNDEFINED returns empty string — the caller may fall back to
     * `ActivityType.displayKey`. Unknown names get a sentence-case fallback
     * (`"NEW_MODALITY"` -> `"New modality"`) so a future SDK enum addition
     * doesn't render as raw uppercase snake case.
     */
    fun pretty(sdkName: String?): String {
        if (sdkName.isNullOrBlank()) return ""
        PRETTY[sdkName]?.let { return it }
        // Fallback: replace underscores with spaces and lower-case everything
        // except the first character. Handles "K_POP_DANCE" -> "K pop dance"
        // gracefully; not perfect but never awful.
        val normalised = sdkName.replace('_', ' ').lowercase()
        return normalised.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * Coarse [ActivityType] bucket for a `PredefinedExerciseType.name`. Preserves
     * the exact whitelist previously in `SamsungHealthRepository.mapExerciseType`
     * (41 names across 6 buckets; rest → OTHER) so pie chart / bucket-summary
     * aggregates don't regress.
     */
    fun bucket(sdkName: String?): ActivityType = when (sdkName) {
        "WALKING", "HIKING", "BACKPACKING" -> ActivityType.WALKING

        "RUNNING", "TRACK_RUNNING", "TREADMILL" -> ActivityType.RUNNING

        "BIKING", "MOUNTAIN_BIKING", "STATIONARY_BIKING" -> ActivityType.CYCLING

        "POOL_SWIMMING", "OPEN_WATER_SWIMMING", "AQUA_AEROBICS" -> ActivityType.SWIMMING

        "YOGA", "PILATES", "STRETCHING" -> ActivityType.YOGA

        // Gym / strength training. The Samsung taxonomy is unusually
        // granular here — every machine and free-weight pattern is its
        // own enum value. All of them belong in the STRENGTH bucket for
        // the dashboard's purposes.
        "WEIGHT_MACHINE",
        "BENCH_PRESS", "SQUATS", "LUNGES",
        "LEG_PRESSES", "LEG_EXTENSIONS", "LEG_CURLS", "LEG_RAISES",
        "BACK_EXTENSIONS", "LAT_PULLDOWNS", "DEADLIFTS",
        "SHOULDER_PRESSES", "FRONT_RAISES", "LATERAL_RAISES",
        "ARM_CURLS", "ARM_EXTENSIONS",
        "PUSH_UPS", "PULL_UPS", "SIT_UPS", "CRUNCH", "PLANK",
        "BURPEES", "MOUNTAIN_CLIMBERS", "JUMPING_JACKS",
        "CIRCUIT_TRAINING", "AEROBICS",
        "STAIR_CLIMBING", "STAIR_CLIMBING_MACHINE", "STEP_MACHINE",
        "ROWING_MACHINE", "ELLIPTICAL" -> ActivityType.STRENGTH

        else -> ActivityType.OTHER
    }
}
