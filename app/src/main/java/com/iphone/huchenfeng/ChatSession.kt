package com.iphone.huchenfeng

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ChatSession")
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
    val title: String,
    val timestamp: Long
)
