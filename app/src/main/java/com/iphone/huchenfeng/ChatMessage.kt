package com.iphone.huchenfeng

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ChatMessage")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val messageId: Long = 0,
    val sessionId: Long,
    val isUser: Boolean,
    val content: String,
    val timestamp: Long
)
