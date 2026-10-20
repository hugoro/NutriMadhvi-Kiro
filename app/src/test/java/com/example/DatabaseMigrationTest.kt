package com.example

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ChatDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest {

    @Test
    fun testMigration6To7PreservesData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_db")
        if (dbFile.exists()) {
            dbFile.delete()
        }

        val db6 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db6.version = 6
        
        db6.execSQL("""
            CREATE TABLE IF NOT EXISTS `activities` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `name` TEXT NOT NULL, 
                `durationMinutes` INTEGER NOT NULL, 
                `caloriesBurned` INTEGER NOT NULL, 
                `timestamp` INTEGER NOT NULL, 
                `sessionId` TEXT NOT NULL, 
                `fullSummary` TEXT NOT NULL, 
                `lastAiAnalyzedSummary` TEXT, 
                `activityType` TEXT NOT NULL, 
                `notes` TEXT
            )
        """.trimIndent())

        val values = ContentValues().apply {
            put("name", "Morning Jog")
            put("durationMinutes", 30)
            put("caloriesBurned", 250)
            put("timestamp", 123456789L)
            put("sessionId", "session_123")
            put("fullSummary", "Jogged 5k")
            put("lastAiAnalyzedSummary", "Good work")
            put("activityType", "RUNNING")
            put("notes", "Felt good")
        }
        val insertedId = db6.insert("activities", null, values)
        assertTrue(insertedId != -1L)
        db6.close()

        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_db")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        if (oldVersion == 6 && newVersion == 7) {
                            ChatDatabase.MIGRATION_6_7.migrate(db)
                        }
                    }
                })
                .build()
        )
        
        val db7 = openHelper.writableDatabase
        val cursor = db7.query("SELECT * FROM activities WHERE id = $insertedId")
        assertTrue(cursor.moveToFirst())
        
        val nameIndex = cursor.getColumnIndex("name")
        val durationIndex = cursor.getColumnIndex("durationMinutes")
        val shRecordIdIndex = cursor.getColumnIndex("shRecordId")
        
        assertEquals("Morning Jog", cursor.getString(nameIndex))
        assertEquals(30, cursor.getInt(durationIndex))
        assertTrue(shRecordIdIndex != -1)
        assertNull(cursor.getString(shRecordIdIndex))
        
        cursor.close()
        db7.close()
    }

    /**
     * v9 → v10 adds the `bmr_snapshots` table. The migration must:
     *  1. Preserve every existing row in foods, activities, chat, target_snapshots,
     *     food_habits — the user's history is not allowed to disappear on update.
     *  2. Seed bmr_snapshots from `user_profile_pref.profile_manual_bmr` IF that
     *     legacy value is > 0 (with effectiveDateEpochDay=0 so the backwards-fill
     *     covers all historical days).
     *  3. Leave bmr_snapshots empty when the legacy manual BMR is 0/unset (the
     *     resolver tolerates that and contributes 0 to "burned" totals).
     */
    @Test
    fun testMigration9To10PreservesDataAndSeedsBmrFromLegacyManual() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v9_v10_seeded")
        if (dbFile.exists()) dbFile.delete()

        // Pre-seed the legacy SharedPreferences the v9→v10 migration reads.
        val prefs = context.getSharedPreferences("user_profile_pref", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("profile_manual_bmr", 1500)
            .putLong("profile_manual_bmr_saved_at_ms", 1_700_000_000_000L)
            .apply()

        // Build a v9 schema by hand and load representative rows. Schema has to
        // match exactly what Room would have produced at v9 — these CREATE TABLE
        // statements come straight from that version's generated schema JSON.
        val db9 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db9.version = 9
        db9.execSQL("""
            CREATE TABLE IF NOT EXISTS `chat_sessions` (
                `id` TEXT PRIMARY KEY NOT NULL,
                `title` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `lastMessageAt` INTEGER NOT NULL
            )
        """.trimIndent())
        db9.execSQL("""
            CREATE TABLE IF NOT EXISTS `chat_messages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `imageBase64` TEXT
            )
        """.trimIndent())
        db9.execSQL("""
            CREATE TABLE IF NOT EXISTS `food_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `description` TEXT NOT NULL,
                `calories` INTEGER NOT NULL,
                `protein` REAL NOT NULL,
                `carbs` REAL NOT NULL,
                `fat` REAL NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `mealCategory` TEXT NOT NULL,
                `imageBase64` TEXT,
                `imagePath` TEXT,
                `appliedHabitIds` TEXT
            )
        """.trimIndent())
        db9.execSQL("""
            CREATE TABLE IF NOT EXISTS `activities` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `durationMinutes` INTEGER NOT NULL,
                `caloriesBurned` INTEGER NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `sessionId` TEXT NOT NULL,
                `fullSummary` TEXT NOT NULL,
                `lastAiAnalyzedSummary` TEXT,
                `activityType` TEXT NOT NULL,
                `notes` TEXT,
                `shRecordId` TEXT
            )
        """.trimIndent())
        db9.execSQL("""
            CREATE TABLE IF NOT EXISTS `food_habits` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `trigger` TEXT NOT NULL,
                `descriptor` TEXT NOT NULL,
                `mealCategoryHint` TEXT,
                `notes` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `isActive` INTEGER NOT NULL DEFAULT 1,
                `lastAppliedAt` INTEGER,
                `timesApplied` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db9.execSQL("""
            CREATE TABLE IF NOT EXISTS `target_snapshots` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `effectiveDateEpochDay` INTEGER NOT NULL,
                `targetKcal` INTEGER NOT NULL,
                `targetProtein` REAL NOT NULL,
                `targetCarbs` REAL NOT NULL,
                `targetFat` REAL NOT NULL,
                `targetSteps` INTEGER NOT NULL,
                `targetActiveMinutes` INTEGER NOT NULL,
                `targetActivityCalories` INTEGER NOT NULL,
                `note` TEXT,
                `createdAtMs` INTEGER NOT NULL
            )
        """.trimIndent())
        db9.execSQL(
            "CREATE INDEX IF NOT EXISTS index_target_snapshots_effectiveDateEpochDay " +
                "ON target_snapshots(effectiveDateEpochDay)"
        )

        // Representative rows the user shouldn't lose on update.
        db9.execSQL(
            "INSERT INTO chat_sessions(id, title, createdAt, lastMessageAt) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("sess-1", "First chat", 1_000L, 2_000L)
        )
        db9.execSQL(
            "INSERT INTO chat_messages(sessionId, role, content, timestamp) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("sess-1", "user", "olá", 1_500L)
        )
        db9.execSQL(
            """INSERT INTO food_entries
                (description, calories, protein, carbs, fat, timestamp, mealCategory)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>("pão de centeio", 210, 7.0, 38.0, 2.0, 1_700_000_000_000L, "BREAKFAST")
        )
        db9.execSQL(
            """INSERT INTO activities
                (name, durationMinutes, caloriesBurned, timestamp, sessionId,
                 fullSummary, activityType)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>("Walk", 35, 180, 1_700_000_000_000L, "act-sess", "casual walk", "WALKING")
        )
        db9.execSQL(
            """INSERT INTO food_habits
                (trigger, descriptor, createdAt, updatedAt, isActive, timesApplied)
                VALUES (?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>("iogurte", "Lidl 10% MG", 1_000L, 1_000L, 1, 5)
        )
        db9.execSQL(
            """INSERT INTO target_snapshots
                (effectiveDateEpochDay, targetKcal, targetProtein, targetCarbs, targetFat,
                 targetSteps, targetActiveMinutes, targetActivityCalories, note, createdAtMs)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(0L, 1800, 85.0, 220.0, 60.0, 6000, 90, 500, "seed", 1_700_000_000_000L)
        )
        db9.close()

        // Drive the v9→v10 migration through the same upgrade callback path Room
        // uses at runtime. We don't open the DB through Room here so a schema
        // mismatch at v10 wouldn't pollute the assertion (we just want to know
        // the migration's SQL statements ran without throwing AND that the
        // pre-existing rows are still queryable afterwards).
        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_v9_v10_seeded")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        if (oldVersion == 9 && newVersion == 10) {
                            ChatDatabase.migration_9_10(context).migrate(db)
                        }
                    }
                })
                .build()
        )
        val db10 = openHelper.writableDatabase

        // Existing rows must all still be there.
        db10.query("SELECT * FROM chat_sessions WHERE id = 'sess-1'").use {
            assertTrue("chat_sessions row vanished after migration", it.moveToFirst())
        }
        db10.query("SELECT * FROM chat_messages WHERE sessionId = 'sess-1'").use {
            assertTrue("chat_messages row vanished after migration", it.moveToFirst())
        }
        db10.query("SELECT calories FROM food_entries WHERE description = 'pão de centeio'").use {
            assertTrue("food_entries row vanished after migration", it.moveToFirst())
            assertEquals(210, it.getInt(0))
        }
        db10.query("SELECT caloriesBurned FROM activities WHERE name = 'Walk'").use {
            assertTrue("activities row vanished after migration", it.moveToFirst())
            assertEquals(180, it.getInt(0))
        }
        db10.query("SELECT timesApplied FROM food_habits WHERE trigger = 'iogurte'").use {
            assertTrue("food_habits row vanished after migration", it.moveToFirst())
            assertEquals(5, it.getInt(0))
        }
        db10.query("SELECT targetKcal FROM target_snapshots WHERE effectiveDateEpochDay = 0").use {
            assertTrue("target_snapshots row vanished after migration", it.moveToFirst())
            assertEquals(1800, it.getInt(0))
        }

        // bmr_snapshots must exist AND have been seeded from prefs.
        db10.query("SELECT effectiveDateEpochDay, valueKcal, source, sourceTimestampMs FROM bmr_snapshots").use {
            assertTrue("bmr_snapshots was not seeded from legacy manual BMR", it.moveToFirst())
            assertEquals(0L, it.getLong(0))
            assertEquals(1500, it.getInt(1))
            assertEquals("MANUAL", it.getString(2))
            assertEquals(1_700_000_000_000L, it.getLong(3))
            assertTrue("expected exactly one seeded row", !it.moveToNext())
        }

        db10.close()
        // Reset prefs so the next test starts clean.
        prefs.edit().clear().apply()
    }

    /** With no legacy manual BMR, bmr_snapshots must be empty after migration —
     *  but every other table must still be intact. */
    @Test
    fun testMigration9To10PreservesDataWithEmptyBmrTable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v9_v10_empty")
        if (dbFile.exists()) dbFile.delete()

        // Make sure the prefs DON'T carry a legacy BMR for this run.
        val prefs = context.getSharedPreferences("user_profile_pref", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        val db9 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db9.version = 9
        db9.execSQL("""
            CREATE TABLE IF NOT EXISTS `food_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `description` TEXT NOT NULL,
                `calories` INTEGER NOT NULL,
                `protein` REAL NOT NULL,
                `carbs` REAL NOT NULL,
                `fat` REAL NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `mealCategory` TEXT NOT NULL,
                `imageBase64` TEXT,
                `imagePath` TEXT,
                `appliedHabitIds` TEXT
            )
        """.trimIndent())
        db9.execSQL(
            """INSERT INTO food_entries
                (description, calories, protein, carbs, fat, timestamp, mealCategory)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>("ovo", 75, 6.0, 0.5, 5.0, 1_700_000_000_000L, "BREAKFAST")
        )
        db9.close()

        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_v9_v10_empty")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        if (oldVersion == 9 && newVersion == 10) {
                            ChatDatabase.migration_9_10(context).migrate(db)
                        }
                    }
                })
                .build()
        )
        val db10 = openHelper.writableDatabase

        db10.query("SELECT calories FROM food_entries WHERE description = 'ovo'").use {
            assertTrue("food row vanished even though we never touched it", it.moveToFirst())
            assertEquals(75, it.getInt(0))
        }
        db10.query("SELECT count(*) FROM bmr_snapshots").use {
            assertTrue(it.moveToFirst())
            assertEquals(
                "bmr_snapshots must be empty when legacy manual BMR is unset",
                0, it.getInt(0)
            )
        }

        db10.close()
    }

    /**
     * v10 → v11 adds the nullable `groupId` column to `food_entries`. The
     * migration must:
     *  1. Be O(1) — no row rewrites, no data loss.
     *  2. Leave every existing food row intact (kcal, name, mealCategory, etc.).
     *  3. Default groupId to NULL on legacy rows so the UI treats them as
     *     standalone singletons (legacy entries pre-multi-photo session feature).
     *  4. Accept new INSERTs that carry an explicit groupId for grouped rows.
     */
    @Test
    fun testMigration10To11AddsGroupIdAndPreservesData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v10_v11")
        if (dbFile.exists()) dbFile.delete()

        // Build a v10 schema that matches the production-generated CREATE TABLE for
        // food_entries (copied verbatim from
        // app/schemas/com.example.data.local.ChatDatabase/10.json so the assertion
        // doesn't drift if the v9→v10 raw test schema diverges).
        val db10 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db10.version = 10
        db10.execSQL("""
            CREATE TABLE IF NOT EXISTS `food_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `calories` INTEGER NOT NULL,
                `protein` REAL NOT NULL,
                `carbs` REAL NOT NULL,
                `fat` REAL NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `imagePath` TEXT,
                `analysisSummary` TEXT,
                `fullSummary` TEXT NOT NULL,
                `lastAiAnalyzedSummary` TEXT,
                `mealCategory` TEXT NOT NULL,
                `appliedHabitIds` TEXT
            )
        """.trimIndent())
        // A pre-v11 row — should survive untouched and end up with groupId=null.
        db10.execSQL(
            """INSERT INTO food_entries
                (name, calories, protein, carbs, fat, timestamp,
                 imagePath, analysisSummary, fullSummary, lastAiAnalyzedSummary,
                 mealCategory, appliedHabitIds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                "almoço legado", 540, 30.0, 60.0, 15.0, 1_700_000_000_000L,
                null, null, "frango grelhado com arroz", null,
                "LUNCH", null
            )
        )
        db10.close()

        // Drive the v10→v11 migration via the production migration object.
        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_v10_v11")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(11) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        if (oldVersion == 10 && newVersion == 11) {
                            ChatDatabase.MIGRATION_10_11.migrate(db)
                        }
                    }
                })
                .build()
        )
        val db11 = openHelper.writableDatabase

        // 1. Pre-existing row survived with all fields intact.
        db11.query(
            "SELECT name, calories, mealCategory, groupId FROM food_entries WHERE name = ?",
            arrayOf("almoço legado")
        ).use {
            assertTrue("legacy food_entries row vanished after migration", it.moveToFirst())
            assertEquals("almoço legado", it.getString(0))
            assertEquals(540, it.getInt(1))
            assertEquals("LUNCH", it.getString(2))
            assertNull("groupId on legacy row must default to NULL", it.getString(3))
        }

        // 2. New rows can carry an explicit groupId.
        db11.execSQL(
            """INSERT INTO food_entries
                (name, calories, protein, carbs, fat, timestamp,
                 fullSummary, mealCategory, groupId)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                "bolo de chocolate", 320, 4.0, 45.0, 12.0, 1_700_000_001_000L,
                "fatia de bolo", "DESSERT", "meal_1700000000000_abcd1234"
            )
        )
        db11.query(
            "SELECT groupId, mealCategory FROM food_entries WHERE name = ?",
            arrayOf("bolo de chocolate")
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("meal_1700000000000_abcd1234", it.getString(0))
            assertEquals("DESSERT", it.getString(1))
        }

        db11.close()
    }

    /**
     * v11 → v12 (Phase E) creates the `user_insights` table from scratch. The
     * migration must:
     *  1. Be additive — no row in any pre-existing table is touched.
     *  2. Land an empty `user_insights` table reachable for INSERTs.
     *  3. Create the indexes the data-card injector relies on.
     */
    @Test
    fun testMigration11To12AddsUserInsightsAndPreservesData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v11_v12")
        if (dbFile.exists()) dbFile.delete()

        val db11 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db11.version = 11
        // Minimal subset of v11 — chat_sessions + food_entries are enough to prove
        // the additive migration didn't disturb anything else.
        db11.execSQL("""
            CREATE TABLE IF NOT EXISTS `chat_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `model` TEXT NOT NULL,
                `systemInstruction` TEXT NOT NULL
            )
        """.trimIndent())
        db11.execSQL("""
            CREATE TABLE IF NOT EXISTS `food_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `calories` INTEGER NOT NULL,
                `protein` REAL NOT NULL,
                `carbs` REAL NOT NULL,
                `fat` REAL NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `imagePath` TEXT,
                `analysisSummary` TEXT,
                `fullSummary` TEXT NOT NULL,
                `lastAiAnalyzedSummary` TEXT,
                `mealCategory` TEXT NOT NULL,
                `appliedHabitIds` TEXT,
                `groupId` TEXT
            )
        """.trimIndent())
        db11.execSQL(
            "INSERT INTO chat_sessions(title, createdAt, model, systemInstruction) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("Antes da v12", 1_700_000_000_000L, "gemini-3.5-flash", "")
        )
        db11.execSQL(
            """INSERT INTO food_entries
                (name, calories, protein, carbs, fat, timestamp,
                 fullSummary, mealCategory)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                "iogurte", 120, 6.0, 12.0, 3.0, 1_700_000_000_000L,
                "iogurte natural", "BREAKFAST"
            )
        )
        db11.close()

        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_v11_v12")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(12) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int, newVersion: Int
                    ) {
                        if (oldVersion == 11 && newVersion == 12) {
                            ChatDatabase.MIGRATION_11_12.migrate(db)
                        }
                    }
                })
                .build()
        )
        val db12 = openHelper.writableDatabase

        // Pre-existing rows are still there.
        db12.query("SELECT title FROM chat_sessions").use {
            assertTrue(it.moveToFirst())
            assertEquals("Antes da v12", it.getString(0))
        }
        db12.query("SELECT calories FROM food_entries WHERE name = 'iogurte'").use {
            assertTrue(it.moveToFirst())
            assertEquals(120, it.getInt(0))
        }
        // user_insights table exists and starts empty.
        db12.query("SELECT count(*) FROM user_insights").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        // Indexes the data-card relies on are present.
        db12.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'user_insights'"
        ).use {
            val names = mutableSetOf<String>()
            while (it.moveToNext()) names += it.getString(0)
            assertTrue(
                "category index missing: $names",
                names.contains("index_user_insights_category")
            )
            assertTrue(
                "expiredAtMs index missing: $names",
                names.contains("index_user_insights_expiredAtMs")
            )
        }
        // INSERT works (proves the schema is reachable from outside the test).
        db12.execSQL(
            """INSERT INTO user_insights
                (text, category, confidence, source, createdAtMs)
                VALUES (?, ?, ?, ?, ?)""",
            arrayOf<Any?>("Hugo gosta de café", "preference", 0.9, "user_stated", 1_700_000_000_000L)
        )
        db12.query("SELECT count(*) FROM user_insights").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }

        db12.close()
    }

    /**
     * v12 → v13 (Phase G) adds the `sessionType` column to `chat_sessions`.
     * The migration must:
     *  1. Default every legacy row to `'personal'` so prior chats keep their
     *     semantics.
     *  2. Leave every column / row otherwise untouched.
     *  3. Accept new INSERTs that pass an explicit sessionType.
     */
    @Test
    fun testMigration12To13DefaultsExistingSessionsToPersonal() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v12_v13")
        if (dbFile.exists()) dbFile.delete()

        val db12 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db12.version = 12
        db12.execSQL("""
            CREATE TABLE IF NOT EXISTS `chat_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `model` TEXT NOT NULL,
                `systemInstruction` TEXT NOT NULL
            )
        """.trimIndent())
        // Two rows, distinguishable by title so the assertion is unambiguous.
        db12.execSQL(
            "INSERT INTO chat_sessions(title, createdAt, model, systemInstruction) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("Sessão antiga A", 1L, "gemini-3.5-flash", "")
        )
        db12.execSQL(
            "INSERT INTO chat_sessions(title, createdAt, model, systemInstruction) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("Sessão antiga B", 2L, "gemini-3.5-flash", "")
        )
        db12.close()

        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_v12_v13")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(13) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int, newVersion: Int
                    ) {
                        if (oldVersion == 12 && newVersion == 13) {
                            ChatDatabase.MIGRATION_12_13.migrate(db)
                        }
                    }
                })
                .build()
        )
        val db13 = openHelper.writableDatabase

        // Both legacy rows must end up with sessionType = 'personal'.
        db13.query("SELECT title, sessionType FROM chat_sessions ORDER BY createdAt").use {
            assertTrue(it.moveToFirst())
            assertEquals("Sessão antiga A", it.getString(0))
            assertEquals("personal", it.getString(1))
            assertTrue(it.moveToNext())
            assertEquals("Sessão antiga B", it.getString(0))
            assertEquals("personal", it.getString(1))
        }
        // New row carrying an explicit nutritionist type works.
        db13.execSQL(
            "INSERT INTO chat_sessions(title, createdAt, model, systemInstruction, sessionType) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>("Consulta nutri", 3L, "gemini-3.5-flash", "", "nutritionist")
        )
        db13.query(
            "SELECT sessionType FROM chat_sessions WHERE title = 'Consulta nutri'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("nutritionist", it.getString(0))
        }

        db13.close()
    }

    /**
     * v13 → v14 (Phase A refinement) adds the `lastMessageAt` column. The
     * migration must:
     *  1. Back-fill existing rows so `lastMessageAt = createdAt` (preserving
     *     the legacy createdAt-based ordering on first launch after upgrade).
     *  2. Accept new INSERTs that overwrite lastMessageAt explicitly.
     */
    @Test
    fun testMigration13To14BackfillsLastMessageAtFromCreatedAt() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v13_v14")
        if (dbFile.exists()) dbFile.delete()

        val db13 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db13.version = 13
        db13.execSQL("""
            CREATE TABLE IF NOT EXISTS `chat_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `model` TEXT NOT NULL,
                `systemInstruction` TEXT NOT NULL,
                `sessionType` TEXT NOT NULL DEFAULT 'personal'
            )
        """.trimIndent())
        // Distinct createdAt values so the back-fill assertion is unambiguous.
        db13.execSQL(
            "INSERT INTO chat_sessions(title, createdAt, model, systemInstruction) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("oldA", 1_700_000_000_000L, "gemini-3.5-flash", "")
        )
        db13.execSQL(
            "INSERT INTO chat_sessions(title, createdAt, model, systemInstruction) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("oldB", 1_700_000_500_000L, "gemini-3.5-flash", "")
        )
        db13.close()

        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_v13_v14")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(14) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int, newVersion: Int
                    ) {
                        if (oldVersion == 13 && newVersion == 14) {
                            ChatDatabase.MIGRATION_13_14.migrate(db)
                        }
                    }
                })
                .build()
        )
        val db14 = openHelper.writableDatabase

        db14.query("SELECT title, createdAt, lastMessageAt FROM chat_sessions ORDER BY createdAt").use {
            assertTrue(it.moveToFirst())
            assertEquals("oldA", it.getString(0))
            assertEquals(1_700_000_000_000L, it.getLong(1))
            assertEquals(
                "oldA: lastMessageAt should equal createdAt after back-fill",
                1_700_000_000_000L, it.getLong(2)
            )
            assertTrue(it.moveToNext())
            assertEquals("oldB", it.getString(0))
            assertEquals(1_700_000_500_000L, it.getLong(1))
            assertEquals(
                "oldB: lastMessageAt should equal createdAt after back-fill",
                1_700_000_500_000L, it.getLong(2)
            )
        }
        // New row overrides lastMessageAt explicitly.
        db14.execSQL(
            """INSERT INTO chat_sessions
                (title, createdAt, model, systemInstruction, sessionType, lastMessageAt)
                VALUES (?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>("fresh", 1_700_000_900_000L, "gemini-3.5-flash", "", "personal", 1_700_001_000_000L)
        )
        db14.query("SELECT lastMessageAt FROM chat_sessions WHERE title = 'fresh'").use {
            assertTrue(it.moveToFirst())
            assertEquals(1_700_001_000_000L, it.getLong(0))
        }

        db14.close()
    }

    /**
     * v19 → v20 (2026-06-26): the migration purges proactive sessions that
     * were auto-created by the old worker on every notification delivery,
     * but **must not touch** personal / nutritionist / personal_trainer
     * sessions. This test is the safety net: pre-populates the DB with one
     * row of every sessionType (plus a message under each), runs the
     * migration, and asserts that only the proactive session + its
     * messages disappear. Foreign-key CASCADE handles the messages
     * automatically — we still assert their absence to catch a future
     * regression where someone disables the FK.
     *
     * Per Hugo's "be careful, don't delete genuine data" reminder
     * (2026-06-26). See memory dashboard-redesign-2026-06-26.
     */
    @Test
    fun testMigration19To20PurgesOnlyProactiveSessions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v19_v20")
        if (dbFile.exists()) dbFile.delete()

        // Build a v19 schema by hand, mirroring the exported v19.json. We
        // only need the chat_sessions + chat_messages tables here — every
        // other entity is irrelevant to the migration under test.
        val db19 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db19.version = 19
        db19.execSQL("PRAGMA foreign_keys = ON")
        db19.execSQL("""
            CREATE TABLE IF NOT EXISTS `chat_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `model` TEXT NOT NULL,
                `systemInstruction` TEXT NOT NULL,
                `sessionType` TEXT NOT NULL,
                `lastMessageAt` INTEGER NOT NULL,
                `cachedContentName` TEXT,
                `proactiveTag` TEXT
            )
        """.trimIndent())
        db19.execSQL("""
            CREATE TABLE IF NOT EXISTS `chat_messages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `sender` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `isError` INTEGER NOT NULL,
                `imageBase64` TEXT,
                `editedTimestamp` INTEGER,
                FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())

        // Insert one session of every legitimate user-owned sessionType + one
        // proactive session. We label the rowids explicitly so the assertions
        // below stay readable.
        fun insertSession(id: Long, sessionType: String, title: String) {
            db19.execSQL(
                """INSERT INTO chat_sessions
                    (id, title, createdAt, model, systemInstruction, sessionType, lastMessageAt, cachedContentName, proactiveTag)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)""",
                arrayOf<Any?>(
                    id, title, 1_700_000_000_000L, "gemini-3.5-flash", "",
                    sessionType, 1_700_000_500_000L,
                    if (sessionType == "proactive") "meals:2026-06-26" else null
                )
            )
        }
        insertSession(1L, "personal", "Madhvi's daily journal")
        insertSession(2L, "nutritionist", "Consulta com nutricionista")
        insertSession(3L, "personal_trainer", "Plano de treino")
        insertSession(4L, "proactive", "Refeições de hoje")

        fun insertMessage(sessionId: Long, text: String) {
            db19.execSQL(
                """INSERT INTO chat_messages
                    (sessionId, sender, text, timestamp, isError)
                    VALUES (?, ?, ?, ?, 0)""",
                arrayOf<Any?>(sessionId, "user", text, 1_700_000_400_000L)
            )
        }
        insertMessage(1L, "what did i eat today?")
        insertMessage(2L, "should i cut carbs?")
        insertMessage(3L, "how many reps for shoulders?")
        insertMessage(4L, "[auto] missing breakfast nudge")

        db19.close()

        // Run the migration through the SupportSQLiteOpenHelper plumbing the
        // same way other tests in this file do — keeps PRAGMA foreign_keys
        // and CASCADE semantics aligned with what Room does at runtime.
        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_v19_v20")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(20) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        if (oldVersion == 19 && newVersion == 20) {
                            ChatDatabase.MIGRATION_19_20.migrate(db)
                        }
                    }
                })
                .build()
        )
        val db20 = openHelper.writableDatabase

        // Personal / nutritionist / personal_trainer rows survive verbatim.
        db20.query(
            "SELECT id, title, sessionType FROM chat_sessions ORDER BY id"
        ).use { cursor ->
            val rows = mutableListOf<Triple<Long, String, String>>()
            while (cursor.moveToNext()) {
                rows += Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2))
            }
            assertEquals(
                "Only the 3 non-proactive sessions should remain",
                listOf(
                    Triple(1L, "Madhvi's daily journal", "personal"),
                    Triple(2L, "Consulta com nutricionista", "nutritionist"),
                    Triple(3L, "Plano de treino", "personal_trainer"),
                ),
                rows
            )
        }

        // And their messages too — CASCADE only fired for the proactive one.
        db20.query(
            "SELECT sessionId, text FROM chat_messages ORDER BY sessionId"
        ).use { cursor ->
            val rows = mutableListOf<Pair<Long, String>>()
            while (cursor.moveToNext()) {
                rows += cursor.getLong(0) to cursor.getString(1)
            }
            assertEquals(
                "Only messages from the 3 surviving sessions remain",
                listOf(
                    1L to "what did i eat today?",
                    2L to "should i cut carbs?",
                    3L to "how many reps for shoulders?",
                ),
                rows
            )
        }

        db20.close()
    }

    /**
     * v22 → v23 (Frente Memory C2.a, 2026-06-29): adds four nullable /
     * defaulted columns to `user_insights` so the local knowledge-base
     * layer (InsightExtractor + InsightRecaller) can score relevance per
     * turn and decay older / unreferenced facts.
     *
     * Strictly additive — the test pre-loads a v22-shaped row and asserts
     * that ALL original columns are byte-identical after migration AND
     * that the four new columns exist with their expected defaults:
     *  - `keywords` (TEXT, default NULL)
     *  - `lastReferencedAtMs` (INTEGER, default NULL)
     *  - `mentionCount` (INTEGER NOT NULL, default 1)
     *  - `summary` (TEXT, default NULL)
     *
     * Catches any future regression where someone accidentally turns the
     * migration destructive or changes the defaults.
     */
    @Test
    fun testMigration22To23AddsKbColumnsAndPreservesData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v22_v23")
        if (dbFile.exists()) dbFile.delete()

        val db22 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db22.version = 22
        // user_insights schema at v22 — copied from v22.json. The four
        // new columns are intentionally absent so the migration is the
        // only path that can add them.
        db22.execSQL("""
            CREATE TABLE IF NOT EXISTS `user_insights` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `text` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `confidence` REAL NOT NULL,
                `source` TEXT NOT NULL,
                `sourceSessionId` INTEGER,
                `createdAtMs` INTEGER NOT NULL,
                `confirmedAtMs` INTEGER,
                `expiredAtMs` INTEGER,
                `relatedInsightIds` TEXT
            )
        """.trimIndent())
        db22.execSQL("CREATE INDEX IF NOT EXISTS index_user_insights_category ON user_insights(category)")
        db22.execSQL("CREATE INDEX IF NOT EXISTS index_user_insights_expiredAtMs ON user_insights(expiredAtMs)")

        // Two representative rows: one confirmed user_stated fact, one
        // pending aggregate_analyzer pattern. The migration must leave
        // both intact.
        db22.execSQL(
            """INSERT INTO user_insights
                (id, text, category, confidence, source, sourceSessionId,
                 createdAtMs, confirmedAtMs, expiredAtMs, relatedInsightIds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                42L, "O Hugo tem 48 anos.", "other", 1.0, "user_stated",
                12L, 1_700_000_000_000L, 1_700_000_001_000L, null, null
            )
        )
        db22.execSQL(
            """INSERT INTO user_insights
                (id, text, category, confidence, source, sourceSessionId,
                 createdAtMs, confirmedAtMs, expiredAtMs, relatedInsightIds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                43L, "Pizza em 70% dos dias acima do target.", "aggregate_pattern", 0.6,
                "aggregate_analyzer", null, 1_700_000_100_000L, null, null, "1,2,3"
            )
        )
        db22.close()

        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_v22_v23")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(23) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        if (oldVersion == 22 && newVersion == 23) {
                            ChatDatabase.MIGRATION_22_23.migrate(db)
                        }
                    }
                })
                .build()
        )
        val db23 = openHelper.writableDatabase

        // 1. Original columns survived byte-identical on row 42.
        db23.query("SELECT * FROM user_insights WHERE id = 42").use {
            assertTrue("row 42 vanished after migration", it.moveToFirst())
            assertEquals("O Hugo tem 48 anos.", it.getString(it.getColumnIndexOrThrow("text")))
            assertEquals("other", it.getString(it.getColumnIndexOrThrow("category")))
            assertEquals(1.0, it.getDouble(it.getColumnIndexOrThrow("confidence")), 0.0)
            assertEquals("user_stated", it.getString(it.getColumnIndexOrThrow("source")))
            assertEquals(12L, it.getLong(it.getColumnIndexOrThrow("sourceSessionId")))
            assertEquals(1_700_000_000_000L, it.getLong(it.getColumnIndexOrThrow("createdAtMs")))
            assertEquals(1_700_000_001_000L, it.getLong(it.getColumnIndexOrThrow("confirmedAtMs")))
            // 2. New columns exist on legacy row with their declared defaults.
            assertNull("keywords default must be NULL", it.getString(it.getColumnIndexOrThrow("keywords")))
            assertNull(
                "lastReferencedAtMs default must be NULL",
                it.getString(it.getColumnIndexOrThrow("lastReferencedAtMs"))
            )
            assertEquals(
                "mentionCount default must be 1",
                1, it.getInt(it.getColumnIndexOrThrow("mentionCount"))
            )
            assertNull("summary default must be NULL", it.getString(it.getColumnIndexOrThrow("summary")))
        }

        // 3. Row 43 (with relatedInsightIds populated) also intact.
        db23.query("SELECT relatedInsightIds, confidence FROM user_insights WHERE id = 43").use {
            assertTrue(it.moveToFirst())
            assertEquals("1,2,3", it.getString(0))
            assertEquals(0.6, it.getDouble(1), 0.0)
        }

        // 4. INSERTs that carry the new columns work.
        db23.execSQL(
            """INSERT INTO user_insights
                (text, category, confidence, source, createdAtMs,
                 keywords, lastReferencedAtMs, mentionCount, summary)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                "É intolerante a lactose.", "allergy", 0.9, "user_stated",
                1_700_000_900_000L, "intolerante,lactose", 1_700_000_950_000L, 3, "Intolerante a lactose"
            )
        )
        db23.query(
            "SELECT keywords, mentionCount, summary FROM user_insights WHERE text LIKE 'É intolerante%'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("intolerante,lactose", it.getString(0))
            assertEquals(3, it.getInt(1))
            assertEquals("Intolerante a lactose", it.getString(2))
        }

        db23.close()
    }

    /**
     * v23 → v24 (Frente C1.a rolling-window, 2026-06-29): adds a single
     * nullable `digestSummary` column to `chat_sessions` so the
     * orchestrator can persist a 1-2 sentence summary of turns that
     * dropped out of the LLM payload past the rolling cap.
     *
     * Strictly additive — every other column and row stays untouched.
     * ChatMessage rows are NEVER deleted by this migration (the rolling
     * window is purely in-memory at request-build time). The test
     * pre-loads a v23-shaped session with messages, runs the migration,
     * and asserts: (a) original columns intact, (b) new column exists
     * with default NULL, (c) chat_messages count unchanged.
     */
    @Test
    fun testMigration23To24AddsDigestSummaryAndPreservesData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v23_v24")
        if (dbFile.exists()) dbFile.delete()

        val db23 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db23.version = 23
        // chat_sessions at v23 — every prior column present except the
        // new digestSummary. Schema copied from v23.json shape.
        db23.execSQL("""
            CREATE TABLE IF NOT EXISTS `chat_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `model` TEXT NOT NULL,
                `systemInstruction` TEXT NOT NULL,
                `sessionType` TEXT NOT NULL DEFAULT 'personal',
                `lastMessageAt` INTEGER NOT NULL,
                `cachedContentName` TEXT,
                `proactiveTag` TEXT
            )
        """.trimIndent())
        // chat_messages must survive untouched — the rolling-window cap
        // is purely in-memory. The test guards against a future
        // regression where someone tries to "vacuum" the table here.
        db23.execSQL("""
            CREATE TABLE IF NOT EXISTS `chat_messages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `sender` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `isError` INTEGER NOT NULL,
                `imageBase64` TEXT,
                `editedTimestamp` INTEGER,
                FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())

        db23.execSQL(
            """INSERT INTO chat_sessions
                (id, title, createdAt, model, systemInstruction, sessionType,
                 lastMessageAt, cachedContentName, proactiveTag)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                7L, "conversa longa", 1_700_000_000_000L, "gemini-3.5-flash",
                "system text…", "personal", 1_700_001_000_000L,
                "cachedContents/abc", null
            )
        )
        // 3 messages spread across the session.
        for (i in 1..3) {
            db23.execSQL(
                """INSERT INTO chat_messages
                    (sessionId, sender, text, timestamp, isError)
                    VALUES (7, ?, ?, ?, 0)""",
                arrayOf<Any?>(
                    if (i % 2 == 1) "user" else "gemini",
                    "msg #$i",
                    1_700_000_000_000L + i * 1_000L
                )
            )
        }
        db23.close()

        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_v23_v24")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(24) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        if (oldVersion == 23 && newVersion == 24) {
                            ChatDatabase.MIGRATION_23_24.migrate(db)
                        }
                    }
                })
                .build()
        )
        val db24 = openHelper.writableDatabase

        // 1. chat_sessions row intact in every original column.
        db24.query("SELECT * FROM chat_sessions WHERE id = 7").use {
            assertTrue("chat_sessions row vanished after migration", it.moveToFirst())
            assertEquals("conversa longa", it.getString(it.getColumnIndexOrThrow("title")))
            assertEquals(1_700_000_000_000L, it.getLong(it.getColumnIndexOrThrow("createdAt")))
            assertEquals("gemini-3.5-flash", it.getString(it.getColumnIndexOrThrow("model")))
            assertEquals("personal", it.getString(it.getColumnIndexOrThrow("sessionType")))
            assertEquals(1_700_001_000_000L, it.getLong(it.getColumnIndexOrThrow("lastMessageAt")))
            assertEquals(
                "cachedContents/abc",
                it.getString(it.getColumnIndexOrThrow("cachedContentName"))
            )
            // 2. New column exists with default NULL.
            assertNull(
                "digestSummary default must be NULL",
                it.getString(it.getColumnIndexOrThrow("digestSummary"))
            )
        }

        // 3. chat_messages count unchanged — rolling-window is in-memory,
        //    NOT a DB-level prune.
        db24.query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = 7").use {
            assertTrue(it.moveToFirst())
            assertEquals(
                "rolling-window must not delete any chat_messages row",
                3, it.getInt(0)
            )
        }

        // 4. UPDATE with the new column works (orchestrator does this
        //    on every roll).
        db24.execSQL(
            "UPDATE chat_sessions SET digestSummary = ? WHERE id = 7",
            arrayOf<Any?>("[2] User talked about pizza and protein targets.")
        )
        db24.query("SELECT digestSummary FROM chat_sessions WHERE id = 7").use {
            assertTrue(it.moveToFirst())
            assertEquals("[2] User talked about pizza and protein targets.", it.getString(0))
        }

        db24.close()
    }

    /**
     * v24 → v25 (2026-07-01): adds a single nullable `freeSugarsG` REAL
     * column to `food_entries` so meal analysis can persist the WHO
     * "free sugars" breakdown that drives the dashboard's split-color
     * carbs bar and the edit-meal "Açúcar livre" line.
     *
     * Strictly additive — the test pre-loads a v24-shaped `food_entries`
     * row with every column populated, runs the migration, and asserts:
     *  1. Every original column survives byte-identical.
     *  2. New `freeSugarsG` column exists with default NULL on the legacy
     *     row.
     *  3. New rows can be inserted with an explicit freeSugarsG value AND
     *     with freeSugarsG omitted (fallback to NULL).
     *
     * Catches any future regression where someone accidentally turns the
     * migration destructive, changes the default, or picks the wrong SQL
     * type (INTEGER would silently truncate the LLM's decimal grams).
     */
    @Test
    fun testMigration24To25AddsFreeSugarsAndPreservesData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v24_v25")
        if (dbFile.exists()) dbFile.delete()

        val db24 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db24.version = 24
        // food_entries at v24 — same shape as the entity Room emitted at
        // v24 (id + all macros + ingredientsJson etc.); freeSugarsG is
        // intentionally absent so the migration is the only path that can
        // add it.
        db24.execSQL("""
            CREATE TABLE IF NOT EXISTS `food_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `calories` INTEGER NOT NULL,
                `protein` REAL NOT NULL,
                `carbs` REAL NOT NULL,
                `fat` REAL NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `imagePath` TEXT,
                `analysisSummary` TEXT,
                `fullSummary` TEXT NOT NULL,
                `lastAiAnalyzedSummary` TEXT,
                `mealCategory` TEXT NOT NULL,
                `appliedHabitIds` TEXT,
                `groupId` TEXT,
                `ingredientsJson` TEXT
            )
        """.trimIndent())

        // Pre-existing row — every LLM-populated field set so the assertion
        // is unambiguous. The `ingredientsJson` uses the same shape the
        // encoder produces so it also verifies the JSON blob survives.
        db24.execSQL(
            """INSERT INTO food_entries
                (id, name, calories, protein, carbs, fat, timestamp,
                 imagePath, analysisSummary, fullSummary, lastAiAnalyzedSummary,
                 mealCategory, appliedHabitIds, groupId, ingredientsJson)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                91L, "sumo de laranja + bolachas", 320, 4.5, 60.0, 8.0,
                1_700_000_500_000L, null,
                "sumo natural com 2 bolachas maria",
                "sumo (~200 ml), bolachas maria (~30 g)", null,
                "SNACK", null, null,
                "[{\"name\":\"sumo\",\"weightGrams\":200.0},{\"name\":\"bolacha maria\",\"weightGrams\":30.0}]"
            )
        )
        db24.close()

        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_v24_v25")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(25) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        if (oldVersion == 24 && newVersion == 25) {
                            ChatDatabase.MIGRATION_24_25.migrate(db)
                        }
                    }
                })
                .build()
        )
        val db25 = openHelper.writableDatabase

        // 1. Original columns survived byte-identical on row 91.
        db25.query("SELECT * FROM food_entries WHERE id = 91").use {
            assertTrue("row 91 vanished after migration", it.moveToFirst())
            assertEquals(
                "sumo de laranja + bolachas",
                it.getString(it.getColumnIndexOrThrow("name"))
            )
            assertEquals(320, it.getInt(it.getColumnIndexOrThrow("calories")))
            assertEquals(60.0, it.getDouble(it.getColumnIndexOrThrow("carbs")), 0.0)
            assertEquals("SNACK", it.getString(it.getColumnIndexOrThrow("mealCategory")))
            assertEquals(
                "[{\"name\":\"sumo\",\"weightGrams\":200.0},{\"name\":\"bolacha maria\",\"weightGrams\":30.0}]",
                it.getString(it.getColumnIndexOrThrow("ingredientsJson"))
            )
            // 2. New column exists on legacy row with default NULL.
            assertNull(
                "freeSugarsG default must be NULL on legacy rows",
                it.getString(it.getColumnIndexOrThrow("freeSugarsG"))
            )
        }

        // 3a. New rows can carry an explicit freeSugarsG value.
        db25.execSQL(
            """INSERT INTO food_entries
                (name, calories, protein, carbs, fat, timestamp,
                 fullSummary, mealCategory, freeSugarsG)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                "sumo fresh", 90, 1.0, 22.0, 0.5, 1_700_001_000_000L,
                "sumo de laranja natural (~200 ml)", "SNACK", 22.0
            )
        )
        db25.query(
            "SELECT freeSugarsG FROM food_entries WHERE name = 'sumo fresh'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(22.0, it.getDouble(0), 0.0)
        }

        // 3b. New rows without freeSugarsG default to NULL.
        db25.execSQL(
            """INSERT INTO food_entries
                (name, calories, protein, carbs, fat, timestamp,
                 fullSummary, mealCategory)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                "arroz integral", 220, 5.0, 45.0, 1.5, 1_700_001_500_000L,
                "chávena de arroz integral cozido", "LUNCH"
            )
        )
        db25.query(
            "SELECT freeSugarsG FROM food_entries WHERE name = 'arroz integral'"
        ).use {
            assertTrue(it.moveToFirst())
            assertTrue(
                "new row without freeSugarsG must be NULL, not 0.0",
                it.isNull(0)
            )
        }

        db25.close()
    }

    /**
     * v25 → v26 (2026-07-01): adds a single nullable `saturatedFatG` REAL
     * column to `food_entries` so meal analysis can persist the WHO/AHA
     * saturated-fat breakdown that drives the dashboard's split-color fat
     * bar (yellow-500 unsat + yellow-200 sat tail) and the two-row split
     * on the Análise → Alimentação card.
     *
     * Strictly additive — mirrors the v24→v25 shape: legacy row survives
     * with the new column defaulting to NULL, new rows can carry either
     * an explicit value or omit it (defaults to NULL).
     */
    @Test
    fun testMigration25To26AddsSaturatedFatAndPreservesData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v25_v26")
        if (dbFile.exists()) dbFile.delete()

        val db25 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db25.version = 25
        // food_entries at v25 — includes freeSugarsG (added by v24→v25) so
        // the schema before the new migration matches what production has.
        db25.execSQL("""
            CREATE TABLE IF NOT EXISTS `food_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `calories` INTEGER NOT NULL,
                `protein` REAL NOT NULL,
                `carbs` REAL NOT NULL,
                `fat` REAL NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `imagePath` TEXT,
                `analysisSummary` TEXT,
                `fullSummary` TEXT NOT NULL,
                `lastAiAnalyzedSummary` TEXT,
                `mealCategory` TEXT NOT NULL,
                `appliedHabitIds` TEXT,
                `groupId` TEXT,
                `ingredientsJson` TEXT,
                `freeSugarsG` REAL
            )
        """.trimIndent())

        // Pre-existing row: cheese + bacon toastie already carrying a
        // freeSugarsG (to prove we don't touch it) but no saturatedFatG.
        db25.execSQL(
            """INSERT INTO food_entries
                (id, name, calories, protein, carbs, fat, timestamp,
                 imagePath, analysisSummary, fullSummary, lastAiAnalyzedSummary,
                 mealCategory, appliedHabitIds, groupId, ingredientsJson, freeSugarsG)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                101L, "tosta mista", 520, 26.0, 40.0, 24.0,
                1_700_002_000_000L, null,
                "tosta com queijo e fiambre",
                "pão de forma, queijo flamengo, fiambre", null,
                "LUNCH", null, null,
                "[{\"name\":\"queijo\",\"weightGrams\":30.0,\"fat\":9.0,\"saturatedFatG\":5.5}]",
                2.0
            )
        )
        db25.close()

        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_v25_v26")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(26) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        if (oldVersion == 25 && newVersion == 26) {
                            ChatDatabase.MIGRATION_25_26.migrate(db)
                        }
                    }
                })
                .build()
        )
        val db26 = openHelper.writableDatabase

        // 1. Original columns survived byte-identical on row 101.
        db26.query("SELECT * FROM food_entries WHERE id = 101").use {
            assertTrue("row 101 vanished after migration", it.moveToFirst())
            assertEquals("tosta mista", it.getString(it.getColumnIndexOrThrow("name")))
            assertEquals(520, it.getInt(it.getColumnIndexOrThrow("calories")))
            assertEquals(24.0, it.getDouble(it.getColumnIndexOrThrow("fat")), 0.0)
            assertEquals(2.0, it.getDouble(it.getColumnIndexOrThrow("freeSugarsG")), 0.0)
            // 2. New column exists on legacy row with default NULL.
            assertNull(
                "saturatedFatG default must be NULL on legacy rows",
                it.getString(it.getColumnIndexOrThrow("saturatedFatG"))
            )
        }

        // 3a. New rows can carry an explicit saturatedFatG value.
        db26.execSQL(
            """INSERT INTO food_entries
                (name, calories, protein, carbs, fat, timestamp,
                 fullSummary, mealCategory, saturatedFatG)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                "bacon com ovo", 380, 22.0, 1.5, 30.0, 1_700_002_500_000L,
                "2 ovos estrelados com 4 tiras de bacon", "BREAKFAST", 11.0
            )
        )
        db26.query(
            "SELECT saturatedFatG FROM food_entries WHERE name = 'bacon com ovo'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(11.0, it.getDouble(0), 0.0)
        }

        // 3b. New rows without saturatedFatG default to NULL.
        db26.execSQL(
            """INSERT INTO food_entries
                (name, calories, protein, carbs, fat, timestamp,
                 fullSummary, mealCategory)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                "salada de atum", 240, 22.0, 8.0, 12.0, 1_700_003_000_000L,
                "atum + alface + azeite", "LUNCH"
            )
        )
        db26.query(
            "SELECT saturatedFatG FROM food_entries WHERE name = 'salada de atum'"
        ).use {
            assertTrue(it.moveToFirst())
            assertTrue(
                "new row without saturatedFatG must be NULL, not 0.0",
                it.isNull(0)
            )
        }

        db26.close()
    }

    /**
     * v26 → v27: adds `activities.sourceLabel TEXT NULL`. Legacy rows must
     * keep NULL and new rows can carry the fine-grained modality string
     * (e.g. "Pilates"). See migration `ChatDatabase.MIGRATION_26_27` and
     * memory [[samsung-health-exercise-type-mapping]].
     */
    @Test
    fun testMigration26To27AddsSourceLabelAndPreservesData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v26_v27")
        if (dbFile.exists()) dbFile.delete()

        val db26 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db26.version = 26
        // activities table as it exists at v26 (post-shRecordId, pre-sourceLabel).
        db26.execSQL("""
            CREATE TABLE IF NOT EXISTS `activities` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `durationMinutes` INTEGER NOT NULL,
                `caloriesBurned` INTEGER NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `sessionId` TEXT NOT NULL,
                `fullSummary` TEXT NOT NULL,
                `lastAiAnalyzedSummary` TEXT,
                `activityType` TEXT NOT NULL,
                `notes` TEXT,
                `shRecordId` TEXT
            )
        """.trimIndent())
        // Pre-existing row that must survive the migration byte-identical
        // (except for the new column defaulting to NULL).
        db26.execSQL(
            """INSERT INTO activities
                (id, name, durationMinutes, caloriesBurned, timestamp,
                 sessionId, fullSummary, lastAiAnalyzedSummary, activityType,
                 notes, shRecordId)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                42L, "Corrida na marginal", 30, 320,
                1_700_003_000_000L, "chat:99",
                "30 min de corrida moderada", null,
                "RUNNING", null, null
            )
        )
        db26.close()

        val openHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_v26_v27")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(27) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        if (oldVersion == 26 && newVersion == 27) {
                            ChatDatabase.MIGRATION_26_27.migrate(db)
                        }
                    }
                })
                .build()
        )
        val db27 = openHelper.writableDatabase

        // 1. Original row survived.
        db27.query("SELECT * FROM activities WHERE id = 42").use {
            assertTrue("row 42 vanished after migration", it.moveToFirst())
            assertEquals("Corrida na marginal", it.getString(it.getColumnIndexOrThrow("name")))
            assertEquals(30, it.getInt(it.getColumnIndexOrThrow("durationMinutes")))
            assertEquals("RUNNING", it.getString(it.getColumnIndexOrThrow("activityType")))
            // 2. New column exists on legacy row with default NULL.
            assertTrue(
                "sourceLabel default must be NULL on legacy rows",
                it.isNull(it.getColumnIndexOrThrow("sourceLabel"))
            )
        }

        // 3a. New row can carry a fine-grained sourceLabel.
        db27.execSQL(
            """INSERT INTO activities
                (name, durationMinutes, caloriesBurned, timestamp,
                 sessionId, fullSummary, activityType, sourceLabel)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                "Pilates", 30, 150, 1_700_004_000_000L, "chat:100",
                "30 min de pilates", "YOGA", "Pilates"
            )
        )
        db27.query(
            "SELECT sourceLabel FROM activities WHERE name = 'Pilates'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("Pilates", it.getString(0))
        }

        // 3b. New rows without sourceLabel default to NULL.
        db27.execSQL(
            """INSERT INTO activities
                (name, durationMinutes, caloriesBurned, timestamp,
                 sessionId, fullSummary, activityType)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                "Caminhada rápida", 20, 80, 1_700_005_000_000L, "",
                "walked to the store", "WALKING"
            )
        )
        db27.query(
            "SELECT sourceLabel FROM activities WHERE name = 'Caminhada rápida'"
        ).use {
            assertTrue(it.moveToFirst())
            assertTrue(
                "new row without sourceLabel must be NULL",
                it.isNull(0)
            )
        }

        db27.close()
    }

    /**
     * v32 → v33 (reconcile branch): re-mapping `IngredientNutritionEntity`
     * after the ensemble worktree left the table UNMAPPED bumped the Room
     * identity hash, so the version had to increment to 33. This is the
     * upgrade path for a v32 device that NEVER had the table (a build that
     * skipped the verbatim-ported v28→v29 DDL). `MIGRATION_32_33` must:
     *  - CREATE the `ingredient_nutrition_cache` table + its unique index,
     *  - add the `editLogJson` / `analysisModel` columns idempotently,
     *  - preserve every existing `food_entries` row byte-for-byte,
     *  - land the DB at user_version = 33.
     */
    @Test
    fun testMigration32To33CreatesIngredientCacheWhenAbsent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v32_v33_absent")
        if (dbFile.exists()) dbFile.delete()

        val db32 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db32.version = 32
        // Minimal food_entries at the v32 shape (satFat + freeSugars present,
        // but NO analysisModel / editLogJson yet — this is a v32 that never
        // saw the reconcile columns).
        db32.execSQL("""
            CREATE TABLE IF NOT EXISTS `food_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `calories` INTEGER NOT NULL,
                `protein` REAL NOT NULL,
                `carbs` REAL NOT NULL,
                `fat` REAL NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `imagePath` TEXT,
                `analysisSummary` TEXT,
                `fullSummary` TEXT NOT NULL,
                `lastAiAnalyzedSummary` TEXT,
                `mealCategory` TEXT NOT NULL,
                `appliedHabitIds` TEXT,
                `groupId` TEXT,
                `ingredientsJson` TEXT,
                `freeSugarsG` REAL,
                `saturatedFatG` REAL
            )
        """.trimIndent())
        // chat_messages is a core table on every real v32 device; the
        // migration's addColumnIfMissing("chat_messages", "analysisModel")
        // requires it to exist (ALTER TABLE cannot target a missing table).
        db32.execSQL("""
            CREATE TABLE IF NOT EXISTS `chat_messages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `text` TEXT NOT NULL,
                `isUser` INTEGER NOT NULL,
                `timestamp` INTEGER NOT NULL
            )
        """.trimIndent())
        db32.execSQL(
            """INSERT INTO food_entries
                (id, name, calories, protein, carbs, fat, timestamp,
                 fullSummary, mealCategory, freeSugarsG, saturatedFatG)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                301L, "arroz de pato", 640, 32.0, 70.0, 22.0,
                1_700_010_000_000L, "arroz de pato no forno", "LUNCH", 1.0, 6.5
            )
        )
        db32.close()

        val db33 = openWithMigration("test_migration_v32_v33_absent", 33) { db, oldV, newV ->
            if (oldV == 32 && newV == 33) ChatDatabase.MIGRATION_32_33.migrate(db)
        }

        assertIngredientCacheAndColumns(db33)

        // Seeded row survives byte-identical.
        db33.query("SELECT * FROM food_entries WHERE id = 301").use {
            assertTrue("row 301 vanished after migration", it.moveToFirst())
            assertEquals("arroz de pato", it.getString(it.getColumnIndexOrThrow("name")))
            assertEquals(640, it.getInt(it.getColumnIndexOrThrow("calories")))
            assertEquals(6.5, it.getDouble(it.getColumnIndexOrThrow("saturatedFatG")), 0.0)
            // New columns exist and default NULL on the legacy row.
            assertTrue(it.isNull(it.getColumnIndexOrThrow("analysisModel")))
            assertTrue(it.isNull(it.getColumnIndexOrThrow("editLogJson")))
        }
        db33.close()
    }

    /**
     * v32 → v33, the LIVE Pixel shape: the device already physically carries
     * `ingredient_nutrition_cache` (created by the verbatim-ported v28→v29
     * DDL, then left unmapped). `MIGRATION_32_33`'s `CREATE TABLE IF NOT
     * EXISTS` / `CREATE UNIQUE INDEX IF NOT EXISTS` must be a safe no-op here
     * — the migration must NOT throw, must NOT drop the table's existing
     * rows, and must still add the analysisModel/editLogJson columns.
     */
    @Test
    fun testMigration32To33IsNoOpWhenIngredientCachePresent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v32_v33_present")
        if (dbFile.exists()) dbFile.delete()

        val db32 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db32.version = 32
        db32.execSQL("""
            CREATE TABLE IF NOT EXISTS `food_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `calories` INTEGER NOT NULL,
                `protein` REAL NOT NULL,
                `carbs` REAL NOT NULL,
                `fat` REAL NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `imagePath` TEXT,
                `analysisSummary` TEXT,
                `fullSummary` TEXT NOT NULL,
                `lastAiAnalyzedSummary` TEXT,
                `mealCategory` TEXT NOT NULL,
                `appliedHabitIds` TEXT,
                `groupId` TEXT,
                `ingredientsJson` TEXT,
                `freeSugarsG` REAL,
                `saturatedFatG` REAL
            )
        """.trimIndent())
        // chat_messages is a core table on every real v32 device; the
        // migration's addColumnIfMissing("chat_messages", "analysisModel")
        // requires it to exist (ALTER TABLE cannot target a missing table).
        db32.execSQL("""
            CREATE TABLE IF NOT EXISTS `chat_messages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `text` TEXT NOT NULL,
                `isUser` INTEGER NOT NULL,
                `timestamp` INTEGER NOT NULL
            )
        """.trimIndent())
        // Live device carries the table already (unmapped) — replicate its
        // exact DDL (same as MIGRATION_28_29) and seed a cached ingredient.
        db32.execSQL("""
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
        """.trimIndent())
        db32.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_ingredient_nutrition_cache_normalizedName` ON `ingredient_nutrition_cache` (`normalizedName`)"
        )
        db32.execSQL(
            """INSERT INTO ingredient_nutrition_cache
                (id, normalizedName, displayName, per100gCalories, per100gProtein,
                 per100gCarbs, per100gFat, per100gSatFat, per100gFreeSugars,
                 source, confidence, createdAtMs, updatedAtMs, timesUsed)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                1L, "arroz", "Arroz cozido", 130.0, 2.7, 28.0, 0.3, 0.1, 0.0,
                "insa", "HIGH", 1_700_000_000_000L, 1_700_000_000_000L, 4L
            )
        )
        db32.execSQL(
            """INSERT INTO food_entries
                (id, name, calories, protein, carbs, fat, timestamp,
                 fullSummary, mealCategory)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                401L, "frango grelhado", 330, 40.0, 0.0, 18.0,
                1_700_011_000_000L, "peito de frango grelhado", "DINNER"
            )
        )
        db32.close()

        val db33 = openWithMigration("test_migration_v32_v33_present", 33) { db, oldV, newV ->
            if (oldV == 32 && newV == 33) ChatDatabase.MIGRATION_32_33.migrate(db)
        }

        assertIngredientCacheAndColumns(db33)

        // The pre-existing cached ingredient must SURVIVE (IF NOT EXISTS must
        // not have dropped/recreated the table).
        db33.query("SELECT displayName, timesUsed FROM ingredient_nutrition_cache WHERE normalizedName = 'arroz'").use {
            assertTrue("cached ingredient vanished — table was recreated!", it.moveToFirst())
            assertEquals("Arroz cozido", it.getString(0))
            assertEquals(4, it.getInt(1))
        }
        db33.query("SELECT name FROM food_entries WHERE id = 401").use {
            assertTrue("row 401 vanished after migration", it.moveToFirst())
            assertEquals("frango grelhado", it.getString(0))
        }
        db33.close()
    }

    /**
     * v33 → v34: adds the append-only `meal_corrections` table. Purely
     * additive `CREATE TABLE IF NOT EXISTS` + index — no existing table is
     * touched. The test pre-loads a v33-shaped `food_entries` row, runs the
     * migration, and asserts:
     *  1. The pre-existing food row survives byte-identical.
     *  2. `meal_corrections` exists, starts empty, and its index is present.
     *  3. A correction row can be inserted (schema reachable from outside).
     *  4. The DB lands at user_version = 34.
     */
    @Test
    fun testMigration33To34AddsMealCorrectionsAndPreservesData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v33_v34")
        if (dbFile.exists()) dbFile.delete()

        val db33 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db33.version = 33
        db33.execSQL("""
            CREATE TABLE IF NOT EXISTS `food_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `calories` INTEGER NOT NULL,
                `protein` REAL NOT NULL,
                `carbs` REAL NOT NULL,
                `fat` REAL NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `imagePath` TEXT,
                `analysisSummary` TEXT,
                `fullSummary` TEXT NOT NULL,
                `lastAiAnalyzedSummary` TEXT,
                `mealCategory` TEXT NOT NULL,
                `appliedHabitIds` TEXT,
                `groupId` TEXT,
                `ingredientsJson` TEXT,
                `freeSugarsG` REAL,
                `saturatedFatG` REAL,
                `editLogJson` TEXT,
                `analysisModel` TEXT
            )
        """.trimIndent())
        db33.execSQL(
            """INSERT INTO food_entries
                (id, name, calories, protein, carbs, fat, timestamp,
                 fullSummary, mealCategory)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                501L, "caril de peixe com arroz", 590, 34.0, 62.0, 18.0,
                1_700_020_000_000L, "caril de peixe com arroz basmati", "LUNCH"
            )
        )
        db33.close()

        val db34 = openWithMigration("test_migration_v33_v34", 34) { db, oldV, newV ->
            if (oldV == 33 && newV == 34) ChatDatabase.MIGRATION_33_34.migrate(db)
        }

        // 1. Pre-existing food row survives.
        db34.query("SELECT name, calories FROM food_entries WHERE id = 501").use {
            assertTrue("food_entries row vanished after migration", it.moveToFirst())
            assertEquals("caril de peixe com arroz", it.getString(0))
            assertEquals(590, it.getInt(1))
        }

        // 2. meal_corrections exists, empty, indexed.
        db34.query("SELECT COUNT(*) FROM meal_corrections").use {
            assertTrue(it.moveToFirst())
            assertEquals("meal_corrections must start empty", 0, it.getInt(0))
        }
        db34.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_meal_corrections_createdAtMs'"
        ).use {
            assertTrue("createdAtMs index missing", it.moveToFirst())
        }

        // 3. INSERT works (schema reachable).
        db34.execSQL(
            """INSERT INTO meal_corrections
                (createdAtMs, mealCategory, model, initialConfidence,
                 aiName, userName, aiCalories, userCalories,
                 aiProtein, userProtein, aiCarbs, userCarbs, aiFat, userFat,
                 aiIngredientsJson, userIngredientsJson)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                1_700_020_500_000L, "LUNCH", "gemini-3.6-flash", "medium",
                "peixe assado", "caril de peixe", 720, 590,
                40.0, 34.0, 50.0, 62.0, 30.0, 18.0,
                "[{\"name\":\"peixe\",\"weightGrams\":180.0}]",
                "[{\"name\":\"peixe\",\"weightGrams\":150.0}]"
            )
        )
        db34.query("SELECT userName, userCalories FROM meal_corrections").use {
            assertTrue(it.moveToFirst())
            assertEquals("caril de peixe", it.getString(0))
            assertEquals(590, it.getInt(1))
        }

        // 4. DB lands at user_version 34.
        db34.query("PRAGMA user_version").use {
            assertTrue(it.moveToFirst())
            assertEquals("DB must end at user_version 34", 34, it.getInt(0))
        }
        db34.close()
    }

    /**
     * v38 → v39: adds the append-only `body_plan_checkpoints` table + index
     * and the nullable `silhouetteNormalization` column on
     * `performance_snapshots`. Strictly additive — no existing table is
     * rewritten. The test pre-loads v38-shaped performance data (a silhouette
     * snapshot with ratios, photo paths and a persisted coach report; a goal
     * with coach targets; a food row with the leftovers snapshot column) and
     * asserts:
     *  1. Every pre-existing row survives byte-identical.
     *  2. `body_plan_checkpoints` exists, starts empty, is indexed, and
     *     accepts INSERTs (schema reachable from outside).
     *  3. `silhouetteNormalization` defaults to NULL on legacy snapshots.
     *  4. The DB lands at user_version = 39.
     */
    @Test
    fun testMigration38To39AddsBodyPlanCheckpointsAndPreservesData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v38_v39")
        if (dbFile.exists()) dbFile.delete()

        val db38 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db38.version = 38
        // performance_snapshots at v38 — v35 angles + v36 silhouette columns.
        // silhouetteNormalization (v39) is intentionally absent so the
        // migration is the only path that can add it.
        db38.execSQL("""
            CREATE TABLE IF NOT EXISTS `performance_snapshots` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `capturedAtMs` INTEGER NOT NULL,
                `source` TEXT NOT NULL DEFAULT 'manual',
                `notes` TEXT,
                `leftShoulderSagittalDeg` REAL,
                `leftShoulderFrontalDeg` REAL,
                `rightShoulderSagittalDeg` REAL,
                `rightShoulderFrontalDeg` REAL,
                `leftHipSagittalDeg` REAL,
                `leftHipFrontalDeg` REAL,
                `rightHipSagittalDeg` REAL,
                `rightHipFrontalDeg` REAL,
                `leftElbowSagittalDeg` REAL,
                `rightElbowSagittalDeg` REAL,
                `leftKneeSagittalDeg` REAL,
                `rightKneeSagittalDeg` REAL,
                `frontalImagePath` TEXT,
                `lateralImagePath` TEXT,
                `frontalShoulderRatio` REAL,
                `frontalWaistRatio` REAL,
                `frontalHipRatio` REAL,
                `frontalThighRatio` REAL,
                `lateralChestRatio` REAL,
                `lateralBellyRatio` REAL,
                `lateralThighRatio` REAL,
                `coachReport` TEXT,
                `reportGeneratedAtMs` INTEGER
            )
        """.trimIndent())
        db38.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_performance_snapshots_capturedAtMs` " +
                "ON `performance_snapshots` (`capturedAtMs`)"
        )
        // performance_goals at v37.
        db38.execSQL("""
            CREATE TABLE IF NOT EXISTS `performance_goals` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `createdAtMs` INTEGER NOT NULL,
                `goalText` TEXT NOT NULL,
                `coachTargetJson` TEXT,
                `targetsGeneratedAtMs` INTEGER
            )
        """.trimIndent())
        // food_entries at v38 (leftoverOriginalJson present, v39 untouched).
        db38.execSQL("""
            CREATE TABLE IF NOT EXISTS `food_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `calories` INTEGER NOT NULL,
                `protein` REAL NOT NULL,
                `carbs` REAL NOT NULL,
                `fat` REAL NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `imagePath` TEXT,
                `analysisSummary` TEXT,
                `fullSummary` TEXT NOT NULL,
                `lastAiAnalyzedSummary` TEXT,
                `mealCategory` TEXT NOT NULL,
                `appliedHabitIds` TEXT,
                `groupId` TEXT,
                `ingredientsJson` TEXT,
                `freeSugarsG` REAL,
                `saturatedFatG` REAL,
                `editLogJson` TEXT,
                `analysisModel` TEXT,
                `leftoverOriginalJson` TEXT
            )
        """.trimIndent())

        // Representative performance rows that must NOT disappear on update.
        db38.execSQL(
            """INSERT INTO performance_snapshots
                (id, capturedAtMs, source, frontalImagePath, lateralImagePath,
                 frontalShoulderRatio, frontalWaistRatio, frontalHipRatio,
                 frontalThighRatio, lateralChestRatio, lateralBellyRatio,
                 lateralThighRatio, coachReport, reportGeneratedAtMs)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                11L, 1_700_030_000_000L, "manual",
                "/data/user/0/com.aistudio.geminichat.rvqzmx/files/performance_photos/abc.jpg",
                "/data/user/0/com.aistudio.geminichat.rvqzmx/files/performance_photos/def.jpg",
                0.38f, 0.24f, 0.31f, 0.21f, 0.20f, 0.19f, 0.18f,
                "Distribuição equilibrada; cintura ligeiramente acima do alvo.",
                1_700_030_010_000L
            )
        )
        db38.execSQL(
            """INSERT INTO performance_goals
                (id, createdAtMs, goalText, coachTargetJson, targetsGeneratedAtMs)
                VALUES (?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                5L, 1_700_029_000_000L, "reduzir barriga e ganhar ombros",
                """{"shoulder":0.40,"waist":0.23,"hip":0.31,"thigh":0.20,"chest":0.22,"belly":0.18,"lateralThigh":0.19}""",
                1_700_029_010_000L
            )
        )
        db38.execSQL(
            """INSERT INTO food_entries
                (id, name, calories, protein, carbs, fat, timestamp,
                 fullSummary, mealCategory, leftoverOriginalJson)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                601L, "almôndegas com massa", 610, 38.0, 55.0, 22.0,
                1_700_031_000_000L, "almôndegas (~120 g), massa (~150 g)",
                "LUNCH", """{"calories":720,"protein":42.0,"carbs":60.0,"fat":26.0}"""
            )
        )
        db38.close()

        val db39 = openWithMigration("test_migration_v38_v39", 39) { db, oldV, newV ->
            if (oldV == 38 && newV == 39) ChatDatabase.MIGRATION_38_39.migrate(db)
        }

        // 1. Performance snapshot survives byte-identical (ratios + photo
        //    paths + persisted coach report), and the new column is NULL.
        db39.query(
            "SELECT source, frontalWaistRatio, frontalImagePath, coachReport, silhouetteNormalization " +
                "FROM performance_snapshots WHERE id = 11"
        ).use {
            assertTrue("snapshot row vanished after migration", it.moveToFirst())
            assertEquals("manual", it.getString(0))
            assertEquals(0.24f, it.getFloat(1), 0.0f)
            assertTrue(it.getString(2).endsWith("/performance_photos/abc.jpg"))
            assertEquals("Distribuição equilibrada; cintura ligeiramente acima do alvo.", it.getString(3))
            assertNull("silhouetteNormalization must default to NULL on legacy rows", it.getString(4))
        }
        // 2. Goal + coach targets survive.
        db39.query(
            "SELECT goalText, coachTargetJson FROM performance_goals WHERE id = 5"
        ).use {
            assertTrue("goal row vanished after migration", it.moveToFirst())
            assertEquals("reduzir barriga e ganhar ombros", it.getString(0))
            assertTrue(it.getString(1).contains("\"waist\":0.23"))
        }
        // 3. Food row (with the v38 leftovers column) survives.
        db39.query("SELECT name, calories, leftoverOriginalJson FROM food_entries WHERE id = 601").use {
            assertTrue("food row vanished after migration", it.moveToFirst())
            assertEquals("almôndegas com massa", it.getString(0))
            assertEquals(610, it.getInt(1))
            assertTrue(it.getString(2).contains("\"protein\":42.0"))
        }
        // 4. body_plan_checkpoints exists, is empty, is indexed, and is writable.
        db39.query("SELECT COUNT(*) FROM body_plan_checkpoints").use {
            assertTrue(it.moveToFirst())
            assertEquals("body_plan_checkpoints must start empty", 0, it.getInt(0))
        }
        db39.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_body_plan_checkpoints_checkpointAtMs'"
        ).use {
            assertTrue("checkpointAtMs index missing", it.moveToFirst())
        }
        db39.execSQL(
            """INSERT INTO body_plan_checkpoints
                (checkpointAtMs, snapshotId, phase, prioritiesJson, tacticSource, deltaSummary)
                VALUES (?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                1_700_040_000_000L, 11L, "CUT",
                """[{"zone":"belly","direction":"REDUCE","gapPp":1.5,"tissue":"FAT_DOMINANT"}]""",
                "deterministic_fallback", "cintura −0.3pp"
            )
        )
        db39.query("SELECT phase, tacticSource FROM body_plan_checkpoints").use {
            assertTrue(it.moveToFirst())
            assertEquals("CUT", it.getString(0))
            assertEquals("deterministic_fallback", it.getString(1))
        }
        // 5. Legacy snapshots can carry the new normalization marker.
        db39.execSQL(
            "UPDATE performance_snapshots SET silhouetteNormalization = 'pose_person_height' WHERE id = 11"
        )
        db39.query("SELECT silhouetteNormalization FROM performance_snapshots WHERE id = 11").use {
            assertTrue(it.moveToFirst())
            assertEquals("pose_person_height", it.getString(0))
        }
        // 6. DB lands at user_version 39.
        db39.query("PRAGMA user_version").use {
            assertTrue(it.moveToFirst())
            assertEquals("DB must end at user_version 39", 39, it.getInt(0))
        }
        db39.close()
    }

    /** Shared assertions for the v33 end state: table + unique index exist,
     *  the reconcile columns exist, and user_version == 33. */
    private fun assertIngredientCacheAndColumns(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        // Table exists.
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='ingredient_nutrition_cache'"
        ).use {
            assertTrue("ingredient_nutrition_cache table missing after v33", it.moveToFirst())
        }
        // Unique index exists.
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_ingredient_nutrition_cache_normalizedName'"
        ).use {
            assertTrue("unique index missing after v33", it.moveToFirst())
        }
        // Reconcile columns present on food_entries.
        val foodCols = mutableSetOf<String>()
        db.query("PRAGMA table_info(food_entries)").use {
            val nameIdx = it.getColumnIndexOrThrow("name")
            while (it.moveToNext()) foodCols += it.getString(nameIdx)
        }
        assertTrue("analysisModel column missing on food_entries", "analysisModel" in foodCols)
        assertTrue("editLogJson column missing on food_entries", "editLogJson" in foodCols)
        // analysisModel must also land on chat_messages (both fixtures create
        // that table, matching the live device where it always exists).
        val chatCols = mutableSetOf<String>()
        db.query("PRAGMA table_info(chat_messages)").use {
            val nameIdx = it.getColumnIndexOrThrow("name")
            while (it.moveToNext()) chatCols += it.getString(nameIdx)
        }
        assertTrue("analysisModel column missing on chat_messages", "analysisModel" in chatCols)
        db.query("PRAGMA user_version").use {
            assertTrue(it.moveToFirst())
            assertEquals("DB must end at user_version 33", 33, it.getInt(0))
        }
    }

    /**
     * v40 additive migration (review item 3/6): per-side normalization
     * markers + checkpoint-anchored goal fields + the UNIQUE one-checkpoint-
     * per-snapshot index. Existing v39 rows must survive byte-identical and
     * the new columns must default to NULL.
     */
    @Test
    fun testMigration39To40AddsNormalizationAndGoalFieldsPreservesData() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("test_migration_v39_v40")
        if (dbFile.exists()) dbFile.delete()

        val db39 = SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null)
        db39.version = 39
        // performance_snapshots at v39 — v36 columns + silhouetteNormalization.
        db39.execSQL("""
            CREATE TABLE IF NOT EXISTS `performance_snapshots` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `capturedAtMs` INTEGER NOT NULL,
                `source` TEXT NOT NULL DEFAULT 'manual',
                `notes` TEXT,
                `frontalShoulderRatio` REAL,
                `frontalWaistRatio` REAL,
                `frontalHipRatio` REAL,
                `frontalThighRatio` REAL,
                `lateralChestRatio` REAL,
                `lateralBellyRatio` REAL,
                `lateralThighRatio` REAL,
                `frontalImagePath` TEXT,
                `lateralImagePath` TEXT,
                `coachReport` TEXT,
                `reportGeneratedAtMs` INTEGER,
                `silhouetteNormalization` TEXT DEFAULT NULL
            )
        """.trimIndent())
        // body_plan_checkpoints at v39 — no goalText/goalTargetsJson, no
        // unique snapshotId index (v40 adds both).
        db39.execSQL("""
            CREATE TABLE IF NOT EXISTS `body_plan_checkpoints` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `checkpointAtMs` INTEGER NOT NULL,
                `snapshotId` INTEGER,
                `bodyCompRowId` INTEGER,
                `phase` TEXT,
                `prioritiesJson` TEXT,
                `tacticsJson` TEXT,
                `tacticSource` TEXT,
                `deltaSummary` TEXT,
                `coachReport` TEXT,
                `reportGeneratedAtMs` INTEGER
            )
        """.trimIndent())
        db39.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_body_plan_checkpoints_checkpointAtMs` " +
                "ON `body_plan_checkpoints` (`checkpointAtMs`)"
        )

        // v39 rows that must survive: one capture whose marker is a single
        // shared value (the v39 "lie" being fixed), and one checkpoint
        // WITHOUT a snapshot (NULL snapshotId must stay valid after the
        // unique index lands).
        db39.execSQL(
            """INSERT INTO performance_snapshots
                (id, capturedAtMs, source, frontalWaistRatio, lateralBellyRatio,
                 silhouetteNormalization, coachReport)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                21L, 1_700_500_000_000L, "manual",
                0.24f, 0.19f, "image_height",
                "relatório v39",
            )
        )
        db39.execSQL(
            """INSERT INTO body_plan_checkpoints
                (id, checkpointAtMs, snapshotId, phase, prioritiesJson,
                 tacticSource, deltaSummary)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                31L, 1_700_500_100_000L, 21L, "CUT",
                """[{"zone":"waist","direction":"REDUCE","gapPp":1.8,"tissue":"FAT_DOMINANT"}]""",
                "deterministic_fallback", "cintura −0.3pp",
            )
        )
        db39.execSQL(
            """INSERT INTO body_plan_checkpoints
                (id, checkpointAtMs, snapshotId, phase, prioritiesJson)
                VALUES (?, ?, NULL, 'RECOMP', ?)""",
            arrayOf<Any?>(1_700_500_200_000L, """[{"zone":"chest","direction":"GROW","gapPp":-1.1,"tissue":"MUSCLE_DOMINANT"}]""")
        )
        db39.close()

        val db40 = openWithMigration("test_migration_v39_v40", 40) { db, oldV, newV ->
            if (oldV == 39 && newV == 40) ChatDatabase.MIGRATION_39_40.migrate(db)
        }
        db40.query("PRAGMA user_version").use {
            assertTrue(it.moveToFirst())
            assertEquals("DB must end at user_version 40", 40, it.getInt(0))
        }

        // 1. Snapshot row survives; legacy marker intact; new per-side
        //    columns default to NULL (no rewrite of existing rows).
        db40.query(
            "SELECT silhouetteNormalization, frontalNormalization, lateralNormalization, frontalWaistRatio " +
                "FROM performance_snapshots WHERE id = 21"
        ).use {
            assertTrue("snapshot row vanished after v39→v40", it.moveToFirst())
            assertEquals("image_height", it.getString(0))
            assertNull("frontalNormalization must default NULL on v39 rows", it.getString(1))
            assertNull("lateralNormalization must default NULL on v39 rows", it.getString(2))
            assertEquals(0.24f, it.getFloat(3), 0.0f)
        }

        // 2. Both v39 checkpoints survive, including the snapshot-less one.
        db40.query(
            "SELECT id, snapshotId, phase, deltaSummary, goalText, goalTargetsJson " +
                "FROM body_plan_checkpoints WHERE id = 31"
        ).use {
            assertTrue("anchored checkpoint vanished after v39→v40", it.moveToFirst())
            assertEquals(31L, it.getLong(0))
            assertEquals(21L, it.getLong(1))
            assertEquals("CUT", it.getString(2))
            assertEquals("cintura −0.3pp", it.getString(3))
            assertNull("goalText must default NULL on v39 checkpoints", it.getString(4))
            assertNull("goalTargetsJson must default NULL on v39 checkpoints", it.getString(5))
        }
        db40.query("SELECT id, snapshotId, phase FROM body_plan_checkpoints WHERE phase='RECOMP'").use {
            assertTrue("snapshot-less checkpoint vanished", it.moveToFirst())
            assertTrue("snapshot-less checkpoint must keep NULL snapshotId", it.isNull(1))
        }

        // 3. The unique index exists and actually rejects a duplicate
        //    snapshotId while still allowing NULLs (SQLite: NULLs distinct).
        db40.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_body_plan_checkpoints_snapshotId'"
        ).use {
            assertTrue("unique snapshotId index missing", it.moveToFirst())
        }
        var duplicateRejected = false
        try {
            db40.execSQL(
                "INSERT INTO body_plan_checkpoints (checkpointAtMs, snapshotId, phase) VALUES (?, ?, 'CUT')",
                arrayOf<Any?>(1_700_501_000_000L, 21L),
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            duplicateRejected = true
        }
        assertTrue("a second checkpoint for the same snapshot must be rejected", duplicateRejected)
        // A second NULL-snapshotId row must still be allowed.
        db40.execSQL(
            "INSERT INTO body_plan_checkpoints (checkpointAtMs, phase) VALUES (?, 'MAINTAIN')",
            arrayOf<Any?>(1_700_502_000_000L),
        )
        db40.query("SELECT COUNT(*) FROM body_plan_checkpoints WHERE snapshotId IS NULL").use {
            assertTrue(it.moveToFirst())
            assertEquals(2, it.getInt(0))
        }

        // 4. New columns are writable (the app path from v40 onwards).
        db40.execSQL(
            "UPDATE performance_snapshots SET frontalNormalization=?, lateralNormalization=? WHERE id=21",
            arrayOf<Any?>("pose_person_height", "image_height"),
        )
        db40.query("SELECT frontalNormalization, lateralNormalization FROM performance_snapshots WHERE id=21").use {
            assertTrue(it.moveToFirst())
            // Per-side conventions may legitimately DIFFER in one row.
            assertEquals("pose_person_height", it.getString(0))
            assertEquals("image_height", it.getString(1))
        }
        db40.execSQL(
            "UPDATE body_plan_checkpoints SET goalText=?, goalTargetsJson=? WHERE id=31",
            arrayOf<Any?>("reduzir cintura", """{"waist":0.23}"""),
        )
        db40.query("SELECT goalText, goalTargetsJson FROM body_plan_checkpoints WHERE id=31").use {
            assertTrue(it.moveToFirst())
            assertEquals("reduzir cintura", it.getString(0))
            assertTrue(it.getString(1).contains("""{"waist":0.23}"""))
        }
    }

    /** Open [name] with a SupportSQLiteOpenHelper whose onUpgrade runs [onUpgrade]. */
    private fun openWithMigration(
        name: String,
        targetVersion: Int,
        onUpgrade: (androidx.sqlite.db.SupportSQLiteDatabase, Int, Int) -> Unit
    ): androidx.sqlite.db.SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(targetVersion) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = onUpgrade(db, oldVersion, newVersion)
                })
                .build()
        )
        return helper.writableDatabase
    }
}
