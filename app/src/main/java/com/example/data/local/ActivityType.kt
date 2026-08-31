package com.example.data.local

enum class ActivityType(val displayKey: String, val colorHex: Long) {
    RUNNING("Running", 0xFFEF4444L),
    WALKING("Walking", 0xFF10B981L),
    CYCLING("Cycling", 0xFF3B82F6L),
    SWIMMING("Swimming", 0xFF06B6D4L),
    STRENGTH("Strength / Gym", 0xFFF59E0BL),
    YOGA("Yoga / Stretch", 0xFF8B5CF6L),
    OTHER("Other / General", 0xFF94A3B8L);

    /**
     * Coarse training-bucket classification used by the chat's activity
     * coaching layer (DataFetcher → HABIT_PROTOCOL) so the LLM can comment
     * on cardio vs strength vs mobility volume without re-inferring it from
     * names. Aligned to the 7 enum values above; the heavier-grained Samsung
     * Health exercise types are already mapped down to this enum upstream
     * in `ExerciseTypeLabels.bucket` ([[samsung-health-exercise-type-mapping]]).
     */
    fun bucket(): TrainingBucket = when (this) {
        RUNNING, WALKING, CYCLING, SWIMMING -> TrainingBucket.CARDIO
        STRENGTH -> TrainingBucket.STRENGTH
        YOGA -> TrainingBucket.MOBILITY
        OTHER -> TrainingBucket.OTHER
    }

    companion object {
        fun fromName(name: String): ActivityType {
            val lower = name.lowercase()
            return when {
                lower.contains("run") || lower.contains("jog") || lower.contains("corrida") || lower.contains("correr") -> RUNNING
                lower.contains("walk") || lower.contains("step") || lower.contains("caminh") || lower.contains("passo") -> WALKING
                lower.contains("cycl") || lower.contains("bik") || lower.contains("cicl") || lower.contains("bicic") || lower.contains("pedal") -> CYCLING
                lower.contains("swim") || lower.contains("natac") || lower.contains("nadar") -> SWIMMING
                lower.contains("gym") || lower.contains("lift") || lower.contains("weight") || lower.contains("strength") || lower.contains("treino") || lower.contains("muscul") || lower.contains("peso") || lower.contains("hiit") || lower.contains("crossfit") -> STRENGTH
                lower.contains("yoga") || lower.contains("stretch") || lower.contains("pilates") || lower.contains("along") -> YOGA
                else -> OTHER
            }
        }
    }
}

/**
 * Coarse training bucket used by the chat to summarise weekly volume
 * (cardio kcal vs strength sessions vs mobility minutes) without forcing
 * the LLM to re-classify Samsung Health's ~105 PredefinedExerciseType
 * names. Mapping lives on [ActivityType.bucket].
 */
enum class TrainingBucket(val labelKey: String) {
    CARDIO("training_bucket_cardio"),
    STRENGTH("training_bucket_strength"),
    MOBILITY("training_bucket_mobility"),
    OTHER("training_bucket_other")
}
