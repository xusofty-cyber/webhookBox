package com.hookbox.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hookbox.app.data.Message
import com.hookbox.app.data.RelayConfig
import com.hookbox.app.net.ConnState
import com.hookbox.app.net.describe

/**
 * 消息列表页：顶栏只放标题、连接状态和设置入口，
 * 控制类操作（启停、地址）全部收进设置页，不再常驻占版面。
 * 列表行只是预览，点进详情页看完整内容。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MessageListScreen(
    messages: List<Message>,
    config: RelayConfig,
    connState: ConnState,
    batteryIgnored: Boolean,
    onOpenMessage: (String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var levelFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceFilter by rememberSaveable { mutableStateOf<String?>(null) }

    val sources = remember(messages) { messages.map { it.source }.distinct().sorted() }
    val filtered = remember(messages, query, levelFilter, sourceFilter) {
        messages.filter { message ->
            (levelFilter == null || message.level == levelFilter) &&
                (sourceFilter == null || message.source == sourceFilter) &&
                (query.isBlank() ||
                    message.title.contains(query, ignoreCase = true) ||
                    message.body.contains(query, ignoreCase = true) ||
                    message.source.contains(query, ignoreCase = true) ||
                    message.raw.contains(query, ignoreCase = true))
        }
    }
    val grouped = remember(filtered) { filtered.groupBy { dayGroupLabel(it.ts) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("HookBox", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(10.dp))
                        StatusDot(connState)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searchVisible = !searchVisible
                        if (!searchVisible) query = ""
                    }) {
                        Icon(
                            if (searchVisible) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searchVisible) "关闭搜索" else "搜索消息",
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (connState != ConnState.Connected) {
                StatusBanner(connState, config, onOpenSettings)
            }
            if (!batteryIgnored) {
                BatteryBanner(onRequestBatteryWhitelist)
            }
            if (searchVisible) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索标题、正文、来源或原始内容") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            run {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = levelFilter == null,
                            onClick = { levelFilter = null },
                            label = { Text("全部级别") },
                        )
                    }
                    items(listOf("info", "warn", "error")) { level ->
                        FilterChip(
                            selected = levelFilter == level,
                            onClick = { levelFilter = if (levelFilter == level) null else level },
                            label = { Text(levelLabel(level)) },
                        )
                    }
                    if (sources.size > 1) {
                        item {
                            FilterChip(
                                selected = sourceFilter == null,
                                onClick = { sourceFilter = null },
                                label = { Text("全部来源") },
                            )
                        }
                        items(sources) { source ->
                            FilterChip(
                                selected = sourceFilter == source,
                                onClick = { sourceFilter = if (sourceFilter == source) null else source },
                                label = { Text(source) },
                            )
                        }
                    }
                }
            }

            when {
                messages.isEmpty() -> EmptyState(config, onOpenSettings)
                filtered.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "没有符合筛选条件的消息",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        grouped.forEach { entry ->
                            val day = entry.key
                            val dayMessages = entry.value
                            stickyHeader(key = "day-$day") {
                                Text(
                                    text = day,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.background)
                                        .padding(vertical = 6.dp),
                                )
                            }
                            items(dayMessages, key = { it.id }) { message ->
                                SwipeableMessageCard(
                                    message = message,
                                    onClick = { onOpenMessage(message.id) },
                                    onDelete = { onDeleteMessage(message.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(state: ConnState) {
    val color = when (state) {
        ConnState.Connected -> Accent
        ConnState.Connecting, is ConnState.Retrying -> Warn
        is ConnState.Failed -> Danger
        ConnState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(state.describe(), color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StatusBanner(state: ConnState, config: RelayConfig, onOpenSettings: () -> Unit) {
    val text = when {
        !config.configured -> "还没有配置中转地址和 token"
        !config.enabled -> "接收已停止，在设置里重新开启"
        state is ConnState.Failed -> "连接失败：${state.reason}"
        state is ConnState.Retrying -> "连接已断开，${state.seconds}s 后自动重连"
        state == ConnState.Connecting -> "正在连接中转服务…"
        else -> "未连接"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (!config.configured || !config.enabled) {
            TextButton(onClick = onOpenSettings) { Text("去设置") }
        }
    }
}

@Composable
private fun BatteryBanner(onRequestBatteryWhitelist: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Warn.copy(alpha = 0.12f))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = Warn,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "系统可能随时掐断后台连接",
            style = MaterialTheme.typography.bodySmall,
            color = Warn,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRequestBatteryWhitelist) {
            Text("去设置", color = Warn, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableMessageCard(
    message: Message,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Danger.copy(alpha = 0.15f))
                    .padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("删除", color = Danger, style = MaterialTheme.typography.labelMedium)
            }
        },
    ) {
        MessageCard(message, onClick)
    }
}

@Composable
private fun MessageCard(message: Message, onClick: () -> Unit) {
    val color = levelColor(message.level)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(color),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LevelBadge(message.level)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        message.source,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatListTime(message.ts),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    message.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (message.body.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        message.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (message.raw.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "点开查看完整内容 ›",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
fun LevelBadge(level: String) {
    val color = levelColor(level)
    Text(
        text = levelLabel(level),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun EmptyState(config: RelayConfig, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (config.configured) "已就绪，等待第一条 webhook" else "先配置中转地址和 token 才能开始接收",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!config.configured) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenSettings) { Text("去配置") }
        }
    }
}
