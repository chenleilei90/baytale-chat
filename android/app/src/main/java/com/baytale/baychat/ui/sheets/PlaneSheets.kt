package com.baytale.baychat.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.model.PaperPlane
import com.baytale.baychat.core.model.PlaneFileMeta
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.design.BayAvatar
import com.baytale.baychat.ui.design.BayBottomSheet
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayIconButton
import com.baytale.baychat.ui.design.BayIconStyle
import com.baytale.baychat.ui.design.BaySwitch
import com.baytale.baychat.ui.design.BayTextField
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

data class PlaneKindVisual(
    val icon: ImageVector,
    val label: String,
    val tint: Color,
)

@Composable
fun rememberPlaneKindVisual(kind: String): PlaneKindVisual {
    val colors = BayTheme.colors
    return when (kind.lowercase()) {
        "apk" -> PlaneKindVisual(Icons.Rounded.Android, "安装包", colors.fileTypeColors.apk)
        "image" -> PlaneKindVisual(Icons.Rounded.Image, "图片", colors.fileTypeColors.image)
        "video" -> PlaneKindVisual(Icons.Rounded.Videocam, "视频", colors.fileTypeColors.video)
        "audio" -> PlaneKindVisual(Icons.Rounded.AudioFile, "音频", colors.fileTypeColors.audio)
        "archive" -> PlaneKindVisual(Icons.Rounded.FolderZip, "压缩包", colors.fileTypeColors.archive)
        "doc" -> PlaneKindVisual(Icons.Rounded.Description, "文档", colors.fileTypeColors.doc)
        else -> PlaneKindVisual(Icons.Rounded.InsertDriveFile, "文件", colors.accent)
    }
}

/**
 * 纸飞机详情预览弹窗：文件清单为主体 + 发送者会话气泡预览 + 免密/直通加入操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaneViewerSheet(
    plane: PaperPlane,
    joining: Boolean,
    onJoin: () -> Unit,
    onReport: (onResult: (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    var reported by remember(plane.id) { mutableStateOf(false) }

    BayBottomSheet(
        onDismiss = { if (!joining) onDismiss() },
        title = "纸飞机 · 公开的文件分享",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(BaySpacing.md),
        ) {
            // 放飞者资料与状态标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                BayAvatar(emojiId = plane.senderAvatar, size = 40.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                    ) {
                        Text(
                            text = plane.senderName.ifBlank { "匿名旅人" },
                            style = BayType.headline,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box(
                            modifier = Modifier
                                .clip(BayShapes.fullShape)
                                .background(colors.fill)
                                .padding(horizontal = BaySpacing.sm, vertical = 2.dp),
                        ) {
                            Text(
                                text = "#${plane.roomCode}",
                                style = BayType.caption.copy(fontFamily = FontFamily.Monospace),
                                color = colors.textSecondary,
                            )
                        }
                    }
                    Text(
                        text = "放飞于 ${FormatUtils.formatRelativeTime(plane.createdAt)} · 仅含公开文件清单",
                        style = BayType.caption,
                        color = colors.textSecondary,
                    )
                }

                // 状态徽章（免密进入 / 可直接加入 / 仅展示）
                val badgeBg: Color
                val badgeText: String
                val badgeColor: Color
                when {
                    !plane.allowEntry -> {
                        badgeBg = colors.fill
                        badgeText = "仅展示"
                        badgeColor = colors.textSecondary
                    }
                    plane.hasPassword -> {
                        badgeBg = colors.accentSoft
                        badgeText = "免密进入"
                        badgeColor = colors.accent
                    }
                    else -> {
                        badgeBg = colors.success.copy(alpha = if (colors.isDark) 0.22f else 0.14f)
                        badgeText = "可直接加入"
                        badgeColor = colors.success
                    }
                }
                Row(
                    modifier = Modifier
                        .clip(BayShapes.fullShape)
                        .background(badgeBg)
                        .padding(horizontal = BaySpacing.sm, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (plane.allowEntry && plane.hasPassword) {
                        Icon(
                            imageVector = Icons.Rounded.VpnKey,
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Text(
                        text = badgeText,
                        style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = badgeColor,
                    )
                }

                BayIconButton(
                    icon = Icons.Rounded.Flag,
                    contentDescription = "举报该纸飞机",
                    enabled = !reported && !joining,
                    onClick = {
                        reported = true
                        onReport { ok ->
                            if (!ok) reported = false
                        }
                    },
                    style = BayIconStyle.Filled,
                    size = 34.dp,
                )
            }

            // 会话风格预览框：文件清单卡片主体 + 发送者留言气泡 + 系统锁提示
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BayShapes.mdShape)
                    .background(colors.fill)
                    .padding(BaySpacing.lg),
                verticalArrangement = Arrangement.spacedBy(BaySpacing.md),
            ) {
                if (plane.files.isNotEmpty()) {
                        Text(
                            text = "房间内可下载文件清单（${plane.files.size} 个）",
                            style = BayType.footnote.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textSecondary,
                        )
                        plane.files.forEach { file ->
                            val visual = rememberPlaneKindVisual(file.kind)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(BayShapes.smShape)
                                    .background(colors.surfaceElevated)
                                    .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(BayShapes.xsShape)
                                        .background(visual.tint.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = visual.icon,
                                        contentDescription = visual.label,
                                        tint = visual.tint,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        style = BayType.subhead.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val sizeLabel = if (file.size > 0L) {
                                        "${visual.label} · ${FormatUtils.formatBytes(file.size)}"
                                    } else {
                                        visual.label
                                    }
                                    Text(
                                        text = sizeLabel,
                                        style = BayType.caption,
                                        color = colors.textSecondary,
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .clip(BayShapes.fullShape)
                                        .background(colors.fill)
                                        .padding(horizontal = BaySpacing.sm, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = null,
                                        tint = colors.textTertiary,
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Text(
                                        text = "待进房下载",
                                        style = BayType.caption,
                                        color = colors.textTertiary,
                                    )
                                }
                            }
                        }
                    }

                    if (plane.message.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                        ) {
                            BayAvatar(emojiId = plane.senderAvatar, size = 28.dp)
                            Box(
                                modifier = Modifier
                                    .clip(BayShapes.mdShape)
                                    .background(colors.surfaceElevated)
                                    .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
                            ) {
                                Text(
                                    text = plane.message,
                                    style = BayType.subhead,
                                    color = colors.textPrimary,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clip(BayShapes.fullShape)
                            .background(colors.surfaceElevated)
                            .padding(horizontal = BaySpacing.md, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = if (plane.allowEntry) {
                                "加入房间即可下载文件和聊天"
                            } else {
                                "放飞者仅公开文件清单，未开放从纸飞机直接进房"
                            },
                            style = BayType.caption,
                            color = colors.textSecondary,
                        )
                    }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                BayButton(
                    text = "关闭",
                    onClick = onDismiss,
                    enabled = !joining,
                    style = BayButtonStyle.Secondary,
                    size = BayButtonSize.Medium,
                    modifier = Modifier.weight(1f),
                )
                BayButton(
                    text = when {
                        !plane.allowEntry -> "放飞者未开放进入"
                        joining -> "正在加入房间..."
                        else -> "加入房间下载并聊天"
                    },
                    onClick = onJoin,
                    enabled = plane.allowEntry && !joining,
                    loading = joining,
                    style = BayButtonStyle.Primary,
                    size = BayButtonSize.Medium,
                    modifier = Modifier.weight(2f),
                )
            }

            Spacer(modifier = Modifier.height(BaySpacing.xs))
        }
    }
}

/**
 * 房间内「放飞纸飞机（分享房间文件）」底栏弹窗：默认勾选最近 5 个文件元数据，不上传文件内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaneComposerSheet(
    roomCode: String,
    hasPassword: Boolean,
    isOwner: Boolean,
    submitting: Boolean,
    candidateFiles: List<PlaneFileMeta>,
    onConfirm: (message: String, files: List<PlaneFileMeta>, allowEntry: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
) {
    val colors = BayTheme.colors
    val selectedIndices = remember(roomCode, candidateFiles) {
        mutableStateListOf<Int>().apply {
            candidateFiles.take(5).indices.forEach { add(it) }
        }
    }
    var message by remember { mutableStateOf("") }
    val canToggleEntry = !hasPassword || isOwner
    var allowEntry by remember(hasPassword, isOwner) { mutableStateOf(canToggleEntry) }

    val chosenFiles = selectedIndices.mapNotNull { candidateFiles.getOrNull(it) }
    val canSubmit = chosenFiles.isNotEmpty() || message.trim().isNotEmpty()

    BayBottomSheet(
        onDismiss = onDismiss,
        title = "放飞纸飞机（分享房间文件）",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(BaySpacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "选择公开的文件信息（最多 5 个）",
                    style = BayType.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textSecondary,
                )
                Text(
                    text = "${chosenFiles.size}/5",
                    style = BayType.caption.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                    color = colors.accent,
                )
            }

            if (candidateFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BayShapes.mdShape)
                        .background(colors.fill)
                        .padding(BaySpacing.lg),
                ) {
                    Text(
                        text = "当前房间暂无已发送文件记录，可直接填写留言后放飞到大厅广场。",
                        style = BayType.footnote,
                        color = colors.textSecondary,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                ) {
                    candidateFiles.forEachIndexed { idx, file ->
                        val selected = idx in selectedIndices
                        val visual = rememberPlaneKindVisual(file.kind)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 46.dp)
                                .clip(BayShapes.smShape)
                                .background(if (selected) colors.accentSoft else colors.fill)
                                .bayClickable {
                                    if (selected) {
                                        selectedIndices.remove(idx)
                                    } else if (selectedIndices.size >= 5) {
                                        onToast("单架纸飞机最多附带 5 个文件信息")
                                    } else {
                                        selectedIndices.add(idx)
                                    }
                                }
                                .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(BayShapes.xsShape)
                                    .background(if (selected) colors.accent else colors.surfaceElevated),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = colors.onAccent,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                            Icon(
                                imageVector = visual.icon,
                                contentDescription = null,
                                tint = visual.tint,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = file.name,
                                style = BayType.subhead,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (file.size > 0L) {
                                Text(
                                    text = FormatUtils.formatBytes(file.size),
                                    style = BayType.caption.copy(fontFamily = FontFamily.Monospace),
                                    color = colors.textTertiary,
                                )
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.xs)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "留言（选填，最多 120 字）",
                        style = BayType.footnote.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textSecondary,
                    )
                    Text(
                        text = "${message.trim().length}/120",
                        style = BayType.caption.copy(fontFamily = FontFamily.Monospace),
                        color = colors.textTertiary,
                    )
                }
                BayTextField(
                    value = message,
                    onValueChange = { if (it.length <= 120) message = it },
                    placeholder = "例如：已上传软件安装包，进房可直接下载",
                    singleLine = false,
                    maxLines = 3,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BayShapes.mdShape)
                    .background(colors.fill)
                    .padding(BaySpacing.lg),
                verticalArrangement = Arrangement.spacedBy(BaySpacing.xs),
            ) {
                    if (hasPassword && !isOwner) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "加密房间仅房主可开启「允许通过纸飞机进入房间」，本次将作为「仅展示」放飞。",
                                style = BayType.footnote,
                                color = colors.textSecondary,
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).padding(end = BaySpacing.md),
                            ) {
                                Text(
                                    text = "允许通过纸飞机进入房间",
                                    style = BayType.subhead.copy(fontWeight = FontWeight.SemiBold),
                                    color = colors.textPrimary,
                                )
                                Text(
                                    text = if (hasPassword) {
                                        "你是房主：开启后访客点开纸飞机可跳过密码直接进房"
                                    } else {
                                        "关闭后仅作公开文件清单展示，不允许点击直通进房"
                                    },
                                    style = BayType.caption,
                                    color = colors.textSecondary,
                                )
                            }
                            BaySwitch(
                                checked = allowEntry,
                                onCheckedChange = { allowEntry = it },
                            )
                        }
                        if (hasPassword && isOwner && allowEntry) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VpnKey,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "已启用房主免密入口，密码仅保存在服务端内存中",
                                    style = BayType.caption,
                                    color = colors.accent,
                                )
                            }
                        }
                    }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                BayButton(
                    text = "取消",
                    onClick = onDismiss,
                    enabled = !submitting,
                    style = BayButtonStyle.Secondary,
                    size = BayButtonSize.Medium,
                    modifier = Modifier.weight(1f),
                )
                BayButton(
                    text = if (submitting) "放飞中..." else "放飞到大厅",
                    onClick = { onConfirm(message.trim(), chosenFiles, allowEntry) },
                    enabled = canSubmit && !submitting,
                    loading = submitting,
                    style = BayButtonStyle.Primary,
                    size = BayButtonSize.Medium,
                    modifier = Modifier.weight(2f),
                )
            }

            Spacer(modifier = Modifier.height(BaySpacing.xs))
        }
    }
}
