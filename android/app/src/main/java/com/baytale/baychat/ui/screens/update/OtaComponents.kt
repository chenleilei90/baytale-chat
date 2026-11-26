package com.baytale.baychat.ui.screens.update

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * 单条变色动效后台下载状态线：按状态区分颜色与动效（正在下载流光、暂停呼吸、失败红线、就绪绿线）。
 */
@Composable
fun BayAnimatedStatusLine(
    status: OtaUpdateStatus,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val targetFraction = when (status) {
        OtaUpdateStatus.VERIFYING,
        OtaUpdateStatus.READY_TO_INSTALL -> 1f
        OtaUpdateStatus.ERROR -> progress.coerceIn(0.18f, 1f)
        else -> progress.coerceIn(0.04f, 1f)
    }
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "otaLineFraction",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "otaStatusLineMotion")
    // 下载/校验中：沿线持续向右扫过的亮光偏移（-0.25..1.25）
    val flowPhase by infiniteTransition.animateFloat(
        initialValue = -0.25f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "otaLineFlow",
    )
    // 暂停中：柔和呼吸透明度脉冲
    val breathAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "otaLineBreath",
    )

    val lineColor = when (status) {
        OtaUpdateStatus.PAUSED -> colors.warning
        OtaUpdateStatus.ERROR -> colors.danger
        OtaUpdateStatus.READY_TO_INSTALL -> colors.success
        else -> colors.accent
    }
    val lineAlpha = if (status == OtaUpdateStatus.PAUSED) breathAlpha else 1f

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(BayShapes.fullShape),
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        // 底轨
        drawRoundRect(
            color = colors.fill,
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = radius,
        )

        val activeWidth = (size.width * animatedFraction).coerceAtLeast(size.height)
        drawRoundRect(
            color = lineColor.copy(alpha = lineAlpha),
            topLeft = Offset.Zero,
            size = Size(width = activeWidth, height = size.height),
            cornerRadius = radius,
        )

        // 正在下载 / 正在校验：在进度条上叠加平滑流动的高光脉冲段
        if (status == OtaUpdateStatus.DOWNLOADING || status == OtaUpdateStatus.VERIFYING) {
            val pulseWidth = (activeWidth * 0.28f).coerceAtLeast(size.height * 3f).coerceAtMost(activeWidth)
            val travelSpan = (activeWidth - pulseWidth).coerceAtLeast(0f)
            val pulseX = (flowPhase.coerceIn(0f, 1f) * travelSpan)
            val shimmerAlpha = if (flowPhase in 0f..1f) 0.48f else 0.16f
            drawRoundRect(
                color = colors.onAccent.copy(alpha = shimmerAlpha),
                topLeft = Offset(x = pulseX, y = 0f),
                size = Size(width = pulseWidth, height = size.height),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * 首页顶部应用自动更新状态横幅：
 * - AVAILABLE：显示新版本发现提示与操作按钮
 * - DOWNLOADING / PAUSED / VERIFYING / READY_TO_INSTALL / ERROR：极简单行状态与百分比 + 单条变色动效状态线
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
                            ota.hasPartialBreakpoint -> "已保存断点 ${ota.progressPercent}%，可继续更新"
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

            OtaUpdateStatus.DOWNLOADING,
            OtaUpdateStatus.PAUSED,
            OtaUpdateStatus.VERIFYING,
            OtaUpdateStatus.READY_TO_INSTALL,
            OtaUpdateStatus.ERROR -> {
                val statusColor = when (ota.status) {
                    OtaUpdateStatus.PAUSED -> colors.warning
                    OtaUpdateStatus.ERROR -> colors.danger
                    OtaUpdateStatus.READY_TO_INSTALL -> colors.success
                    else -> colors.accent
                }
                val statusText = when (ota.status) {
                    OtaUpdateStatus.DOWNLOADING -> "正在下载 v${release.versionName}"
                    OtaUpdateStatus.PAUSED -> "下载暂停"
                    OtaUpdateStatus.VERIFYING -> "正在校验完整性"
                    OtaUpdateStatus.READY_TO_INSTALL -> "v${release.versionName} 已就绪"
                    OtaUpdateStatus.ERROR -> "下载失败"
                    else -> ""
                }
                val percentText = when (ota.status) {
                    OtaUpdateStatus.READY_TO_INSTALL -> "100%"
                    OtaUpdateStatus.VERIFYING -> "校验中"
                    else -> "${ota.progressPercent}%"
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusColor),
                            )
                            Text(
                                text = statusText,
                                style = BayType.subhead.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Text(
                                text = percentText,
                                style = BayType.subhead.copy(fontWeight = FontWeight.SemiBold),
                                color = statusColor,
                                maxLines = 1,
                            )
                        }

                        Spacer(modifier = Modifier.width(BaySpacing.sm))

                        when (ota.status) {
                            OtaUpdateStatus.DOWNLOADING -> {
                                BayButton(
                                    text = "暂停",
                                    onClick = onPause,
                                    style = BayButtonStyle.Secondary,
                                    size = BayButtonSize.Small,
                                )
                            }
                            OtaUpdateStatus.PAUSED -> {
                                BayButton(
                                    text = "继续下载",
                                    onClick = onStartOrResume,
                                    style = BayButtonStyle.Primary,
                                    size = BayButtonSize.Small,
                                )
                            }
                            OtaUpdateStatus.ERROR -> {
                                BayButton(
                                    text = "重试",
                                    onClick = onStartOrResume,
                                    style = BayButtonStyle.DestructiveSoft,
                                    size = BayButtonSize.Small,
                                )
                            }
                            OtaUpdateStatus.READY_TO_INSTALL -> {
                                BayButton(
                                    text = "安装",
                                    onClick = onInstall,
                                    style = BayButtonStyle.Primary,
                                    size = BayButtonSize.Small,
                                )
                            }
                            else -> Unit
                        }
                    }

                    // 补充传输大小与速率详情
                    if (ota.status == OtaUpdateStatus.DOWNLOADING || ota.status == OtaUpdateStatus.PAUSED) {
                        val sizeTotal = ota.totalBytes.coerceAtLeast(release.sizeBytes)
                        val detail = buildString {
                            if (ota.status == OtaUpdateStatus.PAUSED) append("已保留断点 ")
                            append(FormatUtils.formatBytes(ota.downloadedBytes))
                            if (sizeTotal > 0L) append(" / ${FormatUtils.formatBytes(sizeTotal)}")
                            if (ota.status == OtaUpdateStatus.DOWNLOADING && ota.speedBytesPerSec > 0L) {
                                append(" · ${FormatUtils.formatBytes(ota.speedBytesPerSec)}/s")
                            }
                        }
                        Text(
                            text = detail,
                            style = BayType.caption,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // 单条变色动效状态线
                    BayAnimatedStatusLine(
                        status = ota.status,
                        progress = ota.progressFraction,
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
        OtaUpdateStatus.DOWNLOADING -> if (!ota.isForceUpdate) "暂停下载" else null
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
                                text = "检测到本地断点 ${ota.progressPercent}%",
                                style = BayType.caption.copy(fontWeight = FontWeight.Medium),
                                color = colors.accent,
                            )
                            BayAnimatedStatusLine(
                                status = ota.status,
                                progress = ota.progressFraction,
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
                    val dlTotal = ota.totalBytes.coerceAtLeast(release.sizeBytes)
                    val dlMeta = buildString {
                        append(FormatUtils.formatBytes(ota.downloadedBytes))
                        if (dlTotal > 0L) append(" / ${FormatUtils.formatBytes(dlTotal)}")
                        if (ota.speedBytesPerSec > 0L) append(" · ${FormatUtils.formatBytes(ota.speedBytesPerSec)}/s")
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "正在下载",
                                style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.textPrimary,
                            )
                            Text(
                                text = "${ota.progressPercent}%",
                                style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.accent,
                            )
                        }
                        BayAnimatedStatusLine(
                            status = ota.status,
                            progress = ota.progressFraction,
                        )
                        Text(
                            text = dlMeta,
                            style = BayType.caption,
                            color = colors.textSecondary,
                        )
                    }
                }

                OtaUpdateStatus.PAUSED -> {
                    val pausedTotal = ota.totalBytes.coerceAtLeast(release.sizeBytes)
                    val pausedMeta = buildString {
                        append("已保留断点 ${FormatUtils.formatBytes(ota.downloadedBytes)}")
                        if (pausedTotal > 0L) append(" / ${FormatUtils.formatBytes(pausedTotal)}")
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "下载已暂停",
                                style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.textPrimary,
                            )
                            Text(
                                text = "${ota.progressPercent}%",
                                style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.warning,
                            )
                        }
                        BayAnimatedStatusLine(
                            status = ota.status,
                            progress = ota.progressFraction,
                        )
                        Text(
                            text = pausedMeta,
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
                        BayAnimatedStatusLine(
                            status = ota.status,
                            progress = 1f,
                        )
                    }
                }

                OtaUpdateStatus.READY_TO_INSTALL -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "v${release.versionName} 已就绪",
                                style = BayType.caption.copy(fontWeight = FontWeight.Medium),
                                color = colors.success,
                            )
                            Text(
                                text = "100%",
                                style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.success,
                            )
                        }
                        BayAnimatedStatusLine(
                            status = ota.status,
                            progress = 1f,
                        )
                    }
                }

                OtaUpdateStatus.ERROR -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = ota.errorMessage ?: "下载失败",
                                style = BayType.caption.copy(fontWeight = FontWeight.Medium),
                                color = colors.danger,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(BaySpacing.sm))
                            Text(
                                text = "${ota.progressPercent}%",
                                style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.danger,
                            )
                        }
                        BayAnimatedStatusLine(
                            status = ota.status,
                            progress = ota.progressFraction,
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
