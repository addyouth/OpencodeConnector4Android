package com.opencode.remote.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
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
            // 外层用 Column：段内 BasicTextField + 复制条上下排（Box 会重叠，之前按钮压字就是这么来的）
            Column(
                modifier = Modifier.fillMaxWidth(),
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
                                is MdSpan.InlineCode -> {
                                    pushStringAnnotation("COPY", span.text)
                                    withStyle(
                                        SpanStyle(
                                            fontFamily = FontFamily.Monospace,
                                            background = codeBackground,
                                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                        )
                                    ) {
                                        append(" ${span.text} ")
                                    }
                                    pop()
                                }
                            }
                        }
                    }
                    // 只读输入框当文本：能拿到选区（SelectionContainer 读不出选了啥），
                    // 点链接弹框、点行内代码直接拷；选中后气泡跟在选区旁（官方 floating toolbar 同原理：
                    // 以选区包围盒为锚点，见 TextToolbar.showMenu(rect)），不用滑下去找
                    var sel by remember(segment) { mutableStateOf<TextRange?>(null) }
                    var layout by remember(segment) { mutableStateOf<TextLayoutResult?>(null) }
                    val selectedText = sel?.let { r ->
                        val s = r.start.coerceIn(0, annotated.length)
                        val e = r.end.coerceIn(0, annotated.length)
                        if (e > s) annotated.substring(s, e) else null
                    }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        BasicTextField(
                            value = TextFieldValue(annotatedString = annotated, selection = sel ?: TextRange.Zero),
                            onValueChange = { sel = it.selection.takeIf { s -> !s.collapsed } },
                            readOnly = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = color),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            onTextLayout = { layout = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(annotated) {
                                    detectTapGestures { pos ->
                                        if (annotated.isEmpty()) return@detectTapGestures
                                        layout?.let { l ->
                                            val off = l.getOffsetForPosition(pos).coerceIn(0, annotated.length - 1)
                                            annotated.getStringAnnotations("URL", off, off).firstOrNull()?.let {
                                                linkDialogUrl = it.item
                                            } ?: annotated.getStringAnnotations("COPY", off, off).firstOrNull()?.let {
                                                copyRaw(it.item)
                                            }
                                        }
                                    }
                                },
                        )
                        SelectionBubble(
                            selectedText = selectedText,
                            fullText = segment.rawText(),
                            layout = layout,
                            selection = sel,
                            textLength = annotated.length,
                            onClearSelection = { sel = null },
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

// ─── 选中气泡：贴在选区旁的浮动条（不指望系统条；定位原理同 TextToolbar.showMenu(rect)） ──
// 放在包着 BasicTextField 的 Box 里当第二个孩子，offset 锚点 = 选区首字符包围盒。

@Composable
internal fun SelectionBubble(
    selectedText: String?,
    fullText: String,
    layout: TextLayoutResult?,
    selection: TextRange?,
    textLength: Int,
    onClearSelection: () -> Unit,
) {
    if (selectedText.isNullOrEmpty()) return
    val l = layout ?: return
    val r = selection ?: return
    if (r.collapsed) return
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedLabel = AppLocale.strings.copied
    fun copy(t: String) {
        clipboard.setText(AnnotatedString(t))
        Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
    }
    val density = LocalDensity.current
    // 锚点：选区首行顶部，气泡坐它头上；顶到头了就改坐选区尾行脚下
    val bubbleOffset: IntOffset = remember(r, l, textLength, density) {
        try {
            val start = r.start.coerceIn(0, textLength)
            val end = r.end.coerceIn(0, textLength)
            if (end <= start || textLength <= 0) return@remember null
            val line = l.getLineForOffset(start.coerceIn(0, textLength - 1))
            val charBox = l.getBoundingBox(start.coerceIn(0, textLength - 1))
            val gapPx = with(density) { 10.dp.toPx() }
            val bubbleHPx = with(density) { 48.dp.toPx() }
            var yPx = l.getLineTop(line) - bubbleHPx - gapPx
            if (yPx < 0) {
                val endLine = l.getLineForOffset(end.coerceIn(0, textLength - 1))
                yPx = l.getLineBottom(endLine) + gapPx
            }
            val maxX = (l.size.width - with(density) { 200.dp.toPx() }).coerceAtLeast(0f)
            IntOffset(charBox.left.coerceIn(0f, maxX).toInt(), yPx.toInt())
        } catch (_: Exception) {
            null
        }
    } ?: return
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        // Box 默认 TopStart 对齐：offset 直接就是相对段首的锚点位
        modifier = Modifier.offset { bubbleOffset },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                text = "复制 ${selectedText.length}字",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier
                    .clickable {
                        copy(selectedText)
                        onClearSelection()
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
            if (fullText.isNotEmpty()) {
                Text(
                    text = "整段",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable { copy(fullText) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier
                    .clickable { onClearSelection() }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}