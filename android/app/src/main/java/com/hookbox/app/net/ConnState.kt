package com.hookbox.app.net

sealed interface ConnState {
    data object Idle : ConnState
    data object Connecting : ConnState
    data object Connected : ConnState
    data class Retrying(val seconds: Int) : ConnState
    data class Failed(val reason: String) : ConnState
}

fun ConnState.describe(): String = when (this) {
    ConnState.Idle -> "未连接"
    ConnState.Connecting -> "连接中…"
    ConnState.Connected -> "已连接"
    is ConnState.Retrying -> "${seconds}s 后重连"
    is ConnState.Failed -> "连接失败：$reason"
}
