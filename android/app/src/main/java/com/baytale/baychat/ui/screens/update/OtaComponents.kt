package com.baytale.baychat.ui.screens.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.BuildConfig
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.service.OtaUpdateState
import com.baytale.baychat.service.OtaUpdateStatus
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayCard
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.design.BayProgressBar
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * 首页顶部应用自动更新状态横幅：覆盖 AVAILABLE / DOWNLOADING / PAUSED / VERIFYING / READY_TO_INSTALL / ERROR。
 */
@Composable
fun UpdateBannerCard(
    ota: OtaUpdateState,
    onStartOrResume: () -> Unit,
    onPause: () -> Unit,
    onInstall: () -> Unit,
    onIgnore: () -> Unit,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val release = ota.release ?: return

    BayCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onOpenDetail,
        padding = BaySpacing.md,
    ) {
        when (ota.status) {
            OtaUpdateStatus.AVAILABLE -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BannerIconBadge(
                        icon = Icons.Rounded.SystemUpdate,
                        tint = colors.accent,
                        container = colors.accentSoft,
                    )
                    Spacer(modifier = Modifier.width(BaySpacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "发现新版本 v${release.versionName}" + if (ota.isForceUpdate) " · 必要更新" else "",
                            style = BayType.subhead.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val desc = when {
                            ota.hasPartialBreakpoint ->
                                "已保存进度 ${ota.progressPercent}% (${FormatUtils.formatBytes(ota.downloadedBytes)} / ${FormatUtils.formatBytes(ota.totalBytes)})"
                            release.releaseNotes.isNotBlank() -> release.releaseNotes
                            release.sizeBytes > 0L -> "安装包 ${FormatUtils.formatBytes(release.sizeBytes)} · 支持断点续传"
                            else -> "支持后台断点下载与 SHA-256 校验"
                        }
                        Text(
                            text = desc,
                            style = BayType.caption,
                            color = colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(BaySpacing.sm))
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                    ) {
                        BayButton(
                            text = if (ota.hasPartialBreakpoint) "继续 ${ota.progressPercent}%" else "下载更新",
                            onClick = onStartOrResume,
                            style = BayButtonStyle.Primary,
                            size = BayButtonSize.Small,
                        )
                        if (!ota.isForceUpdate) {
                            BayButton(
                                text = "忽略",
                                onClick = onIgnore,
                                style = BayButtonStyle.Plain,
                                size = BayButtonSize.Small,
                            )
                        }
                    }
                }
            }

            OtaUpdateStatus.DOWNLOADING -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val speedLabel = if (ota.speedBytesPerSec > 0L) {
                            " · ${FormatUtils.formatBytes(ota.speedBytesPerSec)}/s"
                        } else ""
                        Text(
                            text = "正在下载 v${release.versionName} · ${ota.progressPercent}%$speedLabel",
                            style = BayType.subhead.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sizeLine = buildString {
                            append(FormatUtils.formatBytes(ota.downloadedBytes))
                            if (ota.totalBytes > 0L) append(" / ${FormatUtils.formatBytes(ota.totalBytes)}")
                            append(" · 支持后台继续")
                        }
                        Text(
                            text = sizeLine,
                            style = BayType.caption,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(BaySpacing.sm))
                    BayButton(
                        text = "暂停",
                        onClick = onPause,
                        leadingIcon = Icons.Rounded.Pause,
                        style = BayButtonStyle.Secondary,
                        size = BayButtonSize.Small,
                    )
                }
                Spacer(modifier = Modifier.height(BaySpacing.sm))
                BayProgressBar(
                    fraction = ota.progressFraction.coerceIn(0.02f, 1f),
                    color = colors.accent,
                    trackColor = colors.fill,
                )
            }

            OtaUpdateStatus.PAUSED -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "v${release.versionName} 下载已暂停 · ${ota.progressPercent}%",
                            style = BayType.subhead.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.warning,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "已保留进度 ${FormatUtils.formatBytes(ota.downloadedBytes)}" +
                                if (ota.totalBytes > 0L) " / ${FormatUtils.formatBytes(ota.totalBytes)}" else "",
                            style = BayType.caption,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(BaySpacing.sm))
                    BayButton(
                        text = "继续下载",
                        onClick = onStartOrResume,
                        style = BayButtonStyle.Secondary,
                        size = BayButtonSize.Small,
                    )
                }
                Spacer(modifier = Modifier.height(BaySpacing.sm))
                BayProgressBar(
                    fraction = ota.progressFraction.coerceIn(0.02f, 1f),
                    color = colors.warning,
                    trackColor = colors.fill,
                )
            }

            OtaUpdateStatus.VERIFYING -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        strokeCap = StrokeCap.Round,
                        color = colors.accent,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "正在校验 v${release.versionName} 安装包",
                            style = BayType.subhead.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary,
                        )
                        Text(
                            text = "正在执行 SHA-256 完整性校验...",
                            style = BayType.caption,
                            color = colors.textSecondary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(BaySpacing.sm))
                BayProgressBar(
                    fraction = 1f,
                    color = colors.accent,
                    trackColor = colors.fill,
                )
            }

            OtaUpdateStatus.READY_TO_INSTALL -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BannerIconBadge(
                        icon = Icons.Rounded.VerifiedUser,
                        tint = colors.success,
                        container = colors.success.copy(alpha = if (colors.isDark) 0.22f else 0.12f),
                    )
                    Spacer(modifier = Modifier.width(BaySpacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "v${release.versionName} 安装包已就绪",
                            style = BayType.subhead.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.success,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "已完成 SHA-256 安全校验，点击即可安装",
                            style = BayType.caption,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(BaySpacing.sm))
                    BayButton(
                        text = "立即安装",
                        onClick = onInstall,
                        style = BayButtonStyle.Primary,
                        size = BayButtonSize.Small,
                    )
                }
            }

            OtaUpdateStatus.ERROR -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BannerIconBadge(
                        icon = Icons.Rounded.ErrorOutline,
                        tint = colors.danger,
                        container = colors.danger.copy(alpha = if (colors.isDark) 0.22f else 0.12f),
                    )
                    Spacer(modifier = Modifier.width(BaySpacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "v${release.versionName} 更新中断",
                            style = BayType.subhead.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.danger,
                            maxLines = 1,
                        )
                        val errText = buildString {
                            append(ota.errorMessage ?: "网络连接中断")
                            if (ota.hasPartialBreakpoint) {
                                append("（已保留 ${ota.progressPercent}% 断点）")
                            }
                        }
                        Text(
                            text = errText,
                            style = BayType.caption,
                            color = colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(BaySpacing.sm))
                    BayButton(
                        text = "重试",
                        onClick = onStartOrResume,
                        style = BayButtonStyle.DestructiveSoft,
                        size = BayButtonSize.Small,
                    )
                }
            }

            else -> Unit
        }
    }
}

/**
 * 应用更新详情与断点续传控制弹窗：零描边、统一基于 [BayDialog] 构建。
 */
@Composable
fun UpdateAvailableDialog(
    ota: OtaUpdateState,
    onStartOrResume: () -> Unit,
    onPause: () -> Unit,
    onInstall: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    val release = ota.release ?: return

    val confirmText = when (ota.status) {
        OtaUpdateStatus.AVAILABLE -> if (ota.hasPartialBreakpoint) "继续下载 (${ota.progressPercent}%)" else "立即下载"
        OtaUpdateStatus.DOWNLOADING -> if (!ota.isForceUpdate) "后台下载" else "暂停下载"
        OtaUpdateStatus.PAUSED -> "继续下载"
        OtaUpdateStatus.VERIFYING -> "校验中..."
        OtaUpdateStatus.READY_TO_INSTALL -> "立即安装"
        OtaUpdateStatus.ERROR -> "重试下载"
        else -> "确定"
    }

    val onConfirmAction: () -> Unit = when (ota.status) {
        OtaUpdateStatus.AVAILABLE,
        OtaUpdateStatus.PAUSED,
        OtaUpdateStatus.ERROR -> onStartOrResume
        OtaUpdateStatus.DOWNLOADING -> if (!ota.isForceUpdate) onDismiss else onPause
        OtaUpdateStatus.VERIFYING -> ({})
        OtaUpdateStatus.READY_TO_INSTALL -> onInstall
        else -> onDismiss
    }

    val dismissLabel: String? = when (ota.status) {
        OtaUpdateStatus.AVAILABLE -> if (!ota.isForceUpdate) "忽略" else null
        OtaUpdateStatus.DOWNLOADING -> if (!ota.isForceUpdate) "暂停" else null
        OtaUpdateStatus.PAUSED,
        OtaUpdateStatus.READY_TO_INSTALL,
        OtaUpdateStatus.ERROR -> if (!ota.isForceUpdate) "稍后" else null
        else -> null
    }

    val onDismissButtonAction: () -> Unit = when (ota.status) {
        OtaUpdateStatus.AVAILABLE -> onIgnore
        OtaUpdateStatus.DOWNLOADING -> onPause
        else -> onDismiss
    }

    val metaLine = buildString {
        append("当前 v${BuildConfig.VERSION_NAME}")
        if (release.sizeBytes > 0L) append(" · 包体 ${FormatUtils.formatBytes(release.sizeBytes)}")
        if (ota.isForceUpdate) append(" · 必要升级")
    }

    BayDialog(
        title = "发现新版本 v${release.versionName}",
        message = metaLine,
        confirmText = confirmText,
        onConfirm = onConfirmAction,
        dismissText = dismissLabel,
        onDismiss = {
            if (dismissLabel != null) {
                onDismissButtonAction()
            } else if (!ota.isForceUpdate) {
                onDismiss()
            }
        },
        destructive = false,
        confirmEnabled = ota.status != OtaUpdateStatus.VERIFYING,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.md)) {
            // 更新说明区
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .clip(BayShapes.smShape)
                    .background(colors.fill)
                    .verticalScroll(rememberScrollState())
                    .padding(BaySpacing.md),
                verticalArrangement = Arrangement.spacedBy(BaySpacing.xs),
            ) {
                Text(
                    text = "更新内容",
                    style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textSecondary,
                )
                Text(
                    text = release.releaseNotes.trim().ifBlank { "性能优化与连接稳定性提升，支持后台断点续传更新。" },
                    style = BayType.footnote,
                    color = colors.textPrimary,
                )
            }

            // 分状态进度与说明区
            when (ota.status) {
                OtaUpdateStatus.AVAILABLE -> {
                    if (ota.hasPartialBreakpoint) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "检测到本地断点 ${ota.progressPercent}%（${FormatUtils.formatBytes(ota.downloadedBytes)} / ${FormatUtils.formatBytes(ota.totalBytes)}）",
                                style = BayType.caption.copy(fontWeight = FontWeight.Medium),
                                color = colors.accent,
                            )
                            BayProgressBar(
                                fraction = ota.progressFraction.coerceIn(0.02f, 1f),
                                color = colors.accent,
                                trackColor = colors.accentSoft,
                            )
                        }
                    } else {
                        Text(
                            text = "支持断点续传、主备源切换、后台保活下载与 SHA-256 完整性校验。",
                            style = BayType.caption,
                            color = colors.textSecondary,
                        )
                    }
                }

                OtaUpdateStatus.DOWNLOADING -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "正在后台下载 · ${ota.progressPercent}%",
                                style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.accent,
                            )
                            if (ota.speedBytesPerSec > 0L) {
                                Text(
                                    text = "${FormatUtils.formatBytes(ota.speedBytesPerSec)}/s",
                                    style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                                    color = colors.textSecondary,
                                )
                            }
                        }
                        BayProgressBar(
                            fraction = ota.progressFraction.coerceIn(0.02f, 1f),
                            color = colors.accent,
                            trackColor = colors.accentSoft,
                        )
                        Text(
                            text = "${FormatUtils.formatBytes(ota.downloadedBytes)} / ${FormatUtils.formatBytes(ota.totalBytes)}",
                            style = BayType.caption,
                            color = colors.textTertiary,
                        )
                    }
                }

                OtaUpdateStatus.PAUSED -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "下载已暂停 · 已保存 ${ota.progressPercent}%",
                            style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.warning,
                        )
                        BayProgressBar(
                            fraction = ota.progressFraction.coerceIn(0.02f, 1f),
                            color = colors.warning,
                            trackColor = colors.fill,
                        )
                        Text(
                            text = "已保存 ${FormatUtils.formatBytes(ota.downloadedBytes)}，点击继续可从断点续传",
                            style = BayType.caption,
                            color = colors.textSecondary,
                        )
                    }
                }

                OtaUpdateStatus.VERIFYING -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "正在校验 SHA-256 完整性...",
                            style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.accent,
                        )
                        BayProgressBar(
                            fraction = 1f,
                            color = colors.accent,
                            trackColor = colors.accentSoft,
                        )
                    }
                }

                OtaUpdateStatus.READY_TO_INSTALL -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(BayShapes.smShape)
                            .background(colors.success.copy(alpha = if (colors.isDark) 0.22f else 0.12f))
                            .padding(BaySpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            tint = colors.success,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "v${release.versionName} 已通过 SHA-256 校验，可立即安装覆盖升级",
                            style = BayType.caption.copy(fontWeight = FontWeight.Medium),
                            color = colors.success,
                        )
                    }
                }

                OtaUpdateStatus.ERROR -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(BayShapes.smShape)
                            .background(colors.danger.copy(alpha = if (colors.isDark) 0.22f else 0.12f))
                            .padding(BaySpacing.md),
                    ) {
                        val errDetail = buildString {
                            append(ota.errorMessage ?: "下载中断")
                            if (ota.hasPartialBreakpoint) {
                                append("（已保留 ${ota.progressPercent}% 断点）")
                            }
                        }
                        Text(
                            text = errDetail,
                            style = BayType.caption.copy(fontWeight = FontWeight.Medium),
                            color = colors.danger,
                        )
                    }
                }

                else -> Unit
            }

            if (!ota.isForceUpdate && ota.status == OtaUpdateStatus.AVAILABLE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    BayButton(
                        text = "暂不更新",
                        onClick = onDismiss,
                        style = BayButtonStyle.Plain,
                        size = BayButtonSize.Small,
                    )
                }
            }
        }
    }
}

@Composable
private fun BannerIconBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    container: androidx.compose.ui.graphics.Color,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(BayShapes.smShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}
