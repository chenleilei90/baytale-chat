package com.baytale.baychat.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.CloudUploadStatus
import com.baytale.baychat.core.model.FileTransferInfo
import com.baytale.baychat.core.model.TransferDirection
import com.baytale.baychat.core.model.TransferStatus
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayFileTypeIcon
import com.baytale.baychat.ui.design.BayProgressBar
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType
import java.util.Locale

/**
 * 图片消息卡片（支持鉴权请求头加载本地/云端图片、接收进度与失败重传）。
 */
@Composable
fun ImageMessageCard(
    item: ChatItem,
    imageHeaders: Map<String, String>,
    onPreviewImage: () -> Unit,
    onCancelTransfer: (peerId: String, transferId: String) -> Unit,
    onRequestResend: () -> Unit,
    onRetryCloudUpload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val context = LocalContext.current
    val cardBg = if (colors.isDark) colors.surfaceElevated else colors.surface
    val imgModel = remember(item.localFilePath, item.imageUrl, item.transfer?.localPath, item.transfer?.contentUri) {
        resolveImageModel(item)
    }
    val transfer = item.transfer

    Column(
        modifier = modifier
            .widthIn(min = 172.dp, max = 264.dp)
            .clip(BayShapes.mdShape)
            .background(cardBg)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (imgModel != null) {
            val request = remember(imgModel, imageHeaders) {
                buildAuthedImageRequest(context, imgModel, imageHeaders)
            }
            AsyncImage(
                model = request,
                contentDescription = item.caption ?: "图片消息",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .bayClickable(onClick = onPreviewImage)
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 250.dp)
                    .clip(BayShapes.smShape),
            )
        } else {
            val pct = if (transfer != null && transfer.fileSize > 0L) {
                (transfer.transferredBytes.toFloat() / transfer.fileSize.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BayShapes.smShape)
                    .background(colors.fill)
                    .padding(BaySpacing.md),
                verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                Text(
                    text = when (transfer?.status) {
                        TransferStatus.ERROR -> transfer.errorMessage ?: "图片接收异常"
                        TransferStatus.CANCELLED -> "图片传输已取消"
                        else -> "正在接收图片 ${(pct * 100).toInt()}%"
                    },
                    style = BayType.footnote.copy(fontWeight = FontWeight.Medium),
                    color = if (transfer?.status == TransferStatus.ERROR) colors.danger else colors.textPrimary,
                )
                if (transfer == null || transfer.status == TransferStatus.TRANSFERRING || transfer.status == TransferStatus.WAITING_ACCEPT) {
                    BayProgressBar(fraction = maxOf(0.04f, pct))
                }
                if (transfer != null && (transfer.status == TransferStatus.TRANSFERRING || transfer.status == TransferStatus.WAITING_ACCEPT)) {
                    val peerId = transfer.targetPeerId ?: item.senderId
                    BayButton(
                        text = "取消",
                        onClick = { onCancelTransfer(peerId, transfer.transferId) },
                        style = BayButtonStyle.DestructiveSoft,
                        size = BayButtonSize.Small,
                    )
                } else if (transfer?.status == TransferStatus.ERROR || transfer?.status == TransferStatus.CANCELLED) {
                    BayButton(
                        text = if (item.isSelf) "重发图片" else "请求重传",
                        onClick = onRequestResend,
                        style = BayButtonStyle.Secondary,
                        size = BayButtonSize.Small,
                        leadingIcon = Icons.Rounded.Refresh,
                    )
                }
            }
        }

        if (!item.caption.isNullOrBlank()) {
            Text(
                text = item.caption,
                style = BayType.footnote,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        if (transfer?.savedDisplayPath != null) {
            Text(
                text = "已保存：${transfer.savedDisplayPath}",
                style = BayType.caption.copy(fontWeight = FontWeight.Normal),
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        if (item.isSelf && transfer?.cloudStatus == CloudUploadStatus.FAILED) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .bayClickable(onClick = onRetryCloudUpload)
                    .clip(BayShapes.xsShape)
                    .background(colors.danger.copy(alpha = if (colors.isDark) 0.22f else 0.12f))
                    .padding(horizontal = BaySpacing.sm, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudUpload,
                    contentDescription = null,
                    tint = colors.danger,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "云缓存上传失败，点击重试",
                    style = BayType.caption,
                    color = colors.danger,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 文件与压缩包传输气泡卡片（零描边，支持断点续传、取消、打开、分享、云缓存下载与重传请求）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileTransferCard(
    item: ChatItem,
    transfer: FileTransferInfo,
    onAccept: (peerId: String, transferId: String, resumeIfAvailable: Boolean) -> Unit,
    onReject: (peerId: String, transferId: String) -> Unit,
    onCancel: (peerId: String, transferId: String) -> Unit,
    onDownloadCached: (url: String) -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRequestResend: () -> Unit,
    onRetryCloudUpload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val cardBg = if (colors.isDark) colors.surfaceElevated else colors.surface
    val rawProgress = if (transfer.fileSize > 0L) {
        (transfer.transferredBytes.toFloat() / transfer.fileSize.toFloat()).coerceIn(0f, 1f)
    } else {
        1f
    }
    val barProgress = if (transfer.status == TransferStatus.TRANSFERRING) maxOf(0.02f, rawProgress) else rawProgress
    val pctNum = rawProgress * 100f
    val pctLabel = if (pctNum > 0f && pctNum < 10f) {
        String.format(Locale.US, "%.1f", pctNum)
    } else {
        pctNum.toInt().toString()
    }
    val hasResume = transfer.canResumeBytes > 0L
    val peerId = transfer.targetPeerId?.takeIf { it.isNotBlank() } ?: item.senderId
    val canOpenLocal = !transfer.contentUri.isNullOrBlank() || !transfer.localPath.isNullOrBlank()

    Column(
        modifier = modifier
            .width(284.dp)
            .clip(BayShapes.mdShape)
            .background(cardBg)
            .padding(BaySpacing.md),
        verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
    ) {
        // 文件图标与基础元信息
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
        ) {
            BayFileTypeIcon(
                fileName = transfer.fileName,
                mimeType = transfer.mimeType,
                size = 42.dp,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transfer.fileName,
                    style = BayType.callout.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                val sourceHint = buildFileSourceHint(transfer)
                Text(
                    text = "${FormatUtils.formatBytes(transfer.fileSize)} · $sourceHint",
                    style = BayType.caption.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 传输中或等待确认态进度条与速率 ETA
        if (transfer.status == TransferStatus.TRANSFERRING || transfer.status == TransferStatus.WAITING_ACCEPT) {
            Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.xs)) {
                BayProgressBar(fraction = barProgress)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when {
                            transfer.status == TransferStatus.WAITING_ACCEPT -> "等待对方确认接收..."
                            transfer.transferredBytes == 0L -> "已确认，正在建立数据流..."
                            else -> "$pctLabel% · ${FormatUtils.formatBytes(transfer.transferredBytes)} · ${FormatUtils.formatBytes(transfer.speedBps.toLong())}/s"
                        },
                        style = BayType.caption.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal),
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (transfer.status == TransferStatus.TRANSFERRING && transfer.speedBps > 0.0) {
                        val eta = FormatUtils.formatEta(transfer.etaSeconds)
                        if (eta.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(BaySpacing.xs))
                            Text(
                                text = eta,
                                style = BayType.caption.copy(fontFamily = FontFamily.Monospace),
                                color = colors.textTertiary,
                            )
                        }
                    }
                }
                if (transfer.resumedFromBytes > 0L) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = "已从 ${FormatUtils.formatBytes(transfer.resumedFromBytes)} 断点续传",
                            style = BayType.caption,
                            color = colors.accent,
                        )
                    }
                }
            }
        }

        // 完成状态提示条
        if (transfer.status == TransferStatus.COMPLETED) {
            val statusText = when {
                transfer.verified -> "校验通过"
                !transfer.savedDisplayPath.isNullOrBlank() -> "已保存至 ${transfer.savedDisplayPath}"
                !transfer.cachedDownloadUrl.isNullOrBlank() && transfer.direction == TransferDirection.RECEIVE -> "云端缓存可下载"
                transfer.isHistoryRecord -> "历史传输记录"
                else -> "传输完成"
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = colors.success,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = statusText,
                    style = BayType.caption,
                    color = colors.success,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 交互操作按钮区
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            when (transfer.status) {
                TransferStatus.OFFERING -> {
                    if (hasResume) {
                        BayButton(
                            text = "续传 (${FormatUtils.formatBytes(transfer.canResumeBytes)})",
                            onClick = { onAccept(peerId, transfer.transferId, true) },
                            style = BayButtonStyle.Primary,
                            size = BayButtonSize.Small,
                            leadingIcon = Icons.Rounded.Download,
                        )
                        BayButton(
                            text = "重下",
                            onClick = { onAccept(peerId, transfer.transferId, false) },
                            style = BayButtonStyle.Neutral,
                            size = BayButtonSize.Small,
                        )
                    } else {
                        BayButton(
                            text = "接收",
                            onClick = { onAccept(peerId, transfer.transferId, true) },
                            style = BayButtonStyle.Primary,
                            size = BayButtonSize.Small,
                            leadingIcon = Icons.Rounded.Download,
                        )
                    }
                    BayButton(
                        text = "拒绝",
                        onClick = { onReject(peerId, transfer.transferId) },
                        style = BayButtonStyle.DestructiveSoft,
                        size = BayButtonSize.Small,
                    )
                }

                TransferStatus.WAITING_ACCEPT, TransferStatus.TRANSFERRING -> {
                    BayButton(
                        text = "取消",
                        onClick = { onCancel(peerId, transfer.transferId) },
                        style = BayButtonStyle.DestructiveSoft,
                        size = BayButtonSize.Small,
                        leadingIcon = Icons.Rounded.Close,
                    )
                }

                TransferStatus.COMPLETED -> {
                    if (canOpenLocal) {
                        BayButton(
                            text = "打开",
                            onClick = onOpen,
                            style = BayButtonStyle.Primary,
                            size = BayButtonSize.Small,
                            leadingIcon = Icons.Rounded.FileOpen,
                        )
                        BayButton(
                            text = "分享",
                            onClick = onShare,
                            style = BayButtonStyle.Neutral,
                            size = BayButtonSize.Small,
                            leadingIcon = Icons.Rounded.Share,
                        )
                    }
                    val cachedUrl = transfer.cachedDownloadUrl
                    if (!cachedUrl.isNullOrBlank()) {
                        BayButton(
                            text = if (canOpenLocal) "重新下载" else "下载附件",
                            onClick = { onDownloadCached(cachedUrl) },
                            style = if (canOpenLocal) BayButtonStyle.Neutral else BayButtonStyle.Primary,
                            size = BayButtonSize.Small,
                            leadingIcon = Icons.Rounded.Download,
                        )
                    } else if (transfer.isHistoryRecord && !canOpenLocal) {
                        BayButton(
                            text = if (item.isSelf) "重发文件" else "请求重传",
                            onClick = onRequestResend,
                            style = BayButtonStyle.Secondary,
                            size = BayButtonSize.Small,
                            leadingIcon = Icons.Rounded.Refresh,
                        )
                    }
                }

                TransferStatus.REJECTED, TransferStatus.CANCELLED, TransferStatus.ERROR -> {
                    val statusDesc = when (transfer.status) {
                        TransferStatus.REJECTED -> "已拒绝接收"
                        TransferStatus.CANCELLED -> "传输已取消"
                        TransferStatus.ERROR -> transfer.errorMessage ?: "传输中断"
                        else -> ""
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = statusDesc,
                            style = BayType.footnote,
                            color = if (transfer.status == TransferStatus.ERROR) colors.danger else colors.textSecondary,
                        )
                        BayButton(
                            text = if (item.isSelf) "重发" else "请求重传",
                            onClick = onRequestResend,
                            style = BayButtonStyle.Secondary,
                            size = BayButtonSize.Small,
                            leadingIcon = Icons.Rounded.Refresh,
                        )
                    }
                }
            }
        }

        // 云缓存备份失败重试提示
        if (item.isSelf && transfer.cloudStatus == CloudUploadStatus.FAILED) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .bayClickable(onClick = onRetryCloudUpload)
                    .clip(BayShapes.xsShape)
                    .background(colors.danger.copy(alpha = if (colors.isDark) 0.22f else 0.12f))
                    .padding(horizontal = BaySpacing.sm, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudUpload,
                    contentDescription = null,
                    tint = colors.danger,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "云缓存上传失败，点击重试",
                    style = BayType.caption,
                    color = colors.danger,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun buildFileSourceHint(transfer: FileTransferInfo): String {
    return when {
        transfer.isHistoryRecord && !transfer.cachedDownloadUrl.isNullOrBlank() -> "云端缓存"
        transfer.isHistoryRecord -> "历史记录"
        transfer.direction == TransferDirection.SEND -> when {
            transfer.recipientsTotal > 1 -> "已发 ${transfer.recipientsDone}/${transfer.recipientsTotal}"
            !transfer.targetPeerName.isNullOrBlank() -> "发给 ${transfer.targetPeerName}"
            !transfer.cachedDownloadUrl.isNullOrBlank() -> "已存云缓存"
            else -> "P2P 发送"
        }
        else -> "P2P 接收"
    }
}
