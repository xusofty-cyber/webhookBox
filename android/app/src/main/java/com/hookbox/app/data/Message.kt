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
    /**
     * 原始 webhook 请求体全文（通常是 JSON）。
     * title/body 是服务端归一化后的预览，详情页靠 raw 展示完整内容；
     * 旧消息或旧版服务端没有这个字段时为空串，详情页只展示 body。
     */
    val raw: String = "",
)
