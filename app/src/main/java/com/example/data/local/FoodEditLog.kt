package com.example.data.local

import org.json.JSONArray
import org.json.JSONObject

enum class FoodEditType {
    ADDED,            // Initial AI analysis
    AUTO,             // Re-analyzed via "Substituir foto"
    MANUAL,           // User edited manually
    LEFTOVER,         // Adjusted via "Registar sobras"
    LEFTOVER_REMOVED  // Leftovers deleted → meal reverted to original
}

/**
 * One row of a meal's activity history.
 *
 * [model] names the LLM behind the write (e.g. `gemini-3.5-flash`,
 * `qwen/qwen3.6-27b`) and is null for purely manual edits and for entries
 * written before the field existed — the UI omits the model on those instead of
 * showing a blank or a made-up value.
 */
data class FoodEditLogEntry(
    val epochMs: Long,
    val type: FoodEditType,
    val model: String? = null
)

fun List<FoodEditLogEntry>.encodeEditLog(): String {
    val arr = JSONArray()
    forEach { e ->
        arr.put(JSONObject().apply {
            put("ms", e.epochMs)
            put("t", e.type.name)
            e.model?.let { put("m", it) }
        })
    }
    return arr.toString()
}

fun decodeEditLog(json: String?): List<FoodEditLogEntry> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            // Per-entry salvage: one row written by a newer/older build must not
            // discard the whole history, which is what a single throw here would
            // do — the log is diagnostic data the user can't reconstruct.
            runCatching {
                val obj = arr.getJSONObject(i)
                FoodEditLogEntry(
                    epochMs = obj.getLong("ms"),
                    type = FoodEditType.valueOf(obj.getString("t")),
                    model = obj.optString("m").takeIf { it.isNotBlank() }
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}

/**
 * Prepends an entry to this row's history, newest first.
 *
 * The log is capped because it lives inline in the `food_entries` row: a meal
 * that gets re-analysed repeatedly would otherwise grow an unbounded JSON blob
 * that every query of that row has to read and parse.
 */
fun FoodEntry.appendEditLog(
    type: FoodEditType,
    model: String? = null,
    nowMs: Long = System.currentTimeMillis()
): FoodEntry {
    val existing = decodeEditLog(editLogJson)
    val updated = (listOf(FoodEditLogEntry(nowMs, type, model)) + existing).take(MAX_EDIT_LOG_ENTRIES)
    return copy(editLogJson = updated.encodeEditLog())
}

private const val MAX_EDIT_LOG_ENTRIES = 30
