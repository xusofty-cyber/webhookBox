package com.hookbox.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hookbox.app.App
import com.hookbox.app.data.RelayConfig
import com.hookbox.app.service.RelayService
import kotlinx.coroutines.launch

/** 顶层目的地：不引入导航库，三个页面用状态切换就够 */
private sealed interface Destination {
    data object List : Destination
    data class Detail(val messageId: String) : Destination
    data object Settings : Destination
}

/**
 * App 的根组件：负责页面切换和全局接线（权限、电池白名单、服务启停）。
 * [externalMessageId] 来自通知点击，变化时直达对应详情页。
 */
@Composable
fun HookBoxApp(externalMessageId: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val messageFlow = remember { App.db.messageDao().observeRecent() }
    val messages by messageFlow.collectAsStateWithLifecycle(emptyList())
    val config by App.settings.config.collectAsStateWithLifecycle(RelayConfig())
    val connState by RelayService.state.collectAsStateWithLifecycle()

    var destination by rememberSaveable(stateSaver = DestinationSaver) {
        mutableStateOf<Destination>(Destination.List)
    }
    var batteryIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    LaunchedEffect(externalMessageId) {
        if (!externalMessageId.isNullOrBlank()) {
            destination = Destination.Detail(externalMessageId)
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryIgnored = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (val dest = destination) {
        Destination.List -> MessageListScreen(
            messages = messages,
            config = config,
            connState = connState,
            batteryIgnored = batteryIgnored,
            onOpenMessage = { destination = Destination.Detail(it) },
            onDeleteMessage = { id ->
                scope.launch { App.db.messageDao().deleteById(id) }
            },
            onOpenSettings = { destination = Destination.Settings },
            onRequestBatteryWhitelist = { requestIgnoreBatteryOptimizations(context) },
        )

        is Destination.Detail -> {
            val detailFlow = remember(dest.messageId) { App.db.messageDao().observeById(dest.messageId) }
            val flowMessage by detailFlow.collectAsStateWithLifecycle(null)
            // 优先用列表里已加载的那条，避免进详情时闪一下「不存在」；删掉后 flow 会变 null
            val fromList = messages.firstOrNull { it.id == dest.messageId }
            MessageDetailScreen(
                message = flowMessage ?: fromList,
                onBack = { destination = Destination.List },
                onDelete = { id ->
                    scope.launch { App.db.messageDao().deleteById(id) }
                    destination = Destination.List
                },
            )
        }

        Destination.Settings -> SettingsScreen(
            config = config,
            connState = connState,
            messageCount = messages.size,
            batteryIgnored = batteryIgnored,
            onBack = { destination = Destination.List },
            onToggleEnabled = { enabled ->
                scope.launch { App.settings.setEnabled(enabled) }
                if (enabled) RelayService.start(context) else RelayService.stop(context)
            },
            onSave = { url, token ->
                scope.launch {
                    App.settings.save(url, token)
                    if (url.isNotBlank() && token.isNotBlank()) {
                        RelayService.start(context)
                    }
                }
                destination = Destination.List
            },
            onClearMessages = {
                scope.launch { App.db.messageDao().clear() }
            },
            onRequestBatteryWhitelist = { requestIgnoreBatteryOptimizations(context) },
        )
    }
}

/** Destination 里带参数（详情页 id），rememberSaveable 需要自定义 Saver */
private val DestinationSaver = androidx.compose.runtime.saveable.mapSaver<Destination>(
    save = { dest ->
        when (dest) {
            Destination.List -> mapOf("kind" to "list")
            Destination.Settings -> mapOf("kind" to "settings")
            is Destination.Detail -> mapOf("kind" to "detail", "id" to dest.messageId)
        }
    },
    restore = { map ->
        when (map["kind"] as? String) {
            "detail" -> (map["id"] as? String)?.let { Destination.Detail(it) } ?: Destination.List
            "settings" -> Destination.Settings
            else -> Destination.List
        }
    },
)

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val manager = context.getSystemService(PowerManager::class.java) ?: return false
    return manager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
}
