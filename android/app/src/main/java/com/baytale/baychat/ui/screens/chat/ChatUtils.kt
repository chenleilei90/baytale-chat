package com.baytale.baychat.ui.screens.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import coil.request.ImageRequest
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.LinkTransportType
import com.baytale.baychat.ui.ActiveRoomSession
import com.baytale.baychat.ui.theme.BayTheme
import java.io.File
import java.util.Locale

// ============================================================================
// 聊天模块内部公共工具与常量定义
// ============================================================================

/**
 * 消息长按快捷表情回应白名单（全聊天模块唯一允许出现表情符号的列表）。
 */
internal val REACTION_EMOJIS = listOf("👍", "❤️", "😂", "🔥", "🎉", "👀")

/**
 * 在线链路状态展示模型。
 */
internal data class LinkStatusBadge(
    val color: Color,
    val label: String,
    val connected: Boolean,
)

/**
 * 计算当前房间连接状态的语义色与提示文本。
 */
@Composable
internal fun rememberLinkStatusBadge(room: ActiveRoomSession): LinkStatusBadge {
    val colors = BayTheme.colors
    return remember(room.peers, room.linkStatuses, room.wsConnected, colors) {
        when {
            !room.wsConnected -> LinkStatusBadge(
                color = colors.warning,
                label = "信令重连中",
                connected = false,
            )
            room.peers.isEmpty() -> LinkStatusBadge(
                color = colors.accent,
                label = "等待成员加入",
                connected = false,
            )
            else -> {
                val ready = room.peers.keys.mapNotNull { room.linkStatuses[it] }.filter { it.dataReady }
                if (ready.isEmpty()) {
                    LinkStatusBadge(
                        color = colors.warning,
                        label = "正在建立连接",
                        connected = false,
                    )
                } else {
                    val rtt = ready.firstNotNullOfOrNull { it.rttMs }?.let { " ${it}ms" }.orEmpty()
                    when {
                        ready.any { it.transport == LinkTransportType.RELAY_TURN } -> LinkStatusBadge(
                            color = colors.warning,
                            label = "中转连接$rtt",
                            connected = true,
                        )
                        ready.all { it.transport == LinkTransportType.P2P_LAN } -> LinkStatusBadge(
                            color = colors.success,
                            label = "局域网直连$rtt",
                            connected = true,
                        )
                        else -> LinkStatusBadge(
                            color = colors.success,
                            label = "P2P 直连$rtt",
                            connected = true,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 将文本写入系统剪贴板（仅由用户显式点击触发）。
 */
internal fun copyTextToClipboard(context: Context, text: String, label: String = "BayChat") {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * 读取系统剪贴板当前纯文本片段。
 */
internal fun readTextFromClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
    return item.coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * 调起系统分享面板发送房间邀请链接。
 */
internal fun shareRoomInvite(context: Context, code: String, url: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "加入 BayChat 房间 #$code 进行文件传输与交流：$url")
    }
    context.startActivity(Intent.createChooser(sendIntent, "分享房间邀请"))
}

/**
 * 解析图片消息对应的加载模型（优先本地已保存文件，其次持久化 URI，最后服务端鉴权链接）。
 */
internal fun resolveImageModel(item: ChatItem): Any? {
    val localPath = item.localFilePath?.takeIf { it.isNotBlank() && File(it).exists() }
        ?: item.transfer?.localPath?.takeIf { it.isNotBlank() && File(it).exists() }
    if (localPath != null) return File(localPath)

    val contentUri = item.transfer?.contentUri?.takeIf { it.isNotBlank() }
    if (contentUri != null) return contentUri

    val url = item.imageUrl?.takeIf { it.isNotBlank() } ?: return null
    return if (url.startsWith("/")) File(url) else url
}

/**
 * 构建带房间鉴权请求头的 Coil [ImageRequest]，确保房间密码绝不拼接进 URL。
 */
internal fun buildAuthedImageRequest(
    context: Context,
    model: Any,
    headers: Map<String, String>,
): ImageRequest {
    return ImageRequest.Builder(context)
        .data(model)
        .apply {
            headers.forEach { (key, value) ->
                addHeader(key, value)
            }
        }
        .crossfade(true)
        .build()
}

/**
 * 将毫秒时长格式化为 mm:ss。
 */
internal fun formatDurationMs(ms: Long): String {
    val totalSec = ((ms + 500L) / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

/**
 * 格式化保鲜期标签文本。
 */
internal fun formatRetentionBadge(days: Int): String = when {
    days <= 0 -> "阅后即焚"
    else -> "${days}天保留"
}
