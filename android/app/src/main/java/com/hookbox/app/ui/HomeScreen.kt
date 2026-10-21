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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hookbox.app.App
import com.hookbox.app.data.Message
import com.hookbox.app.data.RelayConfig
import com.hookbox.app.net.ConnState
import com.hookbox.app.net.describe
import com.hookbox.app.service.RelayService
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // observeRecent() 每次调用都会新建一个 Flow，必须 remember 住，否则每帧都重新订阅
    val messageFlow = remember { App.db.messageDao().observeRecent() }
    val messages by messageFlow.collectAsStateWithLifecycle(emptyList())
    val config by App.settings.config.collectAsStateWithLifecycle(RelayConfig())
    val connState by RelayService.state.collectAsStateWithLifecycle()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var urlDraft by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var tokenDraft by remember(config.token) { mutableStateOf(config.token) }
    var batteryIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .safeDrawingPadding(),
    ) {
        Header(
            state = connState,
            config = config,
            onToggle = {
                if (config.enabled) {
                    scope.launch { App.settings.setEnabled(false) }
                    RelayService.stop(context)
                } else {
                    scope.launch { App.settings.setEnabled(true) }
                    RelayService.start(context)
                }
            },
            onSettings = { showSettings = true },
        )

        if (!batteryIgnored) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Warn.copy(alpha = 0.10f))
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "系统可能随时掐断后台连接",
                    color = Warn,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { requestIgnoreBatteryOptimizations(context) }) {
                    Text("去设置", fontSize = 12.sp, color = Warn)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Stroke)

        if (messages.isEmpty()) {
            EmptyState(config)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(messages, key = { it.id }) { message ->
                    MessageRow(message)
                    HorizontalDivider(color = Stroke.copy(alpha = 0.6f))
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            url = urlDraft,
            token = tokenDraft,
            onUrlChange = { urlDraft = it },
            onTokenChange = { tokenDraft = it },
            onDismiss = { showSettings = false },
            onSave = {
                scope.launch {
                    App.settings.save(urlDraft, tokenDraft)
                    showSettings = false
                    if (urlDraft.isNotBlank() && tokenDraft.isNotBlank()) {
                        RelayService.start(context)
                    }
                }
            },
        )
    }
}

@Composable
private fun Header(
    state: ConnState,
    config: RelayConfig,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("HookBox", color = TextPrimary, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            StatusPill(state)
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = if (config.configured) config.baseUrl else "未配置中转地址",
            color = TextMuted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onToggle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (config.enabled) Surface2 else Accent,
                    contentColor = if (config.enabled) TextPrimary else Bg,
                ),
            ) {
                Text(if (config.enabled) "停止接收" else "开始接收", fontSize = 14.sp)
            }
            OutlinedButton(onClick = onSettings) {
                Text("中转设置", fontSize = 14.sp, color = TextPrimary)
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun StatusPill(state: ConnState) {
    val color = when (state) {
        ConnState.Connected -> Accent
        ConnState.Connecting, is ConnState.Retrying -> Warn
        is ConnState.Failed -> Danger
        ConnState.Idle -> TextMuted
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(state.describe(), color = color, fontSize = 12.sp)
    }
}

@Composable
private fun MessageRow(message: Message) {
    val accent = when (message.level) {
        "error" -> Danger
        "warn" -> Warn
        else -> AccentDim
    }

    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Box(
            Modifier
                .width(2.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.source, color = accent, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(formatTime(message.ts), color = TextMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = message.title,
                color = TextPrimary,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (message.body.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = message.body,
                    color = TextMuted,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(config: RelayConfig) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (config.configured) "已就绪，等待第一条 webhook" else "先点「中转设置」填入地址和 token",
            color = TextMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SettingsDialog(
    url: String,
    token: String,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("中转服务", color = TextPrimary, fontSize = 16.sp) },
        text = {
            Column {
                Text("Worker 地址", color = TextMuted, fontSize = 12.sp)
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    singleLine = true,
                    placeholder = { Text("https://hookbox.xxx.workers.dev", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("RELAY_TOKEN", color = TextMuted, fontSize = 12.sp)
                OutlinedTextField(
                    value = token,
                    onValueChange = onTokenChange,
                    singleLine = true,
                    placeholder = { Text("与服务端 secret 一致", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text("保存并连接") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextMuted) }
        },
    )
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatTime(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(timeFormatter)

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
