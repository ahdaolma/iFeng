package com.iphone.huchenfeng

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    // 1. 获取所有对话（建议用 Flow，这样删除或增加时，列表会自动刷新）
    @Query("SELECT * FROM ChatSession ORDER BY timestamp DESC")
    fun getAllSessions(): Flow<List<ChatSession>>

    // 2. 获取某个对话的所有消息
    @Query("SELECT * FROM ChatMessage WHERE sessionId = :id ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(id: Long): List<ChatMessage>

    // 3. 插入新对话
    @Insert
    suspend fun insertSession(session: ChatSession): Long

    // 4. 插入新消息
    @Insert
    suspend fun insertMessage(message: ChatMessage): Long

    // --- 重点修正：必须放在界面接口的大括号内部 ---

    // 5. 删除特定对话
    @Query("DELETE FROM ChatSession WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: Long): Int // 明确返回被删除的行数

    @Query("DELETE FROM ChatMessage WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySession(sessionId: Long): Int

    @Query("UPDATE ChatSession SET title = :title WHERE sessionId = :sessionId")
    suspend fun updateSessionTitle(sessionId: Long, title: String): Int
}