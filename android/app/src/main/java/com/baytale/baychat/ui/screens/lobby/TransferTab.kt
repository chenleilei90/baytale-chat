package com.baytale.baychat.ui.screens.lobby

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.model.FileTransferInfo
import com.baytale.baychat.core.model.PartialResumeItem
import com.baytale.baychat.core.model.SavedPickupRecord
import com.baytale.baychat.core.model.TransferDirection
import com.baytale.baychat.core.model.TransferRecord
import com.baytale.baychat.core.model.TransferStatus
import com.baytale.baychat.core.util.BayFileKind
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.core.util.classifyFile
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayCard
import com.baytale.baychat.ui.design.BayChip
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.design.BayEmptyState
import com.baytale.baychat.ui.design.BayFileTypeIcon
import com.baytale.baychat.ui.design.BayIconButton
import com.baytale.baychat.ui.design.BayIconStyle
import com.baytale.baychat.ui.design.BayLargeTitleHeader
import com.baytale.baychat.ui.design.BayProgressBar
import com.baytale.baychat.ui.design.BaySectionHeader
import com.baytale.baychat.ui.design.BaySegmented
import com.baytale.baychat.ui.sheets.PickupHubSheet
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TRANSFER_PULL_THROTTLE_MS = 1_500L
private const val TRANSFER_REFRESH_MIN_MS = 350L
private const val TRANSFER_REFRESH_TIMEOUT_MS = 8_000L

private enum class CompletedFilter(val label: String) {
    ALL("全部"),
    IMAGE("图片"),
    VIDEO("视频"),
    FILE("文件与文档"),
}

/**
 * 传输中心页面：集中管理进行中的传输任务、断点续传碎片、已完成收发记录与离线取件码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferTab(
    state: BayChatUiState,
    vm: BayChatViewModel,
    nav: LobbyNav,
) {
    val colors = BayTheme.colors
    val view = LocalView.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // 使用 rememberSaveable 保留切 Tab / 旋转屏幕时的分段与筛选状态
    var selectedSegment by rememberSaveable { mutableIntStateOf(0) }
    var completedFilter by rememberSaveable { mutableStateOf(CompletedFilter.ALL) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isThrottleReset by remember { mutableStateOf(false) }
    var lastPullAt by rememberSaveable { mutableLongStateOf(0L) }

    var showPickupHub by rememberSaveable { mutableStateOf(false) }
    var confirmClearRecords by remember { mutableStateOf(false) }
    var confirmClearPartials by remember { mutableStateOf(false) }
    var pendingRevokeCode by remember { mutableStateOf<String?>(null) }

    val latestTransferRefreshing by rememberUpdatedState(state.isTransferRefreshing)

    // 监听真实刷新状态：最少展示 350ms 防止视觉闪烁，最长 8000ms 超时兜底
    LaunchedEffect(isRefreshing, isThrottleReset) {
        if (!isRefreshing || isThrottleReset) return@LaunchedEffect
        delay(TRANSFER_REFRESH_MIN_MS)
        withTimeoutOrNull(TRANSFER_REFRESH_TIMEOUT_MS - TRANSFER_REFRESH_MIN_MS) {
            snapshotFlow { latestTransferRefreshing }.first { !it }
        }
        isRefreshing = false
        BayHaptic.confirm(view, state.prefs.hapticEnabled)
        vm.playRefreshSound()
    }

    // 提取当前房间内尚未结束的活跃传输项
    val activeTransfers = remember(state.activeRoom?.messages) {
        state.activeRoom?.messages
            .orEmpty()
            .mapNotNull { it.transfer }
            .filter {
                it.status == TransferStatus.OFFERING ||
                    it.status == TransferStatus.WAITING_ACCEPT ||
                    it.status == TransferStatus.TRANSFERRING
            }
            .distinctBy { it.transferId }
    }

    // 对已完成传输记录做按 ID 去重
    val distinctRecords = remember(state.transferRecords) {
        state.transferRecords.distinctBy { it.id }
    }

    val filteredRecords = remember(distinctRecords, completedFilter) {
        when (completedFilter) {
            CompletedFilter.ALL -> distinctRecords
            CompletedFilter.IMAGE -> distinctRecords.filter {
                classifyFile(it.fileName, it.mimeType) == BayFileKind.IMAGE
            }
            CompletedFilter.VIDEO -> distinctRecords.filter {
                classifyFile(it.fileName, it.mimeType) == BayFileKind.VIDEO
            }
            CompletedFilter.FILE -> distinctRecords.filter {
                val k = classifyFile(it.fileName, it.mimeType)
                k != BayFileKind.IMAGE && k != BayFileKind.VIDEO
            }
        }
    }

    fun buildPickupShareUrl(code: String): String {
        val base = state.prefs.serverUrl.trim().trimEnd('/')
        return "$base/p/${code.trim()}"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        BayLargeTitleHeader(
            title = "传输",
            actions = {
                BayIconButton(
                    icon = Icons.Rounded.FolderOpen,
                    contentDescription = "打开下载目录",
                    onClick = { vm.openDownloadsFolder() },
                    style = BayIconStyle.Filled,
                )
                BayIconButton(
                    icon = Icons.Rounded.Add,
                    contentDescription = "新建取件码",
                    onClick = { showPickupHub = true },
                    style = BayIconStyle.Accent,
                )
            },
        )

        BaySegmented(
            options = listOf("进行中", "已完成", "取件码"),
            selected = selectedSegment,
            onSelect = { selectedSegment = it },
            modifier = Modifier.padding(horizontal = BaySpacing.lg, vertical = BaySpacing.sm),
        )

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                val now = System.currentTimeMillis()
                if (now - lastPullAt < TRANSFER_PULL_THROTTLE_MS) {
                    // 节流期内立即复位指示器并轻提示，不触发刷新确认反馈
                    vm.showToast("刚刚刷新过，请稍候")
                    scope.launch {
                        isThrottleReset = true
                        isRefreshing = true
                        delay(50L)
                        isRefreshing = false
                        isThrottleReset = false
                    }
                    return@PullToRefreshBox
                }
                lastPullAt = now
                isThrottleReset = false
                isRefreshing = true
                vm.refreshTransferCenter()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = BaySpacing.lg,
                    end = BaySpacing.lg,
                    top = BaySpacing.sm,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(BaySpacing.md),
            ) {
                when (selectedSegment) {
                    0 -> {
                        // 分段一：进行中（活跃收发 + 断点续传缓存碎片）
                        if (activeTransfers.isEmpty() && state.partialTransfers.isEmpty()) {
                            item(key = "active_empty") {
                                BayEmptyState(
                                    icon = Icons.Rounded.SwapVert,
                                    title = "暂无进行中的传输",
                                    message = "在房间内收发文件时，实时进度与未完成的断点缓存会集中显示在此处。",
                                    actionLabel = if (state.hasActiveRoom) "回到当前房间" else "前往快传页",
                                    onAction = {
                                        if (state.hasActiveRoom) nav.restoreRoom() else nav.switchTab(0)
                                    },
                                )
                            }
                        } else {
                            if (activeTransfers.isNotEmpty()) {
                                item(key = "active_header") {
                                    BaySectionHeader(
                                        title = "当前传输任务 (${activeTransfers.size})",
                                        actionLabel = if (state.hasActiveRoom) "回到房间" else null,
                                        onAction = if (state.hasActiveRoom) {
                                            { nav.restoreRoom() }
                                        } else {
                                            null
                                        },
                                    )
                                }

                                items(
                                    items = activeTransfers,
                                    key = { "active_${it.transferId}" },
                                ) { info ->
                                    ActiveTransferCard(
                                        info = info,
                                        onAccept = {
                                            vm.acceptFile(info.targetPeerId.orEmpty(), info.transferId)
                                        },
                                        onReject = {
                                            vm.rejectFile(info.targetPeerId.orEmpty(), info.transferId)
                                        },
                                        onCancel = {
                                            vm.cancelTransfer(info.targetPeerId.orEmpty(), info.transferId)
                                        },
                                    )
                                }
                            }

                            if (state.partialTransfers.isNotEmpty()) {
                                item(key = "partial_header") {
                                    BaySectionHeader(
                                        title = "断点续传缓存 (${state.partialTransfers.size})",
                                        actionLabel = "全部清理",
                                        onAction = { confirmClearPartials = true },
                                    )
                                }

                                items(
                                    items = state.partialTransfers,
                                    key = { "partial_${it.partFileName}" },
                                ) { item ->
                                    PartialResumeCard(
                                        item = item,
                                        onResume = { vm.requestResumeFromTransferCenter(item) },
                                        onDelete = { vm.deletePartialTransfer(item.partFileName) },
                                    )
                                }
                            }
                        }
                    }

                    1 -> {
                        // 分段二：已完成
                        if (distinctRecords.isNotEmpty()) {
                            item(key = "completed_header") {
                                BaySectionHeader(
                                    title = "已收发记录 (${distinctRecords.size})",
                                    actionLabel = "清空记录",
                                    onAction = { confirmClearRecords = true },
                                )
                            }

                            item(key = "completed_filter") {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                                ) {
                                    items(CompletedFilter.entries) { filter ->
                                        BayChip(
                                            label = filter.label,
                                            selected = completedFilter == filter,
                                            onClick = { completedFilter = filter },
                                        )
                                    }
                                }
                            }
                        }

                        if (filteredRecords.isEmpty()) {
                            item(key = "completed_empty") {
                                BayEmptyState(
                                    icon = Icons.Rounded.History,
                                    title = if (distinctRecords.isEmpty()) "暂无传输记录" else "该分类下暂无记录",
                                    message = if (distinctRecords.isEmpty()) {
                                        "进入房间互传文件或通过取件码下载后，历史记录会保存在这里。"
                                    } else {
                                        "可切换其他分类或查看全部传输记录。"
                                    },
                                    actionLabel = if (distinctRecords.isNotEmpty() && completedFilter != CompletedFilter.ALL) {
                                        "查看全部"
                                    } else {
                                        "打开下载目录"
                                    },
                                    onAction = {
                                        if (distinctRecords.isNotEmpty() && completedFilter != CompletedFilter.ALL) {
                                            completedFilter = CompletedFilter.ALL
                                        } else {
                                            vm.openDownloadsFolder()
                                        }
                                    },
                                )
                            }
                        } else {
                            items(
                                items = filteredRecords,
                                key = { "record_${it.id}" },
                            ) { record ->
                                CompletedRecordCard(
                                    record = record,
                                    onOpen = { vm.openTransfer(record) },
                                    onShare = { vm.shareTransfer(record) },
                                    onDelete = { vm.removeTransferRecord(record.id) },
                                )
                            }
                        }
                    }

                    2 -> {
                        // 分段三：取件码
                        item(key = "pickup_banner") {
                            BayCard(
                                onClick = { showPickupHub = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                                ) {
                                    BayIconButton(
                                        icon = Icons.Rounded.QrCode2,
                                        contentDescription = "离线取件码",
                                        onClick = { showPickupHub = true },
                                        style = BayIconStyle.Accent,
                                        size = 44.dp,
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = "寄出文件或凭码取件",
                                            style = BayType.headline,
                                            color = colors.textPrimary,
                                        )
                                        Text(
                                            text = "无需双方同时在线，上传生成 6 位取件码跨设备提取",
                                            style = BayType.footnote,
                                            color = colors.textSecondary,
                                        )
                                    }
                                    BayButton(
                                        text = "打开",
                                        onClick = { showPickupHub = true },
                                        style = BayButtonStyle.Secondary,
                                        size = BayButtonSize.Small,
                                    )
                                }
                            }
                        }

                        if (state.myPickups.isEmpty()) {
                            item(key = "pickup_empty") {
                                BayEmptyState(
                                    icon = Icons.Rounded.QrCode2,
                                    title = "暂无已发出的取件码",
                                    message = "点击上方「寄出文件或凭码取件」，上传文件即可生成离线取件码。",
                                    actionLabel = "立即新建",
                                    onAction = { showPickupHub = true },
                                )
                            }
                        } else {
                            item(key = "pickup_header") {
                                BaySectionHeader(
                                    title = "我寄出的取件码 (${state.myPickups.size})",
                                    actionLabel = "新建",
                                    onAction = { showPickupHub = true },
                                )
                            }

                            items(
                                items = state.myPickups,
                                key = { "pickup_${it.code}" },
                            ) { rec ->
                                PickupSummaryCard(
                                    record = rec,
                                    onCopyCode = {
                                        clipboard.setText(AnnotatedString(rec.code))
                                        vm.showToast("已复制取件码 ${rec.code}")
                                    },
                                    onCopyLink = {
                                        clipboard.setText(AnnotatedString(buildPickupShareUrl(rec.code)))
                                        vm.showToast("已复制取件链接")
                                    },
                                    onRevoke = { pendingRevokeCode = rec.code },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPickupHub) {
        PickupHubSheet(
            state = state,
            vm = vm,
            onDismiss = { showPickupHub = false },
        )
    }

    if (confirmClearPartials) {
        BayDialog(
            title = "清理全部断点缓存？",
            message = "将删除全部 ${state.partialTransfers.size} 个未完成的传输临时碎片，删除后无法从断点恢复。",
            confirmText = "全部清理",
            onConfirm = {
                vm.clearAllPartialTransfers()
                confirmClearPartials = false
            },
            dismissText = "取消",
            onDismiss = { confirmClearPartials = false },
            destructive = true,
        )
    }

    if (confirmClearRecords) {
        BayDialog(
            title = "清空传输记录？",
            message = "仅清理记录列表显示，已保存到系统 Download/BayChat 或相册的本地文件不受影响。",
            confirmText = "确认清空",
            onConfirm = {
                vm.clearTransferRecords()
                confirmClearRecords = false
            },
            dismissText = "取消",
            onDismiss = { confirmClearRecords = false },
            destructive = true,
        )
    }

    pendingRevokeCode?.let { code ->
        BayDialog(
            title = "撤销取件码 $code？",
            message = "撤销后将从服务器移除该文件，接收方将无法再凭此码取件。",
            confirmText = "确认撤销",
            onConfirm = {
                vm.revokePickup(code)
                pendingRevokeCode = null
            },
            dismissText = "取消",
            onDismiss = { pendingRevokeCode = null },
            destructive = true,
        )
    }
}

/**
 * 保留文件扩展名的中间省略文件名展示组件，确保 .apk / .zip / .pptx 等扩展名永不被截断。
 */
@Composable
private fun MiddleEllipsisFileName(
    fileName: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val (stem, ext) = remember(fileName) { FormatUtils.splitFileNameAndExt(fileName) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stem,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (ext.isNotEmpty()) {
            Text(
                text = ext,
                style = style,
                color = color,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ActiveTransferCard(
    info: FileTransferInfo,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = BayTheme.colors
    val fraction = if (info.fileSize > 0L) {
        (info.transferredBytes.toFloat() / info.fileSize.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = (fraction * 100f).toInt().coerceIn(0, 100)
    val isReceiving = info.direction == TransferDirection.RECEIVE
    val directionLabel = if (isReceiving) "正在接收" else "正在发出"
    val statusText = when (info.status) {
        TransferStatus.OFFERING -> "等待对方响应"
        TransferStatus.WAITING_ACCEPT -> "等待确认接收"
        TransferStatus.TRANSFERRING -> {
            val speed = if (info.speedBps > 0.0) " · ${FormatUtils.formatBytes(info.speedBps.toLong())}/s" else ""
            val eta = FormatUtils.formatEta(info.etaSeconds).takeIf { it.isNotBlank() }?.let { " · 剩余 $it" }.orEmpty()
            "$percent%$speed$eta"
        }
        else -> ""
    }

    BayCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
        ) {
            BayFileTypeIcon(
                fileName = info.fileName,
                mimeType = info.mimeType,
                size = 42.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MiddleEllipsisFileName(
                    fileName = info.fileName,
                    style = BayType.headline,
                    color = colors.textPrimary,
                    modifier = Modifier.fillMaxWidth(),
                )
                val peerPart = info.targetPeerName?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                Text(
                    text = "$directionLabel · ${FormatUtils.formatBytes(info.fileSize)}$peerPart",
                    style = BayType.footnote,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.height(BaySpacing.sm))
        BayProgressBar(fraction = fraction)
        Spacer(modifier = Modifier.height(BaySpacing.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = statusText,
                style = BayType.caption,
                color = colors.accent,
                modifier = Modifier.weight(1f),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                if (isReceiving && info.status == TransferStatus.WAITING_ACCEPT) {
                    BayButton(
                        text = "拒绝",
                        onClick = onReject,
                        style = BayButtonStyle.DestructiveSoft,
                        size = BayButtonSize.Small,
                        leadingIcon = Icons.Rounded.Close,
                    )
                    BayButton(
                        text = "接收",
                        onClick = onAccept,
                        style = BayButtonStyle.Primary,
                        size = BayButtonSize.Small,
                        leadingIcon = Icons.Rounded.Check,
                    )
                } else {
                    BayButton(
                        text = "取消",
                        onClick = onCancel,
                        style = BayButtonStyle.Neutral,
                        size = BayButtonSize.Small,
                    )
                }
            }
        }
    }
}

@Composable
private fun PartialResumeCard(
    item: PartialResumeItem,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = BayTheme.colors
    val fraction = if (item.totalBytes > 0L) {
        (item.savedBytes.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = (fraction * 100f).toInt().coerceIn(0, 100)

    BayCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
        ) {
            BayFileTypeIcon(
                fileName = item.fileName,
                size = 42.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MiddleEllipsisFileName(
                    fileName = item.fileName,
                    style = BayType.headline,
                    color = colors.textPrimary,
                    modifier = Modifier.fillMaxWidth(),
                )
                val roomPart = if (item.roomCode.isNotBlank()) " · #${item.roomCode}" else ""
                Text(
                    text = "$percent% · ${FormatUtils.formatBytes(item.savedBytes)} / ${FormatUtils.formatBytes(item.totalBytes)}$roomPart",
                    style = BayType.footnote,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BayIconButton(
                icon = Icons.Rounded.DeleteOutline,
                contentDescription = "删除断点缓存",
                onClick = onDelete,
                style = BayIconStyle.Plain,
                size = 34.dp,
            )
        }

        Spacer(modifier = Modifier.height(BaySpacing.sm))
        BayProgressBar(fraction = fraction, color = colors.warning)
        Spacer(modifier = Modifier.height(BaySpacing.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "重新进入对应房间可从断点继续传输",
                style = BayType.caption,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            BayButton(
                text = "请求续传",
                onClick = onResume,
                style = BayButtonStyle.Secondary,
                size = BayButtonSize.Small,
                leadingIcon = Icons.Rounded.PlayArrow,
            )
        }
    }
}

@Composable
private fun CompletedRecordCard(
    record: TransferRecord,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = BayTheme.colors
    val isSend = record.direction == "send"
    val directionText = if (isSend) "发出" else "收到"
    val directionColor = if (isSend) colors.accent else colors.success

    BayCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
        ) {
            BayFileTypeIcon(
                fileName = record.fileName,
                mimeType = record.mimeType,
                size = 42.dp,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                ) {
                    Text(
                        text = directionText,
                        style = BayType.caption,
                        color = directionColor,
                        modifier = Modifier
                            .clip(BayShapes.fullShape)
                            .background(directionColor.copy(alpha = if (colors.isDark) 0.20f else 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    MiddleEllipsisFileName(
                        fileName = record.fileName,
                        style = BayType.headline,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }

                val metaParts = buildList {
                    add(FormatUtils.formatBytes(record.fileSize))
                    if (record.roomCode.isNotBlank()) add("#${record.roomCode}")
                    add(FormatUtils.formatRelativeTime(record.timestamp))
                    if (record.verified) add("已校验")
                }
                Text(
                    text = metaParts.joinToString(" · "),
                    style = BayType.footnote,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            BayIconButton(
                icon = if (isSend) Icons.Rounded.ArrowOutward else Icons.Rounded.FileOpen,
                contentDescription = "打开文件",
                onClick = onOpen,
                style = BayIconStyle.Filled,
                size = 34.dp,
            )
            BayIconButton(
                icon = Icons.Rounded.Share,
                contentDescription = "分享文件",
                onClick = onShare,
                style = BayIconStyle.Accent,
                size = 34.dp,
            )
            BayIconButton(
                icon = Icons.Rounded.DeleteOutline,
                contentDescription = "删除记录",
                onClick = onDelete,
                style = BayIconStyle.Plain,
                size = 34.dp,
            )
        }
    }
}

@Composable
private fun PickupSummaryCard(
    record: SavedPickupRecord,
    onCopyCode: () -> Unit,
    onCopyLink: () -> Unit,
    onRevoke: () -> Unit,
) {
    val colors = BayTheme.colors
    val expired = record.expiresAt > 0L && record.expiresAt <= System.currentTimeMillis()
    val expiryText = when {
        expired -> "已过期"
        record.expiresAt > 0L -> FormatUtils.formatExpiry(record.expiresAt)
        else -> "有效"
    }

    BayCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
        ) {
            BayFileTypeIcon(
                fileName = record.fileName,
                mimeType = record.mimeType,
                size = 42.dp,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    Text(
                        text = record.code,
                        style = BayType.headline.copy(fontWeight = FontWeight.Bold),
                        color = if (expired) colors.textTertiary else colors.accent,
                    )
                    if (record.burnAfterRead) {
                        Text(
                            text = "阅后即焚",
                            style = BayType.caption,
                            color = colors.warning,
                            modifier = Modifier
                                .clip(BayShapes.fullShape)
                                .background(colors.warning.copy(alpha = if (colors.isDark) 0.20f else 0.12f))
                                .padding(horizontal = BaySpacing.sm, vertical = 2.dp),
                        )
                    }
                }

                MiddleEllipsisFileName(
                    fileName = record.fileName,
                    style = BayType.subhead,
                    color = colors.textPrimary,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "${FormatUtils.formatBytes(record.fileSize)} · $expiryText",
                    style = BayType.caption,
                    color = if (expired) colors.danger else colors.textSecondary,
                )
            }

            BayIconButton(
                icon = Icons.Rounded.ContentCopy,
                contentDescription = "复制取件码",
                onClick = onCopyCode,
                style = BayIconStyle.Filled,
                size = 34.dp,
            )
            BayIconButton(
                icon = Icons.Rounded.Link,
                contentDescription = "复制取件链接",
                onClick = onCopyLink,
                style = BayIconStyle.Accent,
                size = 34.dp,
            )
            BayIconButton(
                icon = Icons.Rounded.DeleteOutline,
                contentDescription = "撤销取件码",
                onClick = onRevoke,
                style = BayIconStyle.Plain,
                size = 34.dp,
            )
        }
    }
}
