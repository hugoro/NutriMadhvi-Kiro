package com.example.data.local

import org.json.JSONArray
import org.json.JSONObject

enum class FoodEditType { ADDED, AUTO, MANUAL }

data class FoodEditLogEntry(
    val epochMs: Long,
    val type: FoodEditType,
)

fun List<FoodEditLogEntry>.encodeEditLog(): String {
    val arr = JSONArray()
    forEach { e ->
        arr.put(JSONObject().apply {
            put("ms", e.epochMs)
            put("t", e.type.name)
        })
    }
    return arr.toString()
}

fun decodeEditLog(json: String?): List<FoodEditLogEntry> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            FoodEditLogEntry(
                epochMs = obj.getLong("ms"),
                type = FoodEditType.valueOf(obj.getString("t")),
            )
        }
    }.getOrDefault(emptyList())
}

fun FoodEntry.appendEditLog(type: FoodEditType, nowMs: Long = System.currentTimeMillis()): FoodEntry {
    val existing = decodeEditLog(editLogJson)
    val updated = listOf(FoodEditLogEntry(nowMs, type)) + existing
    return copy(editLogJson = updated.encodeEditLog())
}
