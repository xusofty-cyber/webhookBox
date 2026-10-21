package com.hookbox.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hookbox.app.data.Message

/**
 * 消息详情页：完整显示的核心页面。
 * 标题、正文不限行数、全量展示；原始 payload 美化成带缩进的 JSON，
 * 等宽字体、可选择复制、可切换横向滚动看长行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    message: Message?,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("消息详情", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (message != null) {
                        IconButton(onClick = { shareMessage(context, message) }) {
                            Icon(Icons.Filled.Share, contentDescription = "分享")
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除这条消息")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (message == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "这条消息已不存在（可能已被删除）",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LevelBadge(message.level)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        message.source,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(10.dp))
                // 标题：不截断，全量展示
                Text(
                    message.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(14.dp))
                MetaSection(message)

                if (message.body.isNotBlank()) {
                    Spacer(Modifier.height(20.dp))
                    SectionTitle("正文")
                    Spacer(Modifier.height(6.dp))
                    // 正文：不截断，全量展示
                    Text(
                        message.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { copyText(context, "正文", message.body) }) {
                        Text("复制正文")
                    }
                }

                if (message.raw.isNotBlank()) {
                    Spacer(Modifier.height(20.dp))
                    RawSection(message.raw)
                } else {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "这条消息没有保存原始 payload（旧消息或旧版服务端），以上就是全部内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
        }
    }

    if (confirmDelete && message != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条消息？") },
            text = { Text("只删手机本地的记录，服务端不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(message.id)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MetaSection(message: Message) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MetaRow("事件时间", formatFullTime(message.ts))
        MetaRow("接收时间", formatFullTime(message.receivedAt))
        MetaRow("来源", message.source)
        MetaRow("级别", levelLabel(message.level))
        MetaRow("消息 ID", message.id)
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RawSection(raw: String) {
    val context = LocalContext.current
    var wrapLines by rememberSaveable { mutableStateOf(true) }
    val pretty = remember(raw) { prettyJson(raw) }
    val horizontal = rememberScrollState()

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("原始内容（完整 payload）")
        Spacer(Modifier.weight(1f))
        Text(
            "长行横滑",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Switch(checked = !wrapLines, onCheckedChange = { wrapLines = !it })
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = pretty,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .then(if (wrapLines) Modifier else Modifier.horizontalScroll(horizontal))
            .padding(12.dp),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { copyText(context, "原始内容", pretty) }) {
        Text("复制原始内容")
    }
}

private fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

private fun shareMessage(context: Context, message: Message) {
    val text = buildString {
        appendLine(message.title)
        if (message.body.isNotBlank()) {
            appendLine()
            appendLine(message.body)
        }
        if (message.raw.isNotBlank()) {
            appendLine()
            appendLine(prettyJson(message.raw))
        }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享消息"))
}
