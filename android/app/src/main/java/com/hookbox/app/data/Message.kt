package com.hookbox.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val id: String,
    val source: String,
    val title: String,
    val body: String,
    val level: String,
    val ts: Long,
    val receivedAt: Long = System.currentTimeMillis(),
)
