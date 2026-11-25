package com.baytale.baychat.ui.screens.lobby

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.model.RoomSummary
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.core.util.stripLeadingEmoji
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayBottomSheet
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayCard
import com.baytale.baychat.ui.design.BayChip
import com.baytale.baychat.ui.design.BayEmptyState
import com.baytale.baychat.ui.design.BayIconButton
import com.baytale.baychat.ui.design.BayIconStyle
import com.baytale.baychat.ui.design.BayLargeTitleHeader
import com.baytale.baychat.ui.design.BayTextField
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val DISCOVER_PULL_DEBOUNCE_MS = 1500L
private const val DISCOVER_MIN_REFRESH_MS = 350L
private const val DISCOVER_REFRESH_TIMEOUT_MS = 8000L

private val PUBLIC_RETENTION_CHOICES = listOf(
    0 to "阅后即焚",
    1 to "1 天",
    3 to "3 天",
    7 to "7 天",
)

private val PUBLIC_FILE_LIMIT_CHOICES = listOf(
    0 to "不限",
    100 to "100MB",
    500 to "500MB",
)

private val PUBLIC_TOPIC_PRESETS = listOf(
    "文件互传",
    "原图交流",
    "资源共享",
    "随手聊聊",
)

/**
 * 「发现」广场 Tab：浏览公开房间、关键词过滤、下拉刷新与创建公开房。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverTab(
    state: BayChatUiState,
    vm: BayChatViewModel,
    nav: LobbyNav,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var searchKeyword by rememberSaveable { mutableStateOf("") }
    var showCreatePublicSheet by remember { mutableStateOf(false) }
    var pullRefreshing by remember { mutableStateOf(false) }
    var lastPullAt by remember { mutableLongStateOf(0L) }
    var pullStartedAt by remember { mutableLongStateOf(0L) }
    val latestLoading by rememberUpdatedState(state.publicRoomsLoading)
    val latestHapticEnabled by rememberUpdatedState(state.prefs.hapticEnabled)

    LaunchedEffect(Unit) {
        vm.refreshPublicRoomsIfStale()
    }

    // UX-7：等待真实公开房刷新结束（最短 350ms 防闪烁，最长 8000ms 超时兜底）
    LaunchedEffect(pullRefreshing) {
        if (!pullRefreshing) return@LaunchedEffect
        delay(120L)
        withTimeoutOrNull(DISCOVER_REFRESH_TIMEOUT_MS) {
            snapshotFlow { latestLoading }.first { !it }
        }
        val elapsed = System.currentTimeMillis() - pullStartedAt
        if (elapsed < DISCOVER_MIN_REFRESH_MS) {
            delay(DISCOVER_MIN_REFRESH_MS - elapsed)
        }
        pullRefreshing = false
        BayHaptic.confirm(view, enabled = latestHapticEnabled)
        vm.playRefreshSound()
    }

    val filteredRooms = remember(state.publicRooms, searchKeyword) {
        val q = searchKeyword.trim()
        if (q.isEmpty()) {
            state.publicRooms
        } else {
            state.publicRooms.filter { room ->
                room.code.contains(q, ignoreCase = true) ||
                    stripLeadingEmoji(room.topic).contains(q, ignoreCase = true)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BayLargeTitleHeader(
            title = "发现",
            subtitle = if (state.publicRooms.isNotEmpty()) {
                "当前 ${state.publicRooms.size} 个公开房间"
            } else {
                "探索免密公开交流房间"
            },
            actions = {
                BayIconButton(
                    icon = Icons.Rounded.Shuffle,
                    contentDescription = "随机串门",
                    enabled = !state.randomJoining && !state.lobbyBusy,
                    onClick = {
                        BayHaptic.tap(haptic, enabled = state.prefs.hapticEnabled)
                        vm.joinRandomPublicRoom(excludeCurrent = false)
                    },
                    style = BayIconStyle.Filled,
                )
                BayIconButton(
                    icon = Icons.Rounded.Add,
                    contentDescription = "创建公开房间",
                    enabled = !state.lobbyBusy,
                    onClick = { showCreatePublicSheet = true },
                    style = BayIconStyle.Accent,
                )
            },
        )

        // 本地关键词搜索栏（UX-32：配置 Search 动作收起键盘）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.xs),
        ) {
            BayTextField(
                value = searchKeyword,
                onValueChange = { searchKeyword = it.take(60) },
                placeholder = "搜索房间口令或主题关键词",
                leadingIcon = Icons.Rounded.Search,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    },
                ),
                trailing = if (searchKeyword.isNotEmpty()) {
                    {
                        BayIconButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = "清空搜索",
                            onClick = { searchKeyword = "" },
                            style = BayIconStyle.Plain,
                            size = 28.dp,
                        )
                    }
                } else {
                    null
                },
            )
        }

        Spacer(modifier = Modifier.height(BaySpacing.xs))

        PullToRefreshBox(
            isRefreshing = pullRefreshing,
            onRefresh = {
                val now = System.currentTimeMillis()
                if (now - lastPullAt < DISCOVER_PULL_DEBOUNCE_MS) {
                    pullRefreshing = false
                    vm.showToast("刚刚刷新过，请稍候")
                } else {
                    lastPullAt = now
                    pullStartedAt = now
                    pullRefreshing = true
                    vm.refreshPublicRooms(silent = false)
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (filteredRooms.isEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = BaySpacing.xl),
                ) {
                    item {
                        when {
                            state.publicRoomsLoading -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = BaySpacing.xxxl),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(BaySpacing.md),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        color = colors.accent,
                                        strokeWidth = 2.5.dp,
                                        strokeCap = StrokeCap.Round,
                                    )
                                    Text(
                                        text = "正在加载公开房间…",
                                        style = BayType.subhead,
                                        color = colors.textSecondary,
                                    )
                                }
                            }
                            state.publicRoomsError != null -> {
                                BayEmptyState(
                                    icon = Icons.Rounded.ErrorOutline,
                                    title = "公开房间加载失败",
                                    message = state.publicRoomsError,
                                    actionLabel = "重新加载",
                                    onAction = { vm.refreshPublicRooms(silent = false) },
                                )
                            }
                            searchKeyword.isNotBlank() -> {
                                BayEmptyState(
                                    icon = Icons.Rounded.Search,
                                    title = "未找到匹配房间",
                                    message = "换个关键词试试，或清空搜索条件查看全部公开房间。",
                                    actionLabel = "清空搜索",
                                    onAction = { searchKeyword = "" },
                                )
                            }
                            else -> {
                                BayEmptyState(
                                    icon = Icons.Rounded.Explore,
                                    title = "暂无公开房间",
                                    message = "你可以创建一个公开房间供大家发现，或下拉刷新重试。",
                                    actionLabel = "创建公开房间",
                                    onAction = { showCreatePublicSheet = true },
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = BaySpacing.lg,
                        end = BaySpacing.lg,
                        top = BaySpacing.xs,
                        bottom = BaySpacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.md),
                ) {
                    items(
                        items = filteredRooms,
                        key = { it.code },
                    ) { room ->
                        PublicRoomCard(
                            room = room,
                            enabled = !state.lobbyBusy,
                            onJoin = { vm.joinRoom(room.code, "") },
                        )
                    }
                }
            }
        }
    }

    if (showCreatePublicSheet) {
        CreatePublicRoomSheet(
            maxAttachmentMB = state.serverConfig.maxAttachmentMB,
            defaultMaxFileMB = state.serverConfig.defaultRoomMaxFileMB,
            onConfirm = { topic, retentionDays, maxFileMB ->
                showCreatePublicSheet = false
                vm.createPublicRoom(
                    topic = topic,
                    retentionDays = retentionDays,
                    maxFileSizeMB = maxFileMB,
                )
            },
            onDismiss = { showCreatePublicSheet = false },
        )
    }
}

@Composable
private fun PublicRoomCard(
    room: RoomSummary,
    enabled: Boolean,
    onJoin: () -> Unit,
) {
    val colors = BayTheme.colors
    val cleanTopic = stripLeadingEmoji(room.topic).trim().ifBlank { "公开交流房间" }
    val retentionDesc = if (room.retentionDays <= 0) "阅后即焚" else "保留 ${room.retentionDays} 天"
    val limitDesc = FormatUtils.formatLimitMB(room.maxFileSizeMB)

    BayCard(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.55f },
        onClick = if (enabled) onJoin else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(BaySpacing.xs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    Text(
                        text = "#${room.code}",
                        style = BayType.headline.copy(fontFamily = FontFamily.Monospace),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (room.hasPassword) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "加密房间",
                            tint = colors.warning,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(BayShapes.fullShape)
                            .background(colors.accentSoft)
                            .padding(horizontal = BaySpacing.sm, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Group,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = "${room.peerCount}/${room.maxPeers}",
                            style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.accent,
                        )
                    }
                }

                Text(
                    text = cleanTopic,
                    style = BayType.callout,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = "$retentionDesc · 单文件上限 $limitDesc",
                    style = BayType.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }

            Spacer(modifier = Modifier.size(BaySpacing.md))

            BayButton(
                text = "加入",
                onClick = onJoin,
                style = BayButtonStyle.Secondary,
                size = BayButtonSize.Small,
                enabled = enabled,
            )
        }
    }
}

/**
 * 发现页专用「创建公开房间」抽屉（仅此处显式创建 allowRandom = true 的公开房）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CreatePublicRoomSheet(
    maxAttachmentMB: Int,
    defaultMaxFileMB: Int,
    onConfirm: (topic: String, retentionDays: Int, maxFileMB: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    var topic by remember { mutableStateOf("") }
    var retentionDays by remember { mutableIntStateOf(1) }
    var maxFileSizeMB by remember { mutableIntStateOf(defaultMaxFileMB) }

    val availableFileLimits = remember(maxAttachmentMB) {
        if (maxAttachmentMB <= 0) {
            PUBLIC_FILE_LIMIT_CHOICES
        } else {
            val filtered = PUBLIC_FILE_LIMIT_CHOICES.filter { it.first in 1..maxAttachmentMB }
            if (filtered.any { it.first == maxAttachmentMB }) {
                filtered
            } else {
                filtered + (maxAttachmentMB to FormatUtils.formatLimitMB(maxAttachmentMB))
            }
        }
    }

    BayBottomSheet(
        onDismiss = onDismiss,
        title = "创建公开房间",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.lg)) {
            Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                BayTextField(
                    value = topic,
                    onValueChange = { topic = it.take(40) },
                    placeholder = "输入房间主题（如：文件互传）",
                    leadingIcon = Icons.Rounded.Edit,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    PUBLIC_TOPIC_PRESETS.forEach { preset ->
                        BayChip(
                            label = preset,
                            selected = topic == preset,
                            onClick = { topic = if (topic == preset) "" else preset },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                Text(
                    text = "消息保鲜天数",
                    style = BayType.subhead.copy(fontWeight = FontWeight.Medium),
                    color = colors.textSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    PUBLIC_RETENTION_CHOICES.forEach { (days, label) ->
                        BayChip(
                            label = label,
                            selected = retentionDays == days,
                            onClick = { retentionDays = days },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                Text(
                    text = "单文件上限",
                    style = BayType.subhead.copy(fontWeight = FontWeight.Medium),
                    color = colors.textSecondary,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    availableFileLimits.forEach { (mb, label) ->
                        BayChip(
                            label = label,
                            selected = maxFileSizeMB == mb,
                            onClick = { maxFileSizeMB = mb },
                        )
                    }
                }
            }

            Text(
                text = "公开房间将在「发现」列表与随机串门中展示，所有成员均可免密加入。",
                style = BayType.caption,
                color = colors.textSecondary,
            )

            BayButton(
                text = "创建并进入",
                onClick = { onConfirm(topic.trim(), retentionDays, maxFileSizeMB) },
                style = BayButtonStyle.Primary,
                size = BayButtonSize.Large,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
