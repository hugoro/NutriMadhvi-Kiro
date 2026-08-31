package com.example.data.local

import androidx.room.Entity
import androidx.room.Fts4

/**
 * Full-text search shadow table over [ChatMessage]. Added in DB v16
 * (2026-06-15) as the foundation of the WhatsApp-style "search across all
 * conversations" feature.
 *
 * **How it works (Room FTS contract):**
 *   - `contentEntity = ChatMessage::class` makes this a "content-table"
 *     FTS4: it does NOT duplicate the message text on disk. Instead it
 *     stores a tokenized index that points back at `chat_messages.id`
 *     (which Room maps to FTS's special `rowid` column).
 *   - The single indexed column is [text] — same name as
 *     [ChatMessage.text] so Room can wire them together automatically.
 *   - Queries against the virtual table use SQLite's `MATCH` operator,
 *     e.g. `WHERE chat_messages_fts MATCH 'apple'`. We expose this via
 *     [ChatDao.searchMessages] and surface results in the chat search
 *     UI. Token matching is prefix-friendly when the query ends with `*`
 *     (the DAO appends one).
 *
 * **Why FTS4 and not FTS5:** Android's bundled SQLite has shipped FTS4 on
 * every API level we support; FTS5 is only guaranteed on API 30+. FTS4 is
 * fast enough for tens of thousands of messages and has the same `MATCH`
 * surface area we need.
 *
 * **Migration story:** [ChatDatabase.MIGRATION_15_16] creates the virtual
 * table AND seeds it from the existing `chat_messages` rows via
 * `INSERT INTO chat_messages_fts(rowid, text) SELECT id, text FROM
 * chat_messages`. That's a one-time O(N) cost at upgrade time. After
 * that, Room keeps the index in sync because it owns both the parent
 * entity and the FTS shadow.
 */
@Entity(tableName = "chat_messages_fts")
@Fts4(contentEntity = ChatMessage::class)
data class ChatMessageFts(
    val text: String
)
