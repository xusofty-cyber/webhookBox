package com.hookbox.app.net

import com.hookbox.app.data.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 断线补拉：每次（重）连成功后，把服务端最近的消息拉回来补进本地库。
 * 入库用 INSERT OR IGNORE 按 id 去重，在线时实时收到的消息不会重复；
 * 补拉回来的历史消息不弹通知（只在实时路径 postMessage）。
 */
object HistoryFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 拉取最近 [limit] 条并逐条交给 [insert]，返回实际新增条数 */
    suspend fun fetchRecent(
        baseUrl: String,
        token: String,
        limit: Int = 200,
        insert: suspend (Message) -> Long,
    ): Int = withContext(Dispatchers.IO) {
        val trimmed = baseUrl.trim().trimEnd('/')
        val encoded = URLEncoder.encode(token, "UTF-8")
        val url = "$trimmed/api/messages?token=$encoded&limit=$limit"
        val request = Request.Builder().url(url).get().build()

        val body: String? = try {
            val response = client.newCall(request).execute()
            val text = if (response.isSuccessful) response.body?.string() else null
            response.close()
            text
        } catch (e: Exception) {
            null
        } ?: return@withContext 0

        val array = runCatching { JSONObject(body).optJSONArray("messages") }.getOrNull()
            ?: return@withContext 0

        var added = 0
        for (i in 0 until array.length()) {
            val data = array.optJSONObject(i) ?: continue
            val id = data.optString("id")
            if (id.isBlank()) continue
            val message = Message(
                id = id,
                source = data.optString("source", "unknown"),
                title = data.optString("title", "通知"),
                body = data.optString("body"),
                level = data.optString("level", "info"),
                ts = data.optLong("ts", System.currentTimeMillis()),
                raw = data.optString("raw"),
            )
            if (insert(message) != -1L) added++
        }
        added
    }
}
