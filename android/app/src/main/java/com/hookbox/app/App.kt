package com.hookbox.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import com.hookbox.app.data.AppDatabase
import com.hookbox.app.data.SettingsStore

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "hookbox.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        settings = SettingsStore(this)
        createChannels()
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
