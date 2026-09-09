package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatSession::class,
        ChatMessage::class,
        ChatMessageFts::class,
        FoodEntry::class,
        ActivityEntry::class,
        FoodHabit::class,
        TargetSnapshot::class,
        BmrSnapshotEntity::class,
        BodyCompositionSnapshotEntity::class,
        DailyActivitySnapshotEntity::class,
        UserInsight::class,
        PredictedQaEntity::class,
        IngredientNutritionEntity::class,
        MealCorrectionEntity::class
    ],
    version = 34,
    exportSchema = true
)
@androidx.room.TypeConverters(Converters::class)
abstract class ChatDatabase : RoomDatabase() {
    abstract val chatDao: ChatDao
    abstract val foodDao: FoodDao
    abstract val activityDao: ActivityDao
    abstract val foodHabitDao: FoodHabitDao
    abstract val targetSnapshotDao: TargetSnapshotDao
    abstract val bmrSnapshotDao: BmrSnapshotDao
    abstract val bodyCompositionSnapshotDao: BodyCompositionSnapshotDao
    abstract val dailyActivitySnapshotDao: DailyActivitySnapshotDao
    abstract val userInsightDao: UserInsightDao
    abstract val predictedQaDao: PredictedQaDao
    abstract val ingredientNutritionDao: IngredientNutritionDao
    abstract val mealCorrectionDao: MealCorrectionDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activities ADD COLUMN shRecordId TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS food_habits (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        trigger TEXT NOT NULL,
                        descriptor TEXT NOT NULL,
                        mealCategoryHint TEXT,
                        notes TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        lastAppliedAt INTEGER,
                        timesApplied INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("ALTER TABLE food_entries ADD COLUMN appliedHabitIds TEXT DEFAULT NULL")
            }
        }

        /**
         * Adds the `target_snapshots` table that versions the prescribed daily targets
         * (kcal/macros/activity). Existing installs already have those values living
         * in the `user_profile_pref` SharedPreferences as a single overwritten row;
         * we seed one snapshot at epoch day 0 carrying those values, so every
         * historical FoodEntry/ActivityEntry is covered without producing "holes" in
         * past reports.
         *
         * Constructed with a Context so the migration can read the prefs at upgrade
         * time. The Context is application scope and only used here for that read.
         */
        fun migration_8_9(context: Context): Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS target_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        effectiveDateEpochDay INTEGER NOT NULL,
                        targetKcal INTEGER NOT NULL,
                        targetProtein REAL NOT NULL,
                        targetCarbs REAL NOT NULL,
                        targetFat REAL NOT NULL,
                        targetSteps INTEGER NOT NULL,
                        targetActiveMinutes INTEGER NOT NULL,
                        targetActivityCalories INTEGER NOT NULL,
                        note TEXT,
                        createdAtMs INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_target_snapshots_effectiveDateEpochDay " +
                        "ON target_snapshots(effectiveDateEpochDay)"
                )

                // Seed the initial snapshot from whatever the user already had in the
                // legacy prefs file. Defaults match UserProfile()'s defaults so a brand-new
                // install (no prefs) still gets a sane starting row.
                val prefs = context.getSharedPreferences("user_profile_pref", Context.MODE_PRIVATE)
                val nowMs = System.currentTimeMillis()
                db.execSQL(
                    """
                    INSERT INTO target_snapshots (
                        effectiveDateEpochDay,
                        targetKcal, targetProtein, targetCarbs, targetFat,
                        targetSteps, targetActiveMinutes, targetActivityCalories,
                        note, createdAtMs
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        0L, // epoch day 0 → covers every past entry
                        prefs.getInt("profile_target_kcal", 1800),
                        prefs.getFloat("profile_target_protein", 85.0f).toDouble(),
                        prefs.getFloat("profile_target_carbs", 220.0f).toDouble(),
                        prefs.getFloat("profile_target_fat", 60.0f).toDouble(),
                        prefs.getInt("profile_target_steps", 6000),
                        prefs.getInt("profile_target_active_minutes", 90),
                        prefs.getInt("profile_target_activity_calories", 500),
                        "seeded from user_profile_pref at upgrade",
                        nowMs
                    )
                )
            }
        }

        /**
         * Adds the `bmr_snapshots` table that versions the user's resting metabolic
         * rate (TMB / BMR) day-by-day. Until v10 the BMR was a single live value
         * (Samsung Health BodyComposition latest read OR `user_profile_pref.profile_manual_bmr`)
         * with no history, which meant historical reports computed "burned calories"
         * without any TMB component — sometimes flipping a real deficit into an
         * apparent surplus.
         *
         * Seeds one row at epoch day 0 carrying the legacy manual value, but
         * **only if** that value was set (`profile_manual_bmr > 0`). Installs
         * without a manual BMR start with an empty table; [com.example.data.bmr.BmrResolver]
         * returns 0 for every day and dashboards degrade gracefully until the
         * next SH sync or manual save populates the table.
         *
         * Constructed with a Context so the migration can read the prefs at upgrade
         * time. The Context is application scope and only used here for that read.
         */
        fun migration_9_10(context: Context): Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bmr_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        effectiveDateEpochDay INTEGER NOT NULL,
                        valueKcal INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        sourceTimestampMs INTEGER,
                        createdAtMs INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_bmr_snapshots_effectiveDateEpochDay " +
                        "ON bmr_snapshots(effectiveDateEpochDay)"
                )

                // Seed the initial snapshot from the legacy manual BMR if the user
                // had one. Skip otherwise — the table genuinely starts empty for
                // installs that never set a manual value, and BmrResolver tolerates
                // that. SH-derived values populate organically on the next sync.
                val prefs = context.getSharedPreferences("user_profile_pref", Context.MODE_PRIVATE)
                val legacyManualBmr = prefs.getInt("profile_manual_bmr", 0)
                if (legacyManualBmr > 0) {
                    val legacySavedAtMs = prefs.getLong("profile_manual_bmr_saved_at_ms", 0L)
                    val nowMs = System.currentTimeMillis()
                    db.execSQL(
                        """
                        INSERT INTO bmr_snapshots (
                            effectiveDateEpochDay,
                            valueKcal,
                            source,
                            sourceTimestampMs,
                            createdAtMs
                        ) VALUES (?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf<Any?>(
                            0L, // epoch day 0 → covers every past entry via the
                                // backwards-fill rule in BmrResolver
                            legacyManualBmr,
                            "MANUAL",
                            if (legacySavedAtMs > 0) legacySavedAtMs else null,
                            nowMs
                        )
                    )
                }
            }
        }

        /**
         * Adds the `groupId` column to `food_entries` so multiple FoodEntry rows
         * created in a single multi-photo capture session can be linked. The
         * column is nullable: pre-v11 rows stay NULL (treated as standalone
         * singletons by the UI), and singleton sessions on v11+ also stay NULL.
         * Only sessions with 2+ items get a generated id.
         *
         * This is an additive ALTER — O(1) on SQLite, does not rewrite or delete
         * any existing rows.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE food_entries ADD COLUMN groupId TEXT DEFAULT NULL")
            }
        }

        /**
         * Phase E: persistent facts the chat assistant has learned about the
         * user (preferences, allergies, goals, professional recommendations,
         * aggregate patterns). Additive CREATE — does not touch any existing
         * row. Indexed by category and expiredAtMs so the data-card injector
         * can fetch the top-N confirmed live facts in O(log N).
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_insights (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        text TEXT NOT NULL,
                        category TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        source TEXT NOT NULL,
                        sourceSessionId INTEGER,
                        createdAtMs INTEGER NOT NULL,
                        confirmedAtMs INTEGER,
                        expiredAtMs INTEGER,
                        relatedInsightIds TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_insights_category ON user_insights(category)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_user_insights_expiredAtMs ON user_insights(expiredAtMs)")
            }
        }

        /**
         * Phase G: differentiate personal chats from sessions intended to feed a
         * professional (nutritionist / personal trainer). Additive ALTER —
         * existing rows default to "personal" so old chats keep their semantics.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN sessionType TEXT NOT NULL DEFAULT 'personal'")
            }
        }

        /**
         * Phase A refinement: track when a session was last touched so the
         * "auto-resume" picker can surface the chat the user actually
         * conversed with most recently, not just the chat most recently
         * *created*. Additive ALTER — legacy rows seed `lastMessageAt` from
         * `createdAt` so they keep a sensible relative order.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Two-step: ADD COLUMN with a constant default (NOT NULL needs
                // a constant default in SQLite), then back-fill from createdAt.
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN lastMessageAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE chat_sessions SET lastMessageAt = createdAt")
            }
        }

        /**
         * Chat redesign 2026-06-12 / Phase 4 — long-press menu on chat
         * bubbles can now Edit / Copy / Delete a message. Editing apaga a
         * mensagem e todas as posteriores, e re-envia com o novo texto;
         * `editedTimestamp` (nullable) drives the "(editado)" marker on
         * the rewritten user bubble. Pre-v15 rows stay NULL = never edited.
         *
         * Additive ALTER — O(1) on SQLite, no row rewrite.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN editedTimestamp INTEGER DEFAULT NULL")
            }
        }

        /**
         * Adds the `chat_messages_fts` virtual table — an FTS4 shadow over
         * `chat_messages.text`, indexed by `rowid` which mirrors
         * `chat_messages.id`. Powers the WhatsApp-style cross-conversation
         * search added in 2026-06-15.
         *
         * Two operations:
         *   1. `CREATE VIRTUAL TABLE` defines the FTS4 schema. The literal
         *      DDL must match what Room's annotation processor generates
         *      from [ChatMessageFts] / `@Fts4(contentEntity=...)`, so a
         *      schema-validation pass at app start does not flag a drift.
         *   2. Back-fill: a single `INSERT INTO ... SELECT` populates the
         *      index from existing rows. O(N) over total chat history; in
         *      Hugo's install (~thousands of messages) this is a one-time
         *      sub-second cost at first launch on v16.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `chat_messages_fts` " +
                        "USING FTS4(`text` TEXT NOT NULL, " +
                        "content=`chat_messages`)"
                )
                // Back-fill: rowid in FTS == chat_messages.id (the parent's
                // primary key). Room expects this mapping when wiring the
                // FTS table to its content entity.
                db.execSQL(
                    "INSERT INTO `chat_messages_fts` (`docid`, `text`) " +
                        "SELECT `id`, `text` FROM `chat_messages`"
                )
            }
        }

        /**
         * Adds the `ingredientsJson` column on `food_entries` to persist the
         * per-ingredient breakdown the LLM now returns alongside meal totals.
         * Drives the "(10g manteiga; 20g queijo)" hints under each macro bar
         * in the edit-meal screen.
         *
         * Additive ALTER — O(1) on SQLite, no row rewrite. Pre-v17 entries
         * stay NULL and the UI silently skips the hint row for them.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE food_entries ADD COLUMN ingredientsJson TEXT DEFAULT NULL")
            }
        }

        /**
         * v17→v18 (plan ADENDO 2 — Fix 9, 2026-06-22): explicit Gemini
         * context cache reference per chat session. The chat orchestrator
         * uploads the long stable prefix (HABIT_PROTOCOL + template +
         * nameDirective) once on the first send of a session, stores the
         * resulting `cachedContents/{id}` here, and references it in
         * every subsequent turn — saving ~9kB of transit and ~2,500
         * tokens of input cost per turn.
         *
         * Pure additive ALTER — legacy rows get NULL and the orchestrator
         * treats that as "create on next send". No data loss possible.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN cachedContentName TEXT DEFAULT NULL")
            }
        }

        /**
         * v18→v19 (proactive notification engine, 2026-06-22): tags chat
         * sessions that belong to a proactive nudge thread so the worker
         * can resolve-or-reuse one session per day for meal reminders
         * (`meals:<iso-date>`) and one per ISO week for activity nudges
         * (`exercise:<year>-W<iso-week>`). Macros-at-risk and daily-summary
         * triggers leave this column NULL — they always spawn a fresh
         * session by design.
         *
         * Pure additive ALTER + indexed lookup column — O(1) on SQLite,
         * no row rewrite. Pre-v19 sessions stay NULL and the worker
         * treats them as untagged (cannot be resumed via proactiveTag).
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN proactiveTag TEXT DEFAULT NULL")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chat_sessions_proactiveTag " +
                        "ON chat_sessions(proactiveTag)"
                )
            }
        }

        /**
         * v19 → v20 (2026-06-26): one-shot purge of proactive sessions
         * that were created **automatically** by the old worker on every
         * notification delivery. The 2026-06-26 redesign moved session
         * creation to the moment the user actually taps the notification
         * (see [com.example.data.notifications.PendingProactiveNudge] +
         * [com.example.MainActivity.materializeAndOpen]), so the rows
         * that already sit in the DB are leftovers from un-tapped or
         * tapped-but-not-cleaned notifications.
         *
         * Per Hugo's "Limpar agora" decision, we wipe them here. We CANNOT
         * lean on `ForeignKey.CASCADE` to purge the child `chat_messages`:
         * SQLite runs migrations with `PRAGMA foreign_keys = OFF`, so a bare
         * `DELETE FROM chat_sessions` would leave the messages orphaned. We
         * therefore delete the child rows explicitly, first. Deleting the
         * `chat_messages` rows also fires the `chat_messages_fts_BEFORE_DELETE`
         * trigger Room created for the FTS shadow, keeping the virtual table
         * in sync — so we still don't touch `chat_messages_fts` directly.
         *
         * Personal chats (`sessionType != 'proactive'`) are untouched.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    DELETE FROM chat_messages
                    WHERE sessionId IN (
                        SELECT id FROM chat_sessions WHERE sessionType = 'proactive'
                    )
                    """.trimIndent()
                )
                db.execSQL("DELETE FROM chat_sessions WHERE sessionType = 'proactive'")
            }
        }

        /**
         * v20 → v21 (2026-06-26): introduces `body_composition_snapshots` —
         * a local cache of Samsung Health BodyComposition readings so the
         * chat assistant has weight/muscle/water history available and the
         * Reports tab can fall back to the last-known weight when S-Health
         * is offline. See [BodyCompositionSnapshotEntity] for the
         * append-only / Medisana-validated rationale.
         *
         * Purely additive — no existing data touched. New installs hit this
         * migration with an empty source table; existing installs preserve
         * every row in chat_messages / foods / activities / etc.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `body_composition_snapshots` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sourceTimestampMs` INTEGER,
                        `capturedAtMs` INTEGER NOT NULL,
                        `weightKg` REAL NOT NULL,
                        `bodyFatPct` REAL,
                        `muscleMassKg` REAL,
                        `totalBodyWaterKg` REAL,
                        `bmiValue` REAL,
                        `heightCm` REAL
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_body_composition_snapshots_sourceTimestampMs " +
                        "ON body_composition_snapshots(sourceTimestampMs)"
                )
            }
        }

        /**
         * v21 → v22 (2026-06-26): introduces `daily_activity_snapshots` —
         * one frozen row per day with the canonical "active calories" total
         * (workouts + casual movement from steps). The worker
         * [com.example.data.health.DailyActivitySnapshotWorker] captures
         * D-1 every day at ~02:00 so historical reports stay stable even if
         * Samsung Health back-fills/edits/shifts workout records later.
         *
         * Pre-v22 the dashboard re-derived the daily total from SH on every
         * read, which was inconsistent across devices (the Pixel counted
         * workout-time calories twice via `TOTAL_ACTIVE_CALORIES_BURNED`).
         * The snapshot keys off `dateEpochDay`; one row per day.
         *
         * Purely additive — no existing data touched. The table starts empty;
         * the worker's first run back-fills any missing days lazily (capped at
         * 30 days back, since SH typically doesn't have older raw workouts).
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `daily_activity_snapshots` (
                        `dateEpochDay` INTEGER PRIMARY KEY NOT NULL,
                        `activeKcal` INTEGER NOT NULL,
                        `workoutsKcal` INTEGER NOT NULL,
                        `steps` INTEGER NOT NULL,
                        `activeMinutes` INTEGER NOT NULL,
                        `capturedAtMs` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /**
         * 2026-06-29 — Frente Memory C2.a. Adds four nullable / defaulted
         * columns to `user_insights` so the local knowledge-base layer
         * (InsightExtractor + InsightRecaller) can score relevance per
         * turn and decay older / unreferenced facts.
         *
         * Strictly aditive: all existing rows survive with NULL for the
         * three nullable columns and 1 for `mentionCount`. No DROP, no
         * ALTER of existing columns. Robolectric migration test in
         * `UserInsightMigrationV22toV23Test.kt` pins this contract.
         *
         * Indexes on the new columns are intentionally omitted: the table
         * is small (<500 rows in any realistic install) and the recall
         * path is a Kotlin full-scan ranker (see [InsightRecaller]) — a
         * SQL index would buy nothing and slow down inserts/updates.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `user_insights` ADD COLUMN `keywords` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `user_insights` ADD COLUMN `lastReferencedAtMs` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `user_insights` ADD COLUMN `mentionCount` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `user_insights` ADD COLUMN `summary` TEXT DEFAULT NULL")
            }
        }

        /**
         * 2026-06-29 — Frente C1.a (rolling-window history). Adds a single
         * nullable `digestSummary` column to `chat_sessions` so the
         * orchestrator can store a 1-2 sentence summary of turns that
         * have dropped out of the LLM payload past the rolling cap. The
         * ChatMessage rows themselves are NEVER touched — they stay in
         * the DB and remain visible in the timeline. This column is only
         * read/written when assembling the next turn's request.
         *
         * Aditiva: existing sessions get NULL, which the orchestrator
         * treats as "no rolling cap reached yet". No data is lost.
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `digestSummary` TEXT DEFAULT NULL")
            }
        }

        /**
         * Adds the `freeSugarsG` column on `food_entries` to persist the WHO
         * "free sugars" breakdown estimated by the LLM at meal-analysis time.
         * Powers the split-color carbs bar on the dashboard (dark violet =
         * carbs from intact-matrix foods, light violet = free sugars) and the
         * per-meal "Açúcar livre: Xg / Yg" line on the edit-meal screen.
         *
         * Aditiva: rows pre-existing get NULL, treated as "unknown" by the UI,
         * which then renders the legacy single-color bar. No data lost.
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE food_entries ADD COLUMN freeSugarsG REAL DEFAULT NULL")
            }
        }

        /**
         * Adds the `saturatedFatG` column on `food_entries` to persist the WHO/AHA
         * saturated-fat breakdown estimated by the LLM at meal-analysis time.
         * Powers the split-color fat bar on the dashboard (yellow-500 = unsaturated,
         * yellow-200 = saturated tail) and the two-row "Gordura Saturada /
         * Insaturada" split on the Análise → Alimentação card.
         *
         * Aditiva: rows pre-existing get NULL, treated as "unknown" by the UI,
         * which then renders the legacy single-color yellow bar. No data lost.
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE food_entries ADD COLUMN saturatedFatG REAL DEFAULT NULL")
            }
        }

        /**
         * v27: `activities.sourceLabel TEXT NULL`.
         *
         * Preserves fidelity for the fine-grained Samsung Health modality
         * across the LOG_ACTIVITY_PROPOSAL round-trip. When the LLM emits a
         * `sourceLabel` inside the proposal block and the user confirms, the
         * value lands in this column alongside the coarse `activityType`
         * bucket — so a confirmed "Pilates" chat proposal persists as
         * `activityType=YOGA, sourceLabel="Pilates"`, matching the
         * ShWorkout.displayLabel priority used everywhere in the UI.
         *
         * Aditiva: pre-existing rows keep NULL, and the UI falls back to
         * `activityType.displayKey` for them (matches pre-refactor behaviour).
         * See [[samsung-health-exercise-type-mapping]].
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activities ADD COLUMN sourceLabel TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE food_entries ADD COLUMN editLogJson TEXT DEFAULT NULL")
            }
        }

        /**
         * Persists which LLM (Gemini tier or ensemble provider) produced a
         * meal-analysis result / attempted a chat reply, so the model badge in
         * the edit-meal screen survives an app restart — previously this only
         * lived in an in-memory ViewModel StateFlow and was lost on process
         * death.
         *
         * `food_entries.analysisModel TEXT NULL` + `chat_messages.analysisModel
         * TEXT NULL`. Additive: pre-existing rows keep NULL and the UI falls
         * back to the legacy generic label. No data lost.
         *
         * NOTE on numbering: this is the `analysisModel` change that shipped on
         * the live device already (verified: v32 DB physically carries both
         * columns). In the ensemble worktree it was numbered v30→v31 to stack
         * on top of main's v29→v30 editLogJson migration. Kept guarded so it is
         * a no-op where the column already exists.
         */
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("food_entries", "analysisModel", "TEXT DEFAULT NULL")
                db.addColumnIfMissing("chat_messages", "analysisModel", "TEXT DEFAULT NULL")
            }
        }

        /**
         * Repairs installs whose v31 was produced by an ensemble-worktree build
         * that predated the reconciliation. Both the INSA line and the ensemble
         * line independently shipped a schema numbered 31/32, so a device could
         * be stamped v31/v32 while missing (or already having) editLogJson /
         * analysisModel. Every add is guarded, so this is a no-op on the live
         * v32 shape and only backfills a column on the rare mismatched install.
         */
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("food_entries", "editLogJson", "TEXT DEFAULT NULL")
                db.addColumnIfMissing("food_entries", "analysisModel", "TEXT DEFAULT NULL")
                db.addColumnIfMissing("chat_messages", "analysisModel", "TEXT DEFAULT NULL")
            }
        }

        /**
         * Reconciliation bump (base = main + ensemble graft). The live device
         * is stamped v32 with a 12-entity identity hash (6941a9db…) because the
         * ensemble worktree left `ingredient_nutrition_cache` UNMAPPED. This
         * build re-maps `IngredientNutritionEntity` (13 entities) AND carries
         * the analysisModel columns, so Room computes a NEW identity hash that
         * matches neither v30 nor the live v32 stamp.
         *
         * Bumping to v33 forces Room through this migration and then re-stamps
         * room_master_table with the new hash instead of failing the integrity
         * check on launch (which would crash). Every object referenced here is
         * already physically present on the live DB (columns via v30/v31,
         * `ingredient_nutrition_cache` via v28→v29), so the body is a pure
         * no-op-if-exists guard. DDL is byte-identical to MIGRATION_28_29 so the
         * post-migration schema validation matches the generated 33.json.
         */
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ingredient_nutrition_cache` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `normalizedName` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `per100gCalories` REAL NOT NULL,
                        `per100gProtein` REAL NOT NULL,
                        `per100gCarbs` REAL NOT NULL,
                        `per100gFat` REAL NOT NULL,
                        `per100gSatFat` REAL NOT NULL,
                        `per100gFreeSugars` REAL NOT NULL,
                        `source` TEXT NOT NULL,
                        `confidence` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        `timesUsed` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ingredient_nutrition_cache_normalizedName` ON `ingredient_nutrition_cache` (`normalizedName`)"
                )
                db.addColumnIfMissing("food_entries", "editLogJson", "TEXT DEFAULT NULL")
                db.addColumnIfMissing("food_entries", "analysisModel", "TEXT DEFAULT NULL")
                db.addColumnIfMissing("chat_messages", "analysisModel", "TEXT DEFAULT NULL")
            }
        }

        /**
         * Adds the `meal_corrections` table — an append-only record of the gap
         * between the AI's meal-analysis estimate and what the user actually
         * saved. Powers [com.example.data.nutrition.CorrectionCalibrator], which
         * derives a directional calibration (macro bias + recurrent ingredient
         * substitutions) injected into future analysis prompts so the model
         * stops repeating the same interpretation errors.
         *
         * Purely additive `CREATE TABLE IF NOT EXISTS` — no existing table is
         * touched. New + existing installs hit this with an empty table; it
         * fills organically as the user corrects meals. See
         * [MealCorrectionEntity] for the append-only / nullable rationale.
         */
        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `meal_corrections` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `mealCategory` TEXT,
                        `model` TEXT,
                        `initialConfidence` TEXT,
                        `aiName` TEXT,
                        `userName` TEXT,
                        `aiCalories` INTEGER,
                        `userCalories` INTEGER,
                        `aiProtein` REAL,
                        `userProtein` REAL,
                        `aiCarbs` REAL,
                        `userCarbs` REAL,
                        `aiFat` REAL,
                        `userFat` REAL,
                        `aiIngredientsJson` TEXT,
                        `userIngredientsJson` TEXT
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_meal_corrections_createdAtMs` " +
                        "ON `meal_corrections`(`createdAtMs`)"
                )
            }
        }

        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Ingredient nutrition cache — grows organically as the user
                // analyses meals. First lookup goes to INSA/USDA/OFF; subsequent
                // analyses for the same ingredient are served from this table.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ingredient_nutrition_cache` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `normalizedName` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `per100gCalories` REAL NOT NULL,
                        `per100gProtein` REAL NOT NULL,
                        `per100gCarbs` REAL NOT NULL,
                        `per100gFat` REAL NOT NULL,
                        `per100gSatFat` REAL NOT NULL,
                        `per100gFreeSugars` REAL NOT NULL,
                        `source` TEXT NOT NULL,
                        `confidence` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        `timesUsed` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ingredient_nutrition_cache_normalizedName` ON `ingredient_nutrition_cache` (`normalizedName`)"
                )
            }
        }

        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 2026-07-06 (Frente I) — predictive Q&A cache table.
                // Populated by PredictiveQaWorker; consumed by
                // ChatOrchestrator.handle on send.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `predicted_qa` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `queryHash` TEXT NOT NULL,
                        `tag` TEXT NOT NULL,
                        `canonicalText` TEXT NOT NULL,
                        `answerText` TEXT NOT NULL,
                        `dataFingerprint` TEXT NOT NULL,
                        `language` TEXT NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `expiresAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_predicted_qa_queryHash` ON `predicted_qa` (`queryHash`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_predicted_qa_expiresAtMs` ON `predicted_qa` (`expiresAtMs`)"
                )
            }
        }

        fun getInstance(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "gemini_chat_database"
                )
                .addMigrations(
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    migration_8_9(context.applicationContext),
                    migration_9_10(context.applicationContext),
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23,
                    MIGRATION_23_24,
                    MIGRATION_24_25,
                    MIGRATION_25_26,
                    MIGRATION_26_27,
                    MIGRATION_27_28,
                    MIGRATION_28_29,
                    MIGRATION_29_30,
                    MIGRATION_30_31,
                    MIGRATION_31_32,
                    MIGRATION_32_33,
                    MIGRATION_33_34
                )
                // Hard guarantee that v9→v10 (and later) NEVER nukes user data:
                // if a defined migration throws, the app crashes loudly instead of
                // silently wiping foods/activities/chat/target/bmr snapshots.
                //
                // The destructive fallback is preserved ONLY for installs older
                // than v6 — those predate the introduction of explicit migrations
                // and are unrecoverable anyway. Realistic installs in the wild
                // are at v8 or v9 and never hit this branch.
                .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Idempotent `ALTER TABLE … ADD COLUMN`.
 *
 * SQLite has no `ADD COLUMN IF NOT EXISTS`, and a plain `ALTER TABLE` on an
 * existing column throws, aborting the whole migration and leaving the database
 * stamped at the old version. Two branches of this app shipped overlapping
 * schema numbers, so the same column can legitimately already exist on some
 * installs and not on others; every additive migration goes through this helper
 * to stay replay-safe.
 */
private fun SupportSQLiteDatabase.addColumnIfMissing(
    table: String,
    column: String,
    typeAndDefault: String
) {
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return
        }
    }
    execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $typeAndDefault")
}

