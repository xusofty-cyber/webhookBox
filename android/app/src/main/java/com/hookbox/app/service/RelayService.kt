package com.hookbox.app.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.hookbox.app.App
import com.hookbox.app.R
import com.hookbox.app.data.Message
import com.hookbox.app.net.ConnState
import com.hookbox.app.net.HistoryFetcher
import com.hookbox.app.net.RelayConnection
import com.hookbox.app.net.describe
import com.hookbox.app.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RelayService : LifecycleService() {

    private var connectionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground(buildOngoing("正在启动…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            // enabled 由调用方（UI）先落盘，这里不写：
            // stopSelf() 会立刻销毁服务，lifecycleScope 里的协程来不及跑完。
            shutdown()
            return START_NOT_STICKY
        }

        lifecycleScope.launch { App.settings.setEnabled(true) }
        if (connectionJob?.isActive != true) {
            connectionJob = lifecycleScope.launch { loop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        connectionJob?.cancel()
        connectionJob = null
        updateState(ConnState.Idle)
        super.onDestroy()
    }

    /** 本次连接是否已经补拉过历史，断开后复位，下次重连再补 */
    @Volatile
    private var backfilled = false

    private suspend fun loop() {
        while (true) {
            val config = App.settings.config.first()

            if (!config.configured) {
                updateState(ConnState.Idle)
                updateOngoing("未配置中转地址，点开 App 填写")
                delay(15_000)
                continue
            }

            RelayConnection(
                baseUrl = config.baseUrl,
                token = config.token,
                onFrame = ::handleFrame,
                onState = { state ->
                    updateState(state)
                    updateOngoing(state.describe())
                    if (state == ConnState.Connected) {
                        if (!backfilled) {
                            backfilled = true
                            lifecycleScope.launch {
                                runCatching {
                                    HistoryFetcher.fetchRecent(config.baseUrl, config.token) { message ->
                                        withContext(Dispatchers.IO) { App.db.messageDao().insert(message) }
                                    }
                                }
                            }
                        }
                    } else {
                        backfilled = false
                    }
                },
            ).run()
        }
    }

    private fun handleFrame(text: String) {
        if (text == "pong") return

        val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (frame.optString("type") != "msg") return

        val data = frame.optJSONObject("data") ?: return
        val message = Message(
            id = data.optString("id").ifBlank { "local-${System.currentTimeMillis()}" },
            source = data.optString("source", "unknown"),
            title = data.optString("title", "通知"),
            body = data.optString("body"),
            level = data.optString("level", "info"),
            ts = data.optLong("ts", System.currentTimeMillis()),
            raw = data.optString("raw"),
        )

        lifecycleScope.launch {
            val rowId = withContext(Dispatchers.IO) { App.db.messageDao().insert(message) }
            if (rowId != -1L) postMessage(message)
        }
    }

    private fun postMessage(message: Message) {
        if (!canPostNotifications()) return

        val notification = NotificationCompat.Builder(this, App.CHANNEL_MESSAGE)
            .setSmallIcon(R.drawable.ic_stat_hook)
            .setContentTitle(message.title)
            .setContentText(message.body.ifBlank { message.source })
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setPriority(
                if (message.level == "error") NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(message.id))
            .build()

        runCatching { NotificationManagerCompat.from(this).notify(message.id.hashCode(), notification) }
    }

    private fun buildOngoing(text: String): Notification =
        NotificationCompat.Builder(this, App.CHANNEL_RELAY)
            .setSmallIcon(R.drawable.ic_stat_hook)
            .setContentTitle("HookBox")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openAppIntent())
            .build()

    private fun updateOngoing(text: String) {
        runCatching {
            NotificationManagerCompat.from(this).notify(ONGOING_ID, buildOngoing(text))
        }
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, ONGOING_ID, notification, type)
    }

    private fun shutdown() {
        connectionJob?.cancel()
        connectionJob = null
        updateState(ConnState.Idle)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 点消息通知直达该消息的详情页；常驻通知不带 id，只打开 App */
    private fun openAppIntent(messageId: String? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (messageId != null) putExtra(MainActivity.EXTRA_MESSAGE_ID, messageId)
        }
        return PendingIntent.getActivity(
            this,
            messageId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "com.hookbox.app.action.START"
        const val ACTION_STOP = "com.hookbox.app.action.STOP"
        private const val ONGOING_ID = 1001

        private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
        val state: StateFlow<ConnState> = _state.asStateFlow()

        private fun updateState(value: ConnState) {
            _state.value = value
        }

        fun start(context: Context) {
            val intent = Intent(context, RelayService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RelayService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
