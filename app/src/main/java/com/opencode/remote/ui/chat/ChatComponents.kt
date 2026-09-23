package com.opencode.remote.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.remote.data.api.dto.MessageInfo
import com.opencode.remote.data.api.dto.MessagePart
import com.opencode.remote.data.api.dto.ModelInfo
import com.opencode.remote.data.datastore.TemplateEntry
import com.opencode.remote.ui.strings.AppLocale
import kotlinx.coroutines.delay

// ─── Message Segment Parsing ─────────────────────────────────────────────

/** Parse completed message parts into display segments. */
internal fun parseMessageSegments(message: MessageInfo): List<ResponseSegment> {
    return message.parts
        .filter { it.type in listOf(
            "reasoning", "text", "tool-invocation", "tool-call", "tool",
            "file", "agent", "snapshot", "patch", "retry", "compaction", "subtask",
        ) }
        .filter { part ->
            // Keep tool parts even if text is empty — they have structured data
            if (part.type == "tool") true
            else if (part.type == "file") true  // file parts have name/path, not text
            else !part.text.isNullOrBlank()
        }
        .map { part ->
            val segType = when (part.type) {
                "reasoning" -> "thinking"
                "text" -> "text"
                "file" -> "file"
                else -> "tool"
            }
            val displayText = when (segType) {
                "tool" -> ToolSummarizer.summarize(part)
                "file" -> part.name ?: "File"
                else -> part.text ?: ""
            }
            ResponseSegment(type = segType, text = displayText, isStreaming = false)
        }
}

// ─── User Message Item ────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserMessageItem(message: MessageInfo) {
    val s = AppLocale.strings
    val fullText = message.parts
        .filter { it.type == "text" && !it.text.isNullOrBlank() }
        .joinToString("\n") { it.text!! }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                s.me,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            // 同 AI 段：只读框拿选区，选中后气泡贴选区（不用滑下去找）
            var sel by remember(message.id) { mutableStateOf<TextRange?>(null) }
            var userLayout by remember(message.id) { mutableStateOf<TextLayoutResult?>(null) }
            val selectedText = sel?.let { r ->
                val a = r.start.coerceIn(0, fullText.length)
                val b = r.end.coerceIn(0, fullText.length)
                if (b > a) fullText.substring(a, b) else null
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = TextFieldValue(text = fullText, selection = sel ?: TextRange.Zero),
                    onValueChange = { sel = it.selection.takeIf { s -> !s.collapsed } },
                    readOnly = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    onTextLayout = { userLayout = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                SelectionBubble(
                    selectedText = selectedText,
                    fullText = fullText,
                    layout = userLayout,
                    selection = sel,
                    textLength = fullText.length,
                    onClearSelection = { sel = null },
                )
            }
            // 已发附件行：不然发出去了自己都不知道发了哪张
            message.parts
                .filter { it.type == "file" && (!it.text.isNullOrBlank() || !it.name.isNullOrBlank()) }
                .forEach { part ->
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = part.name ?: part.text ?: "File",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
        }
    }
}

// ─── AI Response Panel ────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AiResponsePanel(
    agentName: String,
    segments: List<ResponseSegment>,
    isStreaming: Boolean,
    messageId: String? = null,
    speaking: Boolean = false,
    onSpeakToggle: (() -> Unit)? = null,
    onSpeakSettings: (() -> Unit)? = null,
) {
    val s = AppLocale.strings

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    agentName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                // 喇叭：点朗读/停；长按进引擎手选（默认引擎是僵尸时亲手点名）
                if (messageId != null && onSpeakToggle != null) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .combinedClickable(
                                onClick = onSpeakToggle,
                                onLongClick = onSpeakSettings,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (speaking) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (speaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            segments.forEachIndexed { idx, seg ->
                val isLast = idx == segments.lastIndex
                when (seg.type) {
                    "thinking" -> {
                        val isActive = seg.isStreaming && isLast && isStreaming
                        ExpandableSegment(
                            text = seg.text,
                            isStreaming = isActive,
                            label = if (isActive) s.thinkingActive else s.thought,
                            icon = Icons.Default.Psychology,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            showDuration = true,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    "tool" -> {
                        ExpandableSegment(
                            text = seg.text,
                            isStreaming = seg.isStreaming && isLast,
                            label = ToolSummarizer.summarizeText(seg.text),
                            icon = Icons.Default.Build,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    "file" -> {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = seg.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    else -> {
                        MarkdownText(
                            text = seg.text,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            if (isStreaming) {
                val cursorAlpha by rememberInfiniteTransition(label = "cursor").animateFloat(
                    initialValue = 1f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(530, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                )
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha)),
                )
            }
        }
    }
}

// ─── Expandable Segment (for thinking / tool) ─────────────────────────────

@Composable
internal fun ExpandableSegment(
    text: String,
    isStreaming: Boolean,
    label: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    showDuration: Boolean = false,
) {
    val s = AppLocale.strings
    // Default collapsed for all segments. Streaming segments auto-expand via
    // LaunchedEffect(isStreaming) below. The "disappear" bug is handled by the
    // ViewModel keeping streaming segments visible until the message is confirmed.
    var expanded by remember { mutableStateOf(false) }
    var startTime by remember { mutableStateOf<Long?>(null) }
    var durationSec by remember { mutableStateOf<Int?>(null) }

    // Auto-expand on stream start, auto-collapse with delay on stream end
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            expanded = true
            if (startTime == null) startTime = System.currentTimeMillis()
        } else {
            if (startTime != null) {
                durationSec = ((System.currentTimeMillis() - startTime!!) / 1000).toInt()
                startTime = null
            }
            // Don't auto-collapse on stream end — keep content visible.
            // The user can manually collapse if they want.
        }
    }

    val displayLabel = when {
        !showDuration -> label
        isStreaming -> label  // "Thinking..." passed from caller
        durationSec != null && durationSec!! > 0 -> s.thoughtForSeconds.replace("%d", durationSec.toString())
        else -> label  // "Thought" passed from caller
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = contentColor,
                    )
                    Spacer(Modifier.width(6.dp))
                } else {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = contentColor,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = displayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) s.collapse else s.expand,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor.copy(alpha = 0.6f),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 8.dp, bottom = 8.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = containerColor.copy(alpha = 0.6f),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            var segSel by remember(text) { mutableStateOf<TextRange?>(null) }
                            var segLayout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
                            val segSelected = segSel?.let { r ->
                                val a = r.start.coerceIn(0, text.length)
                                val b = r.end.coerceIn(0, text.length)
                                if (b > a) text.substring(a, b) else null
                            }
                            Box(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                BasicTextField(
                                    value = TextFieldValue(text = text, selection = segSel ?: TextRange.Zero),
                                    onValueChange = { segSel = it.selection.takeIf { s -> !s.collapsed } },
                                    readOnly = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = contentColor,
                                    ),
                                    cursorBrush = SolidColor(contentColor),
                                    onTextLayout = { segLayout = it },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                SelectionBubble(
                                    selectedText = segSelected,
                                    fullText = text,
                                    layout = segLayout,
                                    selection = segSel,
                                    textLength = text.length,
                                    onClearSelection = { segSel = null },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Chat Input Bar ───────────────────────────────────────────────────────

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    selectedModel: ModelInfo?,
    onOpenSettings: () -> Unit,
    contextUsageK: String,
    onScrollToBottom: () -> Unit = {},
    onHistoryPrev: () -> Boolean = { false },
    onHistoryNext: () -> Boolean = { false },
    // 快捷模板 Tier 1：一键发常用语（标题显示、内容发送）
    templates: List<TemplateEntry> = emptyList(),
    isCustomTemplate: (String) -> Boolean = { false },
    onTemplateSend: (TemplateEntry) -> Unit = {},
    onTemplateAdd: () -> Unit = {},
    onTemplateEdit: (TemplateEntry) -> Unit = {},
    // 附件（图片/文件，先传后引）
    attachments: List<AttachedFile> = emptyList(),
    isUploading: Boolean = false,
    onAttachClick: () -> Unit = {},
    onAttachLongClick: () -> Unit = {},
    onRemoveAttachment: (AttachedFile) -> Unit = {},
) {
    val s = AppLocale.strings

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // ── Piano-key strip (top row) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left key — scroll to bottom
                val leftKeyInteractionSource = remember { MutableInteractionSource() }
                val leftKeyPressed by leftKeyInteractionSource.interactions.collectAsState(
                    initial = null
                )
                val isLeftKeyPressed = leftKeyPressed is PressInteraction.Press
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(40.dp)
                        .clickable(
                            interactionSource = leftKeyInteractionSource,
                            indication = null,
                        ) { onScrollToBottom() }
                        .background(
                            if (isLeftKeyPressed) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            } else {
                                Color.Transparent
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Divider 1
                HorizontalDivider(
                    modifier = Modifier
                        .height(40.dp)
                        .width(0.5.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                )

                // Middle key — Settings trigger
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(40.dp)
                        .clickable { onOpenSettings() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = selectedModel?.name ?: s.selectionConfigure,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Divider 2
                HorizontalDivider(
                    modifier = Modifier
                        .height(40.dp)
                        .width(0.5.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                )

                // Right key — context usage (display only)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DataUsage,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = contextUsageK,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── 快捷模板行 Tier 1：点即发；+ 存当前输入；长按删自定义 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                templates.forEach { t ->
                    val custom = isCustomTemplate(t.title)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 1.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.combinedClickable(
                            onClick = { onTemplateSend(t) },
                            onLongClick = if (custom) ({ onTemplateEdit(t) }) else null,
                        ),
                    ) {
                        Text(
                            text = t.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                // +：把当前输入框内容存成模板
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 1.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.clickable { onTemplateAdd() },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── 附件预览行：已传文件名 + ✕；上传中转圈 ──
            if (attachments.isNotEmpty() || isUploading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    attachments.forEach { f ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            tonalElevation = 1.dp,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = f.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                IconButton(
                                    onClick = { onRemoveAttachment(f) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            // ── Text input row (bottom) — no separator between strip and input ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    placeholder = { Text(s.inputPlaceholder) },
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) false
                            else when (e.key) {
                                // #14 输入历史：硬键盘上下键翻历史
                                Key.DirectionUp -> onHistoryPrev()
                                Key.DirectionDown -> onHistoryNext()
                                else -> false
                            }
                        },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    trailingIcon = {
                        // #14 软键盘没有方向键：可点的上下键
                        Row {
                            IconButton(
                                onClick = { onHistoryPrev() },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ExpandLess,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            IconButton(
                                onClick = { onHistoryNext() },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                )

                // 回形针：点选图片，长按选任意文件（先传后引，随消息发出）
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .combinedClickable(
                            onClick = onAttachClick,
                            onLongClick = onAttachLongClick,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                FilledIconButton(
                    onClick = onSend,
                    enabled = inputText.isNotBlank() && !isSending,
                    modifier = Modifier.size(48.dp),
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Default.Send, contentDescription = s.connectButton)
                    }
                }
            }
        }
    }
}

// ─── 快捷模板编辑框：标题（chip 显示）与内容（实际发送）可不同 ───────────

@Composable
internal fun TemplateEditDialog(
    initialTitle: String,
    initialContent: String,
    canDelete: Boolean,
    onConfirm: (String, String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = AppLocale.strings
    var title by remember(initialTitle) { mutableStateOf(initialTitle) }
    var content by remember(initialContent) { mutableStateOf(initialContent) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.templateDialogTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(s.templateTitleLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(s.templateContentLabel) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, content) },
                enabled = title.isNotBlank() && content.isNotBlank(),
            ) { Text(s.selectionConfirm) }
        },
        dismissButton = {
            Row {
                if (canDelete) {
                    TextButton(onClick = onDelete) {
                        Text(s.delete, color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(s.selectionCancel) }
            }
        },
    )
}

// ─── TTS 引擎手选：长按喇叭进入，亲手点名（默认引擎是僵尸时用） ────────────

@Composable
internal fun TtsEngineDialog(
    engines: List<TtsEngineInfo>,
    currentPkg: String?,
    systemDefault: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = AppLocale.strings
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("朗读引擎") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TtsEngineRow(
                    selected = currentPkg == null,
                    title = "自动选择",
                    sub = systemDefault?.let { "系统默认：$it" } ?: "跟随系统默认",
                    onClick = { onPick(null) },
                )
                engines.forEach { e ->
                    TtsEngineRow(
                        selected = currentPkg == e.pkg,
                        title = e.label,
                        sub = e.pkg,
                        onClick = { onPick(e.pkg) },
                    )
                }
                if (engines.isEmpty()) {
                    Text(
                        text = "没扫到引擎：先装 sherpa 引擎 APK",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(s.close) }
        },
    )
}

@Composable
private fun TtsEngineRow(
    selected: Boolean,
    title: String,
    sub: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = if (selected) 2.dp else 0.dp,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}