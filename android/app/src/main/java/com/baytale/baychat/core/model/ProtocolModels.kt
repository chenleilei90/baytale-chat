package com.baytale.baychat.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ============================================================================
// BayChat Android 协议数据模型（与 Go 服务端和 Web 前端 100% 字节级兼容）
// ============================================================================

enum class RoomMode(val wireValue: String) {
    EPHEMERAL("ephemeral"),
    FIXED("fixed");

    companion object {
        fun fromWire(raw: String?): RoomMode =
            if (raw.equals("fixed", ignoreCase = true)) FIXED else EPHEMERAL
    }
}

enum class AppThemeMode(val id: String, val label: String) {
    PEACH("peach", "蜜桃粉"),
    MINT("mint", "薄荷蓝"),
    BERRY("berry", "莓果紫"),
    LIME("lime", "青柠绿"),
    NEON("neon", "暗夜霓虹");

    companion object {
        fun fromId(raw: String?): AppThemeMode =
            entries.firstOrNull { it.id == raw } ?: PEACH
    }
}

@Serializable
data class IceServerConfig(
    val urls: JsonElement,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class ServerConfig(
    val domain: String = "chat.baytale.com",
    val maxRoomPeers: Int = 6,
    val maxRetentionDays: Int = 7,
    val historyMaxMediaBytes: Long = 10L * 1024 * 1024,
    val maxAttachmentMB: Int = 0,
    val defaultRoomMaxFileMB: Int = 500,
    val hasTurn: Boolean = false,
    val iceServers: List<IceServerConfig> = emptyList(),
)

@Serializable
data class RoomStats(
    val totalMessages: Int = 0,
    val totalFiles: Int = 0,
    val totalBytes: Long = 0,
    val totalPomodoros: Int = 0,
    val streakDays: Int = 1,
    val lastActiveDate: String? = null,
)

@Serializable
data class HistoryMeta(
    val retentionDays: Int = 1,
    val messageCount: Int = 0,
    val lastMessageAt: Long? = null,
    val expiresAt: Long? = null,
)

@Serializable
data class RoomSummary(
    val code: String,
    val mode: String = "ephemeral",
    val hasPassword: Boolean = false,
    val maxPeers: Int = 6,
    val peerCount: Int = 0,
    val retentionDays: Int = 1,
    val maxFileSizeMB: Int = 500,
    val stats: RoomStats? = null,
    val createdAt: Long = 0,
    val lastActiveAt: Long = 0,
    val history: HistoryMeta = HistoryMeta(),
)

@Serializable
data class CreateRoomRequest(
    val mode: String,
    val code: String? = null,
    val password: String? = null,
    val maxPeers: Int? = null,
    val retentionDays: Int = 1,
    val maxFileSizeMB: Int? = null,
)

@Serializable
data class VerifyPasswordRequest(
    val password: String,
)

@Serializable
data class MediaUploadResponse(
    val mediaId: String,
    val mime: String,
    val size: Long,
)

@Serializable
data class UserProfile(
    val clientId: String,
    val name: String,
    val avatar: String, // 例如 preset:cat
)

@Serializable
data class PeerProfile(
    val peerId: String,
    val name: String,
    val avatar: String = "preset:cat",
    val joinedAt: Long = 0,
)

@Serializable
data class SavedFixedRoom(
    val code: String,
    val hasPassword: Boolean = false,
    val password: String = "",
    val lastVisitedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class RecentRoom(
    val code: String,
    val mode: String = "ephemeral", // ephemeral | fixed
    val hasPassword: Boolean = false,
    val password: String = "",
    val lastVisitedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class ReplyQuote(
    val id: String,
    val senderName: String,
    val preview: String,
)

@Serializable
data class CachedMessage(
    val id: String,
    val kind: String, // text | image | audio | file_notice
    val senderId: String,
    val senderName: String,
    val senderAvatar: String? = null,
    val text: String? = null,
    val caption: String? = null,
    val mediaId: String? = null,
    val mediaMime: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val isScreenshot: Boolean? = null,
    val replyToId: String? = null,
    val replyToSender: String? = null,
    val replyToPreview: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class SignalEnvelope(
    val type: String,
    val roomCode: String? = null,
    val peerId: String? = null,
    val targetPeerId: String? = null,
    val fromPeerId: String? = null,
    val password: String? = null,
    val profile: PeerProfile? = null,
    val peers: List<PeerProfile>? = null,
    val room: RoomSummary? = null,
    val payload: JsonElement? = null,
    val message: CachedMessage? = null,
    val messages: List<CachedMessage>? = null,
    val messageId: String? = null,
    val history: HistoryMeta? = null,
    val stats: RoomStats? = null,
    val activityKind: String? = null,
    val activityBytes: Long? = null,
    val retentionDays: Int? = null,
    val maxFileSizeMB: Int? = null,
    val reason: String? = null,
    val errorCode: String? = null,
    val error: String? = null,
    val actorName: String? = null,
)

@Serializable
data class SdpPayload(
    val type: String,
    val sdp: String,
)

@Serializable
data class IceCandidatePayload(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0,
)

enum class LinkTransportType {
    CONNECTING,
    P2P_LAN,
    P2P_WAN,
    RELAY_TURN,
    DISCONNECTED,
}

data class PeerLinkStatus(
    val peerId: String,
    val transport: LinkTransportType = LinkTransportType.CONNECTING,
    val rttMs: Int? = null,
    val dataReady: Boolean = false,
)

enum class TransferDirection { SEND, RECEIVE }

enum class TransferStatus {
    OFFERING,
    WAITING_ACCEPT,
    TRANSFERRING,
    COMPLETED,
    REJECTED,
    CANCELLED,
    ERROR,
}

data class FileTransferInfo(
    val transferId: String,
    val streamId: Int,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val isImage: Boolean,
    val isAudio: Boolean = false,
    val isScreenshot: Boolean = false,
    val direction: TransferDirection,
    val targetPeerId: String? = null,
    val targetPeerName: String? = null,
    val status: TransferStatus,
    val transferredBytes: Long = 0,
    val speedBps: Double = 0.0,
    val etaSeconds: Double = 0.0,
    val sha256: String? = null,
    val verified: Boolean = false,
    val localPath: String? = null,
    val savedDisplayPath: String? = null,
    val resumedFromBytes: Long = 0L,
    val canResumeBytes: Long = 0L,
    val errorMessage: String? = null,
)

enum class ChatKind { TEXT, IMAGE, AUDIO, FILE, SYSTEM }

data class ChatItem(
    val id: String,
    val kind: ChatKind,
    val senderId: String,
    val senderName: String,
    val senderAvatar: String? = null,
    val isSelf: Boolean,
    val timestamp: Long,
    val text: String? = null,
    val caption: String? = null,
    val imageUrl: String? = null,
    val localFilePath: String? = null,
    val mediaId: String? = null,
    val replyTo: ReplyQuote? = null,
    val reactions: Map<String, List<String>> = emptyMap(),
    val fromCache: Boolean = false,
    val transfer: FileTransferInfo? = null,
)
