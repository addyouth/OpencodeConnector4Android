package com.opencode.remote.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.opencode.remote.ui.strings.AppLocale
import kotlinx.coroutines.delay

// ─── Markdown Data Model ──────────────────────────────────────────────────

internal sealed class MdSegment {
    data class CodeBlock(val language: String, val code: String) : MdSegment()
    data class Paragraph(val spans: List<MdSpan>) : MdSegment()
}

internal sealed class MdSpan {
    data class Bold(val text: String) : MdSpan()
    data class Italic(val text: String) : MdSpan()
    data class InlineCode(val text: String) : MdSpan()
    data class Plain(val text: String) : MdSpan()
    /** 链接：[显示](url) 或裸 URL——点弹框（打开/复制整条），不再靠系统选词 */
    data class Link(val text: String, val url: String) : MdSpan()
}

// ─── Markdown Parsing ─────────────────────────────────────────────────────

internal fun parseMarkdown(text: String): List<MdSegment> {
    val segments = mutableListOf<MdSegment>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Fenced code block ```lang ... ```
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            segments.add(MdSegment.CodeBlock(lang, codeLines.joinToString("\n")))
            if (i < lines.size) i++ // skip closing ```
            continue
        }

        // Collect consecutive non-code-block lines as a paragraph
        val paraLines = mutableListOf<String>()
        while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
            paraLines.add(lines[i])
            i++
        }
        if (paraLines.isNotEmpty()) {
            val joined = paraLines.joinToString("\n")
            segments.add(MdSegment.Paragraph(parseInlineSpans(joined)))
        }
    }

    return segments
}

internal fun parseInlineSpans(text: String): List<MdSpan> {
    // 先按 [t](u) 切块（块内 Plain 再切裸链接），最后 Plain 块才跑 **/粗体/代码旧逻辑
    val out = mutableListOf<MdSpan>()
    val mdLink = Regex("""\[([^\]\n]+)\]\(([^)\s]+)\)""")
    var lastEnd = 0
    val chunks = mutableListOf<Pair<String, MatchResult?>>()
    for (m in mdLink.findAll(text)) {
        if (m.range.first > lastEnd) chunks.add(text.substring(lastEnd, m.range.first) to null)
        chunks.add("" to m)
        lastEnd = m.range.last + 1
    }
    if (lastEnd < text.length) chunks.add(text.substring(lastEnd) to null)
    if (chunks.isEmpty()) chunks.add(text to null)
    for ((plain, m) in chunks) {
        if (m != null) {
            out.add(MdSpan.Link(m.groupValues[1], m.groupValues[2]))
        } else {
            for (sub in splitBareUrls(plain)) {
                if (sub.second != null) out.add(MdSpan.Link(sub.first, sub.first))
                else out.addAll(parseRichPlain(sub.first))
            }
        }
    }
    if (out.isEmpty()) out.add(MdSpan.Plain(text))
    return out
}

/** 裸链接切分：返回 (文本, url?)，url 非空即链接段。 */
internal fun splitBareUrls(text: String): List<Pair<String, String?>> {
    val out = mutableListOf<Pair<String, String?>>()
    val urlRe = Regex("""https?://[^\s<>"')\]]+""")
    var lastEnd = 0
    for (m in urlRe.findAll(text)) {
        var url = m.value
        // 裁尾巴：中英标点、右括号（markdown 裸链套括号常见）
        url = url.trimEnd('.', ',', ';', ':', '!', '?', '。', '，', '！', '？', '；', '：', '、', '”', '’', '）')
        if (url.endsWith(")") && url.count { it == '(' } < url.count { it == ')' }) {
            url = url.dropLast(1)
        }
        if (m.range.first > lastEnd) out.add(text.substring(lastEnd, m.range.first) to null)
        if (url.length > 8) out.add(url to url)
        else out.add(m.value to null)
        lastEnd = m.range.last + 1
    }
    if (lastEnd < text.length) out.add(text.substring(lastEnd) to null)
    if (out.isEmpty()) out.add(text to null)
    return out
}

/** 旧的行内 Bold/Italic/Code 逻辑（只吃纯文本块）。 */
internal fun parseRichPlain(text: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    val regex = Regex("""(\*\*(.+?)\*\*|`([^`]+)`|\*(.+?)\*)""")
    var lastEnd = 0

    for (match in regex.findAll(text)) {
        if (match.range.first > lastEnd) {
            spans.add(MdSpan.Plain(text.substring(lastEnd, match.range.first)))
        }
        when {
            match.groupValues[2].isNotEmpty() -> spans.add(MdSpan.Bold(match.groupValues[2]))
            match.groupValues[3].isNotEmpty() -> spans.add(MdSpan.InlineCode(match.groupValues[3]))
            match.groupValues[4].isNotEmpty() -> spans.add(MdSpan.Italic(match.groupValues[4]))
        }
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        spans.add(MdSpan.Plain(text.substring(lastEnd)))
    }
    if (spans.isEmpty()) {
        spans.add(MdSpan.Plain(text))
    }
    return spans
}

// ─── Raw text（长按复制原始段用，还原 markdown 写法） ───────────────────────

internal fun MdSpan.rawText(): String = when (this) {
    is MdSpan.Bold -> "**$text**"
    is MdSpan.Italic -> "*$text*"
    is MdSpan.InlineCode -> "`$text`"
    is MdSpan.Plain -> text
    is MdSpan.Link -> if (text == url) url else "[$text]($url)"
}

internal fun MdSegment.rawText(): String = when (this) {
    is MdSegment.CodeBlock -> "```$language\n$code\n```"
    is MdSegment.Paragraph -> spans.joinToString("") { it.rawText() }
}

// ─── Markdown Text Composable ─────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val segments = remember(text) { parseMarkdown(text) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedLabel = AppLocale.strings.copied
    val s = AppLocale.strings
    fun copyRaw(raw: String) {
        clipboard.setText(AnnotatedString(raw))
        Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
    }
    // 点链弹框：打开 / 复制整条（不再靠系统选词，长按整段逻辑不动）
    var linkDialogUrl by remember { mutableStateOf<String?>(null) }
    fun copyLink(url: String) {
        clipboard.setText(AnnotatedString(url))
        Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
        linkDialogUrl = null
    }
    fun openLink(url: String) {
        linkDialogUrl = null
        try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(context, s.linkOpenFailed, Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEach { segment ->
            // 长按复制原始段（与框内手动选取共存：按中文字触发复制，按住拖动仍可选取）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = { copyRaw(segment.rawText()) }),
            ) {
                when (segment) {
                is MdSegment.CodeBlock -> {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = codeBackground,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (segment.language.isNotEmpty()) {
                                    Text(
                                        text = segment.language,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                // 代码块一键复制
                                var copied by remember { mutableStateOf(false) }
                                if (copied) {
                                    LaunchedEffect(Unit) { delay(1200); copied = false }
                                }
                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(segment.code))
                                        copied = true
                                    },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = segment.code,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = color,
                                    )
                                }
                            }
                        }
                    }
                }
                is MdSegment.Paragraph -> {
                    val annotated = buildAnnotatedString {
                        for (span in segment.spans) {
                            when (span) {
                                is MdSpan.Plain -> append(span.text)
                                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(span.text)
                                }
                                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                    append(span.text)
                                }
                                is MdSpan.Link -> {
                                    pushStringAnnotation("URL", span.url)
                                    withStyle(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline,
                                        )
                                    ) {
                                        append(span.text)
                                    }
                                    pop()
                                }
                                is MdSpan.InlineCode -> withStyle(
                                    SpanStyle(
                                        fontFamily = FontFamily.Monospace,
                                        background = codeBackground,
                                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                    )
                                ) {
                                    append(" ${span.text} ")
                                }
                            }
                        }
                    }
                    SelectionContainer {
                        ClickableText(
                            text = annotated,
                            style = MaterialTheme.typography.bodyMedium.copy(color = color),
                            onClick = { offset ->
                                annotated.getStringAnnotations("URL", offset, offset)
                                    .firstOrNull()?.let { linkDialogUrl = it.item }
                            },
                        )
                    }
                }
                }
            }
        }
    }

    // 链接动作框：打开 / 复制整条 / 关闭
    linkDialogUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { linkDialogUrl = null },
            title = {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = {
                TextButton(onClick = { copyLink(url) }) { Text(s.copyLink) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { openLink(url) }) { Text(s.openLink) }
                    TextButton(onClick = { linkDialogUrl = null }) { Text(s.close) }
                }
            },
        )
    }
}
