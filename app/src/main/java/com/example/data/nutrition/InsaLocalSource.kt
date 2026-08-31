package com.example.data.nutrition

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.text.Normalizer

/**
 * Reads the INSA Tabela da Composição de Alimentos from the embedded
 * assets/nutrition/insa_pt.db SQLite file.
 *
 * Fail-safe: any exception returns null. The caller falls through to the
 * next source in the cascade.
 */
class InsaLocalSource(context: Context) {

    private val db: SQLiteDatabase? = runCatching {
        val file = context.getDatabasePath("insa_pt_asset.db")
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            context.assets.open("nutrition/insa_pt.db").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }.getOrNull()

    /**
     * Looks up a food by normalised name. Returns [NutritionData] on match, null otherwise.
     * Uses exact match first, then LIKE prefix, to minimise false positives.
     */
    fun lookup(normalizedQuery: String): NutritionData? = runCatching {
        val db = db ?: return null

        // 1. Exact match
        query(db, "normalized_name = ?", arrayOf(normalizedQuery))
            ?: // 2. Prefix match (query is a prefix of the stored name)
            query(db, "normalized_name LIKE ?", arrayOf("$normalizedQuery%"))
            ?: // 3. Contains match (stored name contains the query as a substring)
            query(db, "normalized_name LIKE ?", arrayOf("%$normalizedQuery%"))
    }.getOrNull()

    private fun query(db: SQLiteDatabase, where: String, args: Array<String>): NutritionData? {
        db.rawQuery(
            "SELECT name_pt, per100g_kcal, per100g_fat, per100g_sat_fat, " +
                "per100g_carbs, per100g_sugars, per100g_protein " +
                "FROM foods WHERE $where LIMIT 1",
            args
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return NutritionData(
                displayName     = cursor.getString(0),
                per100gCalories = cursor.getDouble(1),
                per100gFat      = cursor.getDouble(2),
                per100gSatFat   = cursor.getDouble(3),
                per100gCarbs    = cursor.getDouble(4),
                per100gFreeSugars = cursor.getDouble(5),
                per100gProtein  = cursor.getDouble(6),
                source          = "insa",
                confidence      = "verified",
            )
        }
    }

    companion object {
        fun normalize(text: String): String {
            val nfd = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            return nfd.replace(Regex("\\p{Mn}"), "").trim()
        }
    }
}
