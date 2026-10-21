package com.hookbox.app.ui

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val zone: ZoneId get() = ZoneId.systemDefault()

private val timeOfDay: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val monthDayTime: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val fullDateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val dayLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")

/** 列表行里的时间：今天只显示时刻，其余带日期 */
fun formatListTime(ts: Long): String {
    val instant = Instant.ofEpochMilli(ts)
    val date = instant.atZone(zone).toLocalDate()
    return if (date == LocalDate.now(zone)) {
        instant.atZone(zone).format(timeOfDay)
    } else {
        instant.atZone(zone).format(monthDayTime)
    }
}

/** 详情页元信息里的时间：完整到秒，带年份 */
fun formatFullTime(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(zone).format(fullDateTime)

/** 列表按天分组的分组头：今天 / 昨天 / 具体日期 */
fun dayGroupLabel(ts: Long): String {
    val date = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> date.format(dayLabel)
    }
}

/**
 * 把原始 payload 美化成带缩进的 JSON 便于阅读；
 * 不是合法 JSON（比如表单原文）就原样返回，绝不吞内容。
 */
fun prettyJson(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return raw
    return runCatching {
        when {
            trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
            trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
            else -> raw
        }
    }.getOrDefault(raw)
}
