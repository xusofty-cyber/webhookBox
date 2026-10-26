package com.hookbox.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Patterns
import android.widget.Toast
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.UrlAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

/** 网址后面常粘着的标点/括号：匹配时剥掉，别把句号、右括号吃进链接里 */
private val TRAILING_PUNCT = setOf(
    '.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"',
    '、', '。', '，', '；', '：', '！', '？', '）',
)

/** 识别文本里的链接并做成可点样式；没有链接时原样返回。 */
fun linkified(text: String, linkColor: Color): AnnotatedString {
    val matcher = Patterns.WEB_URL.matcher(text)
    if (!matcher.find()) return AnnotatedString(text)
    return buildAnnotatedString {
        var pos = 0
        matcher.reset()
        while (matcher.find()) {
            var end = matcher.end()
            while (end > matcher.start() && text[end - 1] in TRAILING_PUNCT) end--
            if (end <= matcher.start()) continue
            append(text.substring(pos, matcher.start()))
            val raw = text.substring(matcher.start(), end)
            val url = if (raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true)
            ) {
                raw
            } else {
                "https://$raw"
            }
            pushUrlAnnotation(UrlAnnotation(url))
            pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            append(raw)
            pop()
            pop()
            pos = end
        }
        append(text.substring(pos))
    }
}

/**
 * 带链接的文本：点到链接直接用系统浏览器打开，点到普通文字什么都不做。
 * 只用在详情页（那里没有会抢点击的父级手势）；列表卡片的正文预览保持
 * 普通 Text，否则点卡片进详情的手势会被正文抢走。
 */
@Composable
fun LinkText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) { linkified(text, linkColor) }
    ClickableText(
        text = annotated,
        style = style.copy(color = color),
        modifier = modifier,
        onClick = { offset ->
            annotated.getUrlAnnotations(offset, offset).firstOrNull()?.let {
                openUrl(context, it.item.url)
            }
        },
    )
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show() }
}
