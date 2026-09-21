package com.opencode.remote.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.opencode.remote.data.api.dto.FileNode

/** 文本预览白名单（二进制直接吐乱码，必须设防；图片以后走 Coil，另案）。 */
private val PREVIEWABLE_EXTENSIONS = setOf(
    "md", "markdown", "txt", "log",
    "json", "jsonc", "yaml", "yml", "toml", "xml",
    "html", "htm", "css", "js", "ts", "py",
    "sh", "ps1", "bat", "cmd", "csv", "ini", "cfg",
    "properties", "env", "gradle", "kt", "java", "c", "h",
)

internal fun isPreviewableFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext.isNotEmpty() && ext in PREVIEWABLE_EXTENSIONS
}

@Composable
fun FileTreeView(
    files: List<FileNode>,
    currentPath: String,
    onNavigateToDirectory: (String) -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    onNavigateUp: (() -> Unit)? = null,
    expandedFilePath: String? = null,
    expandedFileContent: String? = null,
    isLoadingFileContent: Boolean = false,
    onToggleFilePreview: ((FileNode) -> Unit)? = null,
    onOpenFile: ((FileNode) -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // 返回行独立于空态：空目录也要能返回上级（之前埋在 else 里，空目录无返回键）
        val showBack = currentPath != "." && currentPath.isNotEmpty() && onNavigateUp != null
        when {
            isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(32.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            files.isEmpty() -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (showBack) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateUp?.invoke() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Go back",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "..",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f, fill = false),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Empty directory",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // ".." / Go back row for navigating to parent directory
                    if (showBack) {
                        item(key = "__go_back__") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateUp?.invoke() }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "Go back",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "..",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    items(files, key = { it.path }) {
                        val file = it
                        val fileName = file.displayName
                        val isDirectory = file.type == "directory"
                        val isPreviewable = !isDirectory && isPreviewableFile(fileName)
                        val isExpanded = expandedFilePath == file.path

                        Column {
                            // File row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("file_item_${fileName}")
                                    .then(
                                        when {
                                            isDirectory -> Modifier.clickable { onNavigateToDirectory(file.path) }
                                            isPreviewable -> Modifier.clickable { onToggleFilePreview?.invoke(file) }
                                            else -> Modifier.clickable(enabled = onOpenFile != null) { onOpenFile?.invoke(file) }
                                        }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = when {
                                        isDirectory -> MaterialTheme.colorScheme.primary
                                        isPreviewable -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                // Show expand/collapse indicator for previewable files
                                if (isPreviewable) {
                                    Spacer(Modifier.weight(1f))
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            // Inline content preview
                            if (isExpanded) {
                                if (isLoadingFileContent) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                } else {
                                    expandedFileContent?.let { content ->
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 4.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(4.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 200.dp)
                                                    .verticalScroll(rememberScrollState())
                                                    .padding(8.dp)
                                            ) {
                                                SelectionContainer {
                                                    Text(
                                                        text = content,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
