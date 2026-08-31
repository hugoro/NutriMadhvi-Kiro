package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val sender: String, // "user" or "gemini"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val imageBase64: String? = null,
    /**
     * Last-edit wall-clock timestamp (ms). NULL = never edited (legacy and
     * fresh rows). Populated by [com.example.ui.ChatViewModel.confirmEditAndResubmit]
     * on the user-side message that was rewritten. The message identity stays
     * pinned to [timestamp]; this column only drives the "(editado)" marker
     * in the UI.
     *
     * Added in DB v15 via [com.example.data.local.ChatDatabase.MIGRATION_14_15].
     */
    val editedTimestamp: Long? = null
)
