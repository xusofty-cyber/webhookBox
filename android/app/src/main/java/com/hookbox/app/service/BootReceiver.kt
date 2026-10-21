package com.hookbox.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hookbox.app.App
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 开机自启。只恢复用户上次主动开启过的状态，不会偷偷常驻。
 * BOOT_COMPLETED 是系统允许启动前台服务的豁免场景之一。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val enabled = runCatching {
            runBlocking { App.settings.config.first().enabled }
        }.getOrDefault(false)

        if (enabled) RelayService.start(context)
    }
}
