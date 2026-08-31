package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [BodyCompositionSnapshotEntity]. Append-only at the entity level
 * (no @Update / @Delete) — the source of truth is Samsung Health, the local
 * table just caches reads so the chat assistant has history to reference
 * and so the UI doesn't lose the last-known weight when SH is offline.
 *
 * Ordering convention mirrors [BmrSnapshotDao] / [TargetSnapshotDao]:
 * primary by the source timestamp (or createdAtMs as tiebreaker), with
 * `id DESC` as the ultimate tiebreaker so same-second inserts surface in
 * write order.
 */
@Dao
interface BodyCompositionSnapshotDao {
    @Insert
    suspend fun insert(snapshot: BodyCompositionSnapshotEntity): Long

    /** Latest snapshot regardless of date. Drives the "Peso actual" row in
     *  the Reports tab and the chat's "current values" injection. Null
     *  when the table is empty (fresh install, never synced from SH). */
    @Query("""
        SELECT * FROM body_composition_snapshots
        ORDER BY COALESCE(sourceTimestampMs, capturedAtMs) DESC, id DESC
        LIMIT 1
    """)
    suspend fun latest(): BodyCompositionSnapshotEntity?

    /** Live flow of the latest snapshot — the UI subscribes to this so a
     *  fresh weigh-in updates the Reports tab without a manual refresh. */
    @Query("""
        SELECT * FROM body_composition_snapshots
        ORDER BY COALESCE(sourceTimestampMs, capturedAtMs) DESC, id DESC
        LIMIT 1
    """)
    fun observeLatest(): Flow<BodyCompositionSnapshotEntity?>

    /** Last N snapshots, newest first. Used by the chat context builder
     *  to expose recent body-composition history to the LLM. N is bounded
     *  by the caller — a typical value is 10–20 entries; even a year of
     *  daily weigh-ins is tractable to load into a prompt context, but we
     *  cap to keep the request body small. */
    @Query("""
        SELECT * FROM body_composition_snapshots
        ORDER BY COALESCE(sourceTimestampMs, capturedAtMs) DESC, id DESC
        LIMIT :limit
    """)
    suspend fun recent(limit: Int): List<BodyCompositionSnapshotEntity>

    /** Snapshots whose effective timestamp (sourceTimestampMs preferred,
     *  capturedAtMs fallback) falls in [startMs, endMs). Returned in
     *  ascending chronological order so callers can fold over them to
     *  build week- or month-bucket averages without re-sorting.
     *
     *  The Reports tab's Overview card queries this with a deliberately
     *  wide left edge — typically `selectedStartMs - 180 days` — so the
     *  forward-fill logic (weeks/months without a weigh-in inherit the
     *  last known average) has historical data to anchor on even when the
     *  selected period itself has no readings. */
    @Query("""
        SELECT * FROM body_composition_snapshots
        WHERE COALESCE(sourceTimestampMs, capturedAtMs) >= :startMs
          AND COALESCE(sourceTimestampMs, capturedAtMs) < :endMs
        ORDER BY COALESCE(sourceTimestampMs, capturedAtMs) ASC, id ASC
    """)
    suspend fun getBetween(startMs: Long, endMs: Long): List<BodyCompositionSnapshotEntity>

    /** Checks whether a snapshot for the given source timestamp already
     *  exists. Used to dedup reads from Samsung Health — we never want
     *  two rows for the same physical weigh-in. */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM body_composition_snapshots
            WHERE sourceTimestampMs = :sourceTimestampMs
            LIMIT 1
        )
    """)
    suspend fun existsForSourceTimestamp(sourceTimestampMs: Long): Boolean
}
