package com.hookbox.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.hookbox.app.App
import com.hookbox.app.service.RelayService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 通知点击带来的消息 id，HookBoxApp 监听它直达详情页 */
    private var pendingMessageId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureRelayRunning()
        pendingMessageId = intent?.getStringExtra(EXTRA_MESSAGE_ID)
        setContent {
            HookBoxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HookBoxApp(externalMessageId = pendingMessageId)
                }
            }
        }
    }

    /**
     * 每次打开界面都补查一遍：开关是开的就确保服务在跑。
     * 覆盖「进程还活着、但服务已被系统单独停掉」的场景（App.onCreate 不会再执行）。
     * RelayService.start 是幂等的，服务已在运行时不会重复建连。
     */
    private fun ensureRelayRunning() {
        lifecycleScope.launch {
            val enabled = runCatching { App.settings.config.first().enabled }.getOrDefault(false)
            if (enabled) runCatching { RelayService.start(this@MainActivity) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 加时间戳后缀触发重组之外的变化检测：直接换一个新的 State 值即可，
        // 同一条消息重复点通知时用一次性消费处理——先清空再赋值
        val id = intent.getStringExtra(EXTRA_MESSAGE_ID)
        pendingMessageId = null
        pendingMessageId = id
    }

    companion object {
        const val EXTRA_MESSAGE_ID = "com.hookbox.app.extra.MESSAGE_ID"
    }
}
