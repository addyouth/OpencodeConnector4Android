package com.opencode.remote.ui.sessions

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import com.opencode.remote.data.api.dto.SessionInfo
import com.opencode.remote.data.api.dto.WorktreeInfo
import com.opencode.remote.data.api.dto.SessionSummary
import com.opencode.remote.data.sessionstore.SessionStatus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencode.remote.ui.components.ErrorSnackbar
import com.opencode.remote.ui.strings.AppLocale
import com.opencode.remote.ui.util.TimeFormatter
import com.opencode.remote.ui.util.TimeGroup
import com.opencode.remote.ui.strings.AppStrings

// ─── TimeGroup label helper ──────────────────────────────────────────

private fun TimeGroup.label(s: AppStrings): String = when (this) {
    TimeGroup.TODAY -> s.timeToday
    TimeGroup.YESTERDAY -> s.timeYesterday
    TimeGroup.THIS_WEEK -> s.timeThisWeek
    TimeGroup.OLDER -> s.timeOlder
}

// ─── Level 1: Projects List ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    onProjectClick: (String) -> Unit,
    onDisconnected: () -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
    onDirectChat: (String, String?) -> Unit = { _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val s = AppLocale.strings

    // Auto-refresh when navigating back to this screen
    LifecycleResumeEffect(Unit) {
        viewModel.loadSessions()
        onPauseOrDispose { /* no-op */ }
    }

    // 启动直达置顶：开屏一次，第一个钉住的会话还在就直跳聊天（被删则静默回落列表）
    var directDone by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.pinned, uiState.autoDirectPin, uiState.allSessions) {
        if (!directDone && uiState.autoDirectPin) {
            val pin = uiState.pinned.firstOrNull()
            val found = pin?.let { p -> uiState.allSessions.find { it.id == p.id } }
            if (pin != null && found != null) {
                directDone = true
                onDirectChat(pin.id, found.resolvedDirectory ?: pin.dir)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(s.projects)
                        val subtitle = uiState.currentServerName
                            ?: uiState.projectName?.let { "${s.serverPath}: ${it.replace('\\', '/').substringAfterLast('/')}" }
                        subtitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleDensity() }) {
                        Icon(
                            imageVector = if (uiState.listDensity == ListDensity.COMPACT) Icons.Default.ViewAgenda else Icons.Default.ViewHeadline,
                            contentDescription = if (uiState.listDensity == ListDensity.COMPACT) s.densityDefault else s.densityCompact,
                        )
                    }
                    IconButton(onClick = { viewModel.toggleDarkMode() }) {
                        Icon(
                            if (AppLocale.darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = if (AppLocale.darkMode) "Light mode" else "Dark mode",
                        )
                    }
                    IconButton(onClick = { viewModel.loadSessions() }) {
                        Icon(Icons.Default.Refresh, contentDescription = s.refresh)
                    }
                    IconButton(onClick = onDisconnected) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = s.disconnect)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                uiState.sessions.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = s.noProjects,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    val statusMap by viewModel.sessionStatusMap.collectAsStateWithLifecycle()

                    val grouped = remember(uiState.sessions) {
                        uiState.sessions
                            .groupBy { it.resolvedDirectory ?: "unknown" }
                            .mapValues { (_, sessions) ->
                                sessions.sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
                            }
                            .toList()
                            .sortedByDescending { (_, sessions) ->
                                sessions.maxOfOrNull { it.time?.updated ?: it.time?.created ?: 0L } ?: 0L
                            }
                    }

                    // Precompute busy state per directory
                    val busyDirectories = remember(statusMap, grouped) {
                        grouped
                            .filter { (_, sessions) ->
                                sessions.any { statusMap[it.id] == SessionStatus.BUSY }
                            }
                            .map { (dir, _) -> dir }
                            .toSet()
                    }

                    val timeGrouped = remember(grouped) {
                        grouped
                            .groupBy { (_, sessions) ->
                                val latestTs = sessions.maxOfOrNull { it.time?.updated ?: it.time?.created ?: 0L }
                                if (latestTs != null && latestTs > 0L) TimeFormatter.classifyTimeGroup(latestTs)
                                else TimeGroup.OLDER
                            }
                            .toList()
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // 置顶卡：钉住的会话各一张，点即直达聊天（被删的不显示，静默回落）
                        uiState.pinned.forEach { pin ->
                            uiState.allSessions.find { it.id == pin.id }?.let { pinned ->
                                item(key = "pinned_${pin.id}") {
                                    PinnedSessionCard(
                                        session = pinned,
                                        autoDirect = uiState.autoDirectPin,
                                        onOpen = { onDirectChat(pin.id, pinned.resolvedDirectory ?: pin.dir) },
                                        onUnpin = { viewModel.togglePin(pin.id, null) },
                                        onAutoDirectChange = viewModel::setAutoDirectPin,
                                        modifier = Modifier.animateItemPlacement(tween(300)),
                                    )
                                }
                            }
                        }
                        timeGrouped.forEach { (timeGroup, projects) ->
                            stickyHeader(key = timeGroup.name) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = timeGroup.label(s),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                            }
                            items(
                                items = projects,
                                key = { (dir, _) -> dir }
                            ) { (directory, sessions) ->
                                ProjectCard(
                                    directory = directory,
                                    sessionCount = sessions.size,
                                    hasBusySessions = directory in busyDirectories,
                                    onClick = { onProjectClick(directory) },
                                    modifier = Modifier.animateItemPlacement(tween(300)),
                                )
                            }
                        }
                    }
                }
            }

            ErrorSnackbar(
                error = uiState.error,
                onDismiss = viewModel::clearError,
            )
        }
    }
}

@Composable
private fun ProjectCard(
    directory: String,
    sessionCount: Int,
    hasBusySessions: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = AppLocale.strings
    val folderName = directory.replace('\\', '/').substringAfterLast('/')

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = folderName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hasBusySessions) {
                        Spacer(modifier = Modifier.width(8.dp))
                        BusyProjectDot()
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = directory,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$sessionCount ${s.sessions}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── 置顶卡：钉住的主会话 + 启动直达开关 ─────────────────────────────────

@Composable
private fun PinnedSessionCard(
    session: SessionInfo,
    autoDirect: Boolean,
    onOpen: () -> Unit,
    onUnpin: () -> Unit,
    onAutoDirectChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = AppLocale.strings
    val dir = session.resolvedDirectory
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s.pinnedSection,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = session.title ?: session.slug ?: "Session ${session.id.take(8)}...",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    dir?.let {
                        Text(
                            text = it.replace('\\', '/').substringAfterLast('/'),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onUnpin) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = s.unpinSession,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = s.autoDirectPin,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = autoDirect,
                    onCheckedChange = onAutoDirectChange,
                )
            }
        }
    }
}

// ─── Level 2: Sessions within a Project ──────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectSessionsScreen(
    directory: String,
    onSessionClick: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SessionsViewModel,  // Shared with SessionsScreen — passed from AppNavigation
    onTerminalClick: (String) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val s = AppLocale.strings
    val folderName = directory.replace('\\', '/').substringAfterLast('/')
    val density = LocalDensity.current

    // ── Memo panel gesture + animation (same pattern as ChatScreen file panel) ──
    var cumulativeDragX by remember { mutableFloatStateOf(0f) }
    val dragThresholdPx = with(density) { 40.dp.toPx() }
    val panelWidthDp = 280.dp
    val memoPanelOffset by animateDpAsState(
        targetValue = if (uiState.isMemoPanelOpen) 0.dp else panelWidthDp,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "memo_panel_offset",
    )
    val memoContentOffset by animateDpAsState(
        targetValue = if (uiState.isMemoPanelOpen) -panelWidthDp else 0.dp,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "memo_content_offset",
    )

    // Auto-refresh when navigating back to this screen
    LifecycleResumeEffect(Unit) {
        viewModel.loadSessions()
        onPauseOrDispose { /* no-op */ }
    }

    // Auto-navigate when a new session is created
    LaunchedEffect(Unit) {
        viewModel.creationEvents.collect { newSessionId ->
            onSessionClick(newSessionId)
        }
    }

    val projectSessions = remember(uiState.allSessions, directory) {
        val inDir = uiState.allSessions.filter { it.resolvedDirectory == directory }
        val ids = inDir.map { it.id }.toSet()
        // 顶层规则：无父引用，或父不在本项目（搬过来的孤儿子会话顶层展示）；
        // 父在身边的只挂父展开项下，避免双份
        inDir.filter { s ->
            val p = s.fork?.sessionID ?: s.parentID
            p.isNullOrBlank() || p !in ids
        }.sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
    }

    // Rename / Move dialog targets (declared here: used by cards above AND dialogs below)
    var renameTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var moveTarget by remember { mutableStateOf<SessionInfo?>(null) }

    // Outer Box with swipe gesture detection for memo panel
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { cumulativeDragX = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        cumulativeDragX += dragAmount
                        if (!uiState.isMemoPanelOpen && cumulativeDragX < -dragThresholdPx) {
                            change.consume()
                            viewModel.setMemoPanelOpen(true, directory)
                        } else if (uiState.isMemoPanelOpen && cumulativeDragX > dragThresholdPx) {
                            change.consume()
                            viewModel.closeMemoPanel()
                        }
                    },
                    onDragEnd = { cumulativeDragX = 0f },
                    onDragCancel = { cumulativeDragX = 0f },
                )
            }
    ) {
        // Main content — shifts left when memo panel opens
        Box(modifier = Modifier.offset(x = memoContentOffset)) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(folderName)
                                Text(
                                    text = directory,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                if (uiState.isMemoPanelOpen) viewModel.closeMemoPanel() else onBack()
                            }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = s.helpBack)
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.toggleDensity() }) {
                                Icon(
                                    imageVector = if (uiState.listDensity == ListDensity.COMPACT) Icons.Default.ViewAgenda else Icons.Default.ViewHeadline,
                                    contentDescription = if (uiState.listDensity == ListDensity.COMPACT) s.densityDefault else s.densityCompact,
                                )
                            }
                            IconButton(onClick = { viewModel.openWorktrees(directory) }) {
                                Icon(Icons.Default.Folder, contentDescription = s.worktreeTitle)
                            }
                            IconButton(onClick = { onTerminalClick(directory) }) {
                                Icon(Icons.Default.Terminal, contentDescription = s.terminalTitle)
                            }
                            IconButton(onClick = { viewModel.loadSessions() }) {
                                Icon(Icons.Default.Refresh, contentDescription = s.refresh)
                            }
                        },
                    )
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.createSession(directory) },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text(s.newSession) },
                        expanded = projectSessions.isEmpty(),
                    )
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    when {
                        uiState.isLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }

                        projectSessions.isEmpty() -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChatBubbleOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = s.noSessions,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = s.noSessionsHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        else -> {
                            val isSearching = uiState.searchQuery.isNotBlank()
                            val statusMap by viewModel.sessionStatusMap.collectAsStateWithLifecycle()
                            val childrenMap by viewModel.childrenMap.collectAsStateWithLifecycle()
                            val allProjectSessionsMap = remember(uiState.sessions) {
                                viewModel.allSessionsForProject(directory).associateBy { it.id }
                            }
                            val flatRenderSessions = remember(projectSessions, childrenMap, uiState.expandedParents) {
                                excludeChildrenOfExpandedParents(projectSessions, childrenMap, uiState.expandedParents)
                            }
                            val timeGroupedSessions = remember(flatRenderSessions) {
                                groupSessionsByTime(flatRenderSessions).toList()
                            }
                            val filteredSessions = if (isSearching) {
                                projectSessions.filter {
                                    it.title?.contains(uiState.searchQuery, ignoreCase = true) == true
                                        || it.slug?.contains(uiState.searchQuery, ignoreCase = true) == true
                                        || it.id.contains(uiState.searchQuery, ignoreCase = true)
                                }
                            } else {
                                emptyList()
                            }

                            Column(modifier = Modifier.fillMaxSize()) {
                                // T10: Search bar
                                OutlinedTextField(
                                    value = uiState.searchQuery,
                                    onValueChange = { viewModel.setSearchQuery(it) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    placeholder = { Text(s.searchSessions) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Search, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        if (uiState.searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                                Icon(Icons.Default.Close, contentDescription = null)
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.large,
                                )

                                if (isSearching && filteredSessions.isEmpty()) {
                                    // T10: No results
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .weight(1f),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = s.noSearchResults,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        // 底部留白 88dp：末卡片不再叠进 FAB 区，误触顶部卡片类彻底排除
                                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        if (isSearching) {
                                            items(
                                                items = filteredSessions,
                                                key = { it.id }
                                            ) { session ->
                                                SessionCard(
                                                    session = session,
                                                    status = statusMap[session.id],
                                                    density = uiState.listDensity,
                                                    onClick = { onSessionClick(session.id) },
                                                    onDelete = { viewModel.deleteSession(session.id, directory) },
                                                    onFork = { viewModel.forkSession(session.id, directory) },
                                                    modifier = Modifier.animateItemPlacement(tween(300)),
                                                )
                                            }
                                        } else {
                                            timeGroupedSessions.forEach { (timeGroup, sessions) ->
                                                stickyHeader(key = "${timeGroup.name}_header") {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                                                        tonalElevation = 1.dp,
                                                        modifier = Modifier.fillMaxWidth(),
                                                    ) {
                                                        Text(
                                                            text = timeGroup.label(s),
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            fontWeight = FontWeight.SemiBold,
                                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                                        )
                                                    }
                                                }
                                                items(
                                                    items = sessions,
                                                    key = { it.id }
                                                ) { session ->
                                                    val hasChildren = when {
                                                        session.parentID != null -> false
                                                        !viewModel.shouldRefreshChildren(session.id) -> childrenMap[session.id]?.isNotEmpty() == true
                                                        else -> true
                                                    }
                                                    val isExpanded = session.id in uiState.expandedParents

                                                    SessionCard(
                                                        session = session,
                                                        status = statusMap[session.id],
                                                        density = uiState.listDensity,
                                                        onClick = { onSessionClick(session.id) },
                                                        onDelete = { viewModel.deleteSession(session.id, directory) },
                                                        onFork = { viewModel.forkSession(session.id, directory) },
                                                        onRename = { renameTarget = session },
                                                        onMove = { moveTarget = session },
                                                        onPin = { viewModel.togglePin(session.id, session.resolvedDirectory) },
                                                        isPinned = uiState.pinned.any { it.id == session.id },
                                                        hasChildren = hasChildren,
                                                        isExpanded = isExpanded,
                                                        onToggleExpand = {
                                                            viewModel.toggleExpand(session.id)
                                                            // 每次展开都刷（子 fork 可能在折叠时新增；once-only 会导致三角 permanently 消失）
                                                            if (!isExpanded) {
                                                                viewModel.refreshChildSessions(session.id)
                                                            }
                                                        },
                                                        modifier = Modifier.animateItemPlacement(tween(300)),
                                                    )

                                                    // Show child sessions when expanded
                                                    if (hasChildren && isExpanded) {
                                                        val childIds = childrenMap[session.id] ?: emptySet()
                                                        childIds.forEach { childId ->
                                                            val childSession = allProjectSessionsMap[childId]
                                                            if (childSession != null) {
                                                                SessionCard(
                                                                    session = childSession,
                                                                    status = statusMap[childId],
                                                                    density = uiState.listDensity,
                                                                    onClick = { onSessionClick(childId) },
                                                                    onDelete = { viewModel.deleteSession(childId, directory) },
                                                                    onFork = { viewModel.forkSession(childId, directory) },
                                                                    onRename = { renameTarget = childSession },
                                                                    onMove = { moveTarget = childSession },
                                                                    onPin = { viewModel.togglePin(childId, childSession.resolvedDirectory) },
                                                                    isPinned = uiState.pinned.any { it.id == childId },
                                                                    isChild = true,
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

                    ErrorSnackbar(
                        error = uiState.error,
                        onDismiss = viewModel::clearError,
                    )

                    // Worktree manager dialog
                    if (uiState.showWorktreeDialog) {
                        WorktreeDialog(
                            title = s.worktreeTitle,
                            dirs = uiState.worktreeDirs,
                            isLoading = uiState.isLoadingWorktrees,
                            branchHint = s.worktreeBranch,
                            nameHint = s.worktreeName,
                            closeText = s.close,
                            needRepoText = s.worktreeNeedRepo,
                            canCreate = uiState.worktreeHasGit != false,
                            onRefresh = viewModel::refreshWorktrees,
                            onCreate = viewModel::createWorktree,
                            onDelete = viewModel::removeWorktree,
                            onDismiss = viewModel::closeWorktrees,
                        )
                    }

                    // Rename dialog
                    var renameText by remember(renameTarget) { mutableStateOf(renameTarget?.title ?: "") }
                    renameTarget?.let { target ->
                        AlertDialog(
                            onDismissRequest = { renameTarget = null },
                            title = { Text(s.renameSession) },
                            text = {
                                OutlinedTextField(
                                    value = renameText,
                                    onValueChange = { renameText = it },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    viewModel.renameSession(target.id, renameText.trim())
                                    renameTarget = null
                                }) { Text(s.selectionConfirm) }
                            },
                            dismissButton = {
                                TextButton(onClick = { renameTarget = null }) { Text(s.selectionCancel) }
                            },
                        )
                    }

                    // Move dialog (pick target project directory)
                    moveTarget?.let { target ->
                        val dirs = remember { viewModel.projectDirectories() }
                        AlertDialog(
                            onDismissRequest = { moveTarget = null },
                            title = { Text(s.moveSession) },
                            text = {
                                Column(
                                    modifier = Modifier.verticalScroll(rememberScrollState()),
                                ) {
                                    dirs.forEach { dir ->
                                        val selected = dir == target.resolvedDirectory
                                        TextButton(
                                            onClick = {
                                                if (!selected) viewModel.moveSession(target.id, dir)
                                                moveTarget = null
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(
                                                text = (if (selected) "● " else "") + dir.replace('\\', '/').substringAfterLast('/'),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                TextButton(onClick = { moveTarget = null }) { Text(s.selectionCancel) }
                            },
                        )
                    }
                }
            }
        }

        // Memo panel — slides in from right edge
        if (uiState.isMemoPanelOpen || memoPanelOffset < panelWidthDp) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = memoPanelOffset)
            ) {
                MemoPanel(
                    projectName = folderName,
                    memos = uiState.memos,
                    onAdd = { viewModel.addMemo(directory) },
                    onUpdate = { viewModel.updateMemo(directory, it) },
                    onDelete = { viewModel.deleteMemo(directory, it) },
                    onClose = { viewModel.closeMemoPanel() },
                )
            }
        }
    }
}

@Composable
private fun WorktreeDialog(
    title: String,
    dirs: List<WorktreeInfo>,
    isLoading: Boolean,
    branchHint: String,
    nameHint: String,
    closeText: String,
    needRepoText: String,
    canCreate: Boolean,
    onRefresh: () -> Unit,
    onCreate: (branch: String, name: String) -> Unit,
    onDelete: (directory: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var branch by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        },
        text = {
            Column {
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(dirs, key = { it.directory }) { w ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = w.directory.replace('\\', '/').substringAfterLast('/').ifEmpty { w.directory },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { onDelete(w.directory) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (!canCreate) {
                    Text(
                        text = needRepoText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedTextField(
                    value = branch,
                    onValueChange = { branch = it },
                    placeholder = { Text(branchHint) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(nameHint) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(branch.trim(), name.trim())
                    branch = ""
                    name = ""
                },
                enabled = canCreate && branch.isNotBlank(),
            ) { Text(branchHint) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(closeText) }
        },
    )
}

@Composable
private fun SessionCard(
    session: SessionInfo,
    status: SessionStatus?,
    density: ListDensity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onFork: () -> Unit,
    onRename: () -> Unit = {},
    onMove: () -> Unit = {},
    onPin: () -> Unit = {},
    isPinned: Boolean = false,
    isChild: Boolean = false,
    hasChildren: Boolean = false,
    isExpanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isCompact = density == ListDensity.COMPACT
    val cardPadding = if (isCompact) 8.dp else 16.dp
    val spacerWidth = if (isCompact) 8.dp else 16.dp
    val titleStyle = if (isCompact) {
        MaterialTheme.typography.labelMedium
    } else {
        MaterialTheme.typography.titleSmall
    }
    val bodyStyle = if (isCompact) {
        MaterialTheme.typography.labelSmall
    } else {
        MaterialTheme.typography.bodySmall
    }
    val labelStyle = if (isCompact) {
        MaterialTheme.typography.labelSmall
    } else {
        MaterialTheme.typography.labelSmall
    }
    val spacerHeight = if (isCompact) 2.dp else 4.dp

    var showMenu by remember { mutableStateOf(false) }
    val s = AppLocale.strings

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isChild) Modifier.padding(start = 24.dp) else Modifier)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isChild) 0.5.dp else 1.dp),
        border = if (isChild) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // T11: Status indicator dot with pulse animation for busy state
            StatusDot(status = status)

            Spacer(modifier = Modifier.width(spacerWidth))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title ?: session.slug ?: "Session ${session.id.take(8)}...",
                    style = titleStyle,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(spacerHeight))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = TimeFormatter.formatRelativeTime(session.time?.updated),
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // T12: Summary statistics (+N/-M/Ff)
                    SessionSummaryStats(summary = session.summary)
                }
                Spacer(modifier = Modifier.height(spacerHeight))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val isCompleted = session.time?.completed != null && session.time.completed > 0
                    Text(
                        text = if (isCompleted) "COMPLETED" else "ACTIVE",
                        style = labelStyle,
                        color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary,
                    )
                    session.version?.let { ver ->
                        Text(
                            text = "v$ver",
                            style = labelStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Expand/collapse chevron for parent sessions with children
            if (hasChildren && onToggleExpand != null) {
                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) s.collapseChildren else s.expandChildren,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = s.moreActions,
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(s.forkSession) },
                        leadingIcon = { Icon(Icons.Default.ForkRight, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onFork()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s.renameSession) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s.moveSession) },
                        leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onMove()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (isPinned) s.unpinSession else s.pinSession) },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onPin()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s.delete, color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

// ─── T11: Status Dot ─────────────────────────────────────────────────────

private val StatusGreen = Color(0xFF4CAF50)
private val StatusYellow = Color(0xFFFFC107)
private val StatusGray = Color(0xFF757575)

@Composable
private fun StatusDot(status: SessionStatus?) {
    val isBusy = status == SessionStatus.BUSY

    // Pulse animation for busy state
    val infiniteTransition = rememberInfiniteTransition(label = "status_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "status_alpha",
    )

    val dotColor = when (status) {
        SessionStatus.BUSY -> StatusGreen
        SessionStatus.IDLE -> StatusGray
        null -> StatusGray
    }

    val statusLabel = when (status) {
        SessionStatus.BUSY -> "busy"
        SessionStatus.IDLE -> "idle"
        null -> "unknown"
    }

    Box(
        modifier = Modifier
            .size(12.dp)
            .semantics { contentDescription = "Session status: $statusLabel" }
            .background(
                color = dotColor,
                shape = CircleShape,
            )
            .then(
                if (isBusy) Modifier.alpha(pulseAlpha) else Modifier,
            ),
    )
}

/**
 * Green pulsing dot for project cards — shown when any session in the project is busy.
 * Reuses the same 800ms pulse animation as [StatusDot].
 */
@Composable
private fun BusyProjectDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "busy_project_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "busy_project_alpha",
    )

    Box(
        modifier = Modifier
            .size(12.dp)
            .background(color = StatusGreen, shape = CircleShape)
            .alpha(pulseAlpha),
    )
}

// ─── T12: Summary Statistics ─────────────────────────────────────────────

@Composable
private fun SessionSummaryStats(summary: SessionSummary?) {
    if (summary == null) return
    if (summary.additions == 0 && summary.deletions == 0 && summary.files == 0) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (summary.additions > 0) {
            Text(
                text = "+${summary.additions}",
                style = MaterialTheme.typography.labelSmall,
                color = StatusGreen,
                fontWeight = FontWeight.Medium,
            )
        }
        if (summary.deletions > 0) {
            Text(
                text = "-${summary.deletions}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
            )
        }
        if (summary.files > 0) {
            Text(
                text = "${summary.files}f",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
