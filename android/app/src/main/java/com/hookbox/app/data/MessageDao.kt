package com.hookbox.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages ORDER BY ts DESC LIMIT 500")
    fun observeRecent(): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE id = :id")
    fun observeById(id: String): Flow<Message?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: Message): Long

    @Query("SELECT MAX(ts) FROM messages")
    suspend fun newestTs(): Long?

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages")
    suspend fun clear()
}
