package com.hookbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hookbox.app.data.RelayConfig
import com.hookbox.app.net.ConnState
import com.hookbox.app.net.describe

/**
 * 设置页：原来挤在 AlertDialog 里的两个输入框拆成独立页面，
 * 容得下校验、token 可见性切换、保活清单和消息管理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    config: RelayConfig,
    connState: ConnState,
    messageCount: Int,
    batteryIgnored: Boolean,
    onBack: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSave: (String, String) -> Unit,
    onClearMessages: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
) {
    var urlDraft by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var tokenDraft by remember(config.token) { mutableStateOf(config.token) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    val urlError = when {
        urlDraft.isBlank() -> null
        !urlDraft.startsWith("http://") && !urlDraft.startsWith("https://") &&
            !urlDraft.startsWith("ws://") && !urlDraft.startsWith("wss://") ->
            "地址要以 https:// 开头，不要带结尾的 /ws"
        else -> null
    }
    val dirty = urlDraft != config.baseUrl || tokenDraft != config.token
    val canSave = dirty && urlError == null && urlDraft.isNotBlank() && tokenDraft.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader("接收")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("接收 webhook 消息", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "当前状态：${connState.describe()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = onToggleEnabled,
                    enabled = config.configured,
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            SectionHeader("中转服务")
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                label = { Text("中转地址") },
                placeholder = { Text("https://hookbox.xxx.workers.dev") },
                singleLine = true,
                isError = urlError != null,
                supportingText = {
                    Text(urlError ?: "当前地址：${config.baseUrl.ifBlank { "未填写" }}")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tokenDraft,
                onValueChange = { tokenDraft = it },
                label = { Text("RELAY_TOKEN") },
                placeholder = { Text("与服务端 secret 一致") },
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { tokenVisible = !tokenVisible }) {
                        Text(if (tokenVisible) "隐藏" else "显示")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSave(urlDraft, tokenDraft) },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存并连接")
            }
            if (dirty && !canSave && urlError == null) {
                Text(
                    "地址和 token 都填好后才能保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            SectionHeader("保活")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("电池优化白名单", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (batteryIgnored) "已加入，系统不会因省电掐断连接" else "未加入，锁屏一段时间后可能收不到消息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!batteryIgnored) {
                    OutlinedButton(onClick = onRequestBatteryWhitelist) { Text("去设置") }
                }
            }
            Text(
                "国内 ROM 还需在系统设置里允许本 App 自启动，并把省电策略设为无限制；在多任务界面下拉加锁，防止一键清理带走。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            SectionHeader("本地消息")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已保存 $messageCount 条（最多 500 条）",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = messageCount > 0,
                ) { Text("清空") }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "HookBox ${com.hookbox.app.BuildConfig.VERSION_NAME} · 消息只存在本机和你自己的中转服务里",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空本地消息？") },
            text = { Text("会删除手机里保存的全部 $messageCount 条消息，服务端不受影响，之后重新连接会补拉最近 200 条。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearMessages()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
