package com.baytale.baychat.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.StorageCategory
import com.baytale.baychat.ui.design.BayBottomSheet
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayCard
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.design.BayGroup
import com.baytale.baychat.ui.design.BayListRow
import com.baytale.baychat.ui.design.RowTrailing
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

private data class StorageCategoryDisplay(
    val category: StorageCategory,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
    val bytes: Long,
)

/**
 * 存储空间管理抽屉：展示本地缓存占用明细，支持按分类清理或一键清理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSheet(
    state: BayChatUiState,
    vm: BayChatViewModel,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    val stats = state.storageStats

    var pendingCategory by remember { mutableStateOf<StorageCategoryDisplay?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshStorageStats()
    }

    val items = listOf(
        StorageCategoryDisplay(
            category = StorageCategory.RECEIVED_MEDIA,
            title = "聊天附件与媒体缓存",
            subtitle = "聊天内下载保留的图片、视频与附件缓存",
            icon = Icons.Rounded.PhotoLibrary,
            tint = colors.fileTypeColors.image,
            bytes = stats.receivedMediaBytes,
        ),
        StorageCategoryDisplay(
            category = StorageCategory.PARTIALS,
            title = "断点续传碎片",
            subtitle = "未完成的传输任务临时片段文件",
            icon = Icons.Rounded.PauseCircle,
            tint = colors.warning,
            bytes = stats.partialsBytes,
        ),
        StorageCategoryDisplay(
            category = StorageCategory.COIL_CACHE,
            title = "缩略图浏览缓存",
            subtitle = "历史图片加载生成的本地磁盘缓存",
            icon = Icons.Rounded.Collections,
            tint = colors.accent,
            bytes = stats.coilCacheBytes,
        ),
        StorageCategoryDisplay(
            category = StorageCategory.UPDATES,
            title = "应用更新安装包",
            subtitle = "升级下载的本地安装文件与碎片",
            icon = Icons.Rounded.SystemUpdate,
            tint = colors.fileTypeColors.apk,
            bytes = stats.updatesBytes,
        ),
        StorageCategoryDisplay(
            category = StorageCategory.EXPORTS,
            title = "临时附件与语音缓存",
            subtitle = "文件夹压缩包、拍照录像及语音临时文件",
            icon = Icons.Rounded.FolderZip,
            tint = colors.fileTypeColors.archive,
            bytes = stats.exportsBytes,
        ),
    )

    BayBottomSheet(
        onDismiss = onDismiss,
        title = "存储空间管理",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(BaySpacing.lg),
        ) {
            // 顶部总占用概览卡片
            BayCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.xs)) {
                        Text(
                            text = "应用缓存总占用",
                            style = BayType.footnote,
                            color = colors.textSecondary,
                        )
                        Text(
                            text = FormatUtils.formatBytes(stats.totalBytes),
                            style = BayType.title1,
                            color = colors.textPrimary,
                        )
                    }
                    BayButton(
                        text = "重新统计",
                        onClick = { vm.refreshStorageStats() },
                        style = BayButtonStyle.Neutral,
                        size = BayButtonSize.Small,
                    )
                }
                Spacer(modifier = Modifier.height(BaySpacing.sm))
                Text(
                    text = "已保存至系统 Download/BayChat 与相册的文件不受清理影响。",
                    style = BayType.footnote,
                    color = colors.textSecondary,
                )
            }

            // 分类缓存明细
            BayGroup(
                header = "缓存明细",
                footer = "常驻收藏房间（${state.savedRooms.size} 个）与最近记录（${state.recentRooms.size} 个）仅占极小配置空间。",
            ) {
                items.forEachIndexed { index, item ->
                    BayListRow(
                        icon = item.icon,
                        iconTint = item.tint,
                        title = item.title,
                        subtitle = item.subtitle,
                        value = FormatUtils.formatBytes(item.bytes),
                        trailing = RowTrailing.Custom {
                            BayButton(
                                text = "清理",
                                onClick = { pendingCategory = item },
                                style = BayButtonStyle.DestructiveSoft,
                                size = BayButtonSize.Small,
                                enabled = item.bytes > 0L,
                            )
                        },
                        showDivider = index < items.lastIndex,
                    )
                }
            }

            // 聊天与最近记录清理入口
            val hasRecordsToClear = state.recentRooms.isNotEmpty() ||
                state.draftsByRoom.isNotEmpty() ||
                (state.activeRoom?.messages?.isNotEmpty() == true)
            BayGroup(header = "消息记录清理") {
                BayListRow(
                    icon = Icons.Rounded.Forum,
                    iconTint = colors.danger,
                    title = "清理聊天与最近记录",
                    subtitle = "清空本机最近房间（${state.recentRooms.size} 条）、草稿与当前消息流",
                    trailing = RowTrailing.Custom {
                        BayButton(
                            text = "清理",
                            onClick = { confirmClearHistory = true },
                            style = BayButtonStyle.DestructiveSoft,
                            size = BayButtonSize.Small,
                            enabled = hasRecordsToClear,
                        )
                    },
                )
            }

            // 一键清理全部缓存
            BayButton(
                text = "一键清理全部缓存",
                onClick = { confirmClearAll = true },
                style = BayButtonStyle.DestructiveSoft,
                size = BayButtonSize.Large,
                leadingIcon = Icons.Rounded.DeleteSweep,
                enabled = stats.totalBytes > 0L,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // 单项清理确认弹窗
    pendingCategory?.let { target ->
        BayDialog(
            title = "清理${target.title}？",
            message = "将释放 ${FormatUtils.formatBytes(target.bytes)} 本地缓存空间，已保存至系统下载目录或相册的文件不受影响。",
            confirmText = "确认清理",
            onConfirm = {
                vm.clearStorage(target.category)
                pendingCategory = null
            },
            dismissText = "取消",
            onDismiss = { pendingCategory = null },
            destructive = true,
        )
    }

    // 全部清理确认弹窗
    if (confirmClearAll) {
        BayDialog(
            title = "一键清理全部缓存？",
            message = "将清理全部 ${FormatUtils.formatBytes(stats.totalBytes)} 本地临时缓存与断点碎片，已保存到系统目录的文件不会丢失。",
            confirmText = "全部清理",
            onConfirm = {
                StorageCategory.entries.forEach { cat ->
                    vm.clearStorage(cat)
                }
                confirmClearAll = false
            },
            dismissText = "取消",
            onDismiss = { confirmClearAll = false },
            destructive = true,
        )
    }

    if (confirmClearHistory) {
        BayDialog(
            title = "清理聊天与最近记录？",
            message = "将清空本机最近访问房间列表、输入草稿及当前聊天消息显示，收藏的常驻房间与已保存的系统文件不受影响。",
            confirmText = "确认清理",
            onConfirm = {
                vm.clearAllLocalHistoryRecords()
                confirmClearHistory = false
            },
            dismissText = "取消",
            onDismiss = { confirmClearHistory = false },
            destructive = true,
        )
    }
}
