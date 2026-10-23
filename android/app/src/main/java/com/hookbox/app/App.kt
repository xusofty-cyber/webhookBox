package com.hookbox.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import com.hookbox.app.data.AppDatabase
import com.hookbox.app.data.SettingsStore
import com.hookbox.app.service.RelayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "hookbox.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        settings = SettingsStore(this)
        createChannels()
        ensureRelayRunning()
    }

    /**
     * 进程启动自愈：上次用户开启过接收就把服务拉起来。
     * 服务进程被系统回收后，靠 START_STICKY 不一定能复活，打开 App 时
     * 必须主动补这一步，否则界面会一直停在「未连接」。
     * startForegroundService 在个别后台拉起场景可能被系统拒绝，runCatching 兜底防崩溃。
     */
    private fun ensureRelayRunning() {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val enabled = runCatching { settings.config.first().enabled }.getOrDefault(false)
            if (enabled) runCatching { RelayService.start(this@App) }
        }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RELAY, "连接状态", NotificationManager.IMPORTANCE_MIN).apply {
                description = "前台服务常驻通知"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGE, "收到的通知", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "来自各平台的 webhook 消息"
            },
        )
    }

    companion object {
        const val CHANNEL_RELAY = "relay"
        const val CHANNEL_MESSAGE = "messages"

        lateinit var db: AppDatabase
            private set

        lateinit var settings: SettingsStore
            private set
    }
}
