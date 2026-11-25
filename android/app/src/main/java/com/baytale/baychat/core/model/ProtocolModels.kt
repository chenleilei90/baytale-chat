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
    AIR("air", "清风极简"),
    PEACH("peach", "蜜桃暖屿"),
    MINT("mint", "薄荷蓝"),
    BERRY("berry", "莓果紫"),
    LIME("lime", "青柠绿"),
    NEON("neon", "暗夜光束");

    companion object {
        fun fromId(raw: String?): AppThemeMode =
            entries.firstOrNull { it.id == raw } ?: AIR
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
    val historyMaxFileBytes: Long = 100L * 1024 * 1024,
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
    val allowRandom: Boolean = false,
    val topic: String = "",
    val maxPeers: Int = 6,
    val peerCount: Int = 0,
    val retentionDays: Int = 1,
    val maxFileSizeMB: Int = 500,
    val stats: RoomStats? = null,
    val createdAt: Long = 0,
    val lastActiveAt: Long = 0,
    val history: HistoryMeta = HistoryMeta(),
    // 房主令牌与房主身份标识（仅创房时由服务端下发）
    val ownerToken: String? = null,
    val isOwner: Boolean = false,
)

@Serializable
data class CreateRoomRequest(
    val mode: String,
    val code: String? = null,
    val password: String? = null,
    val allowRandom: Boolean? = null,
    val topic: String? = null,
    val maxPeers: Int? = null,
    val retentionDays: Int = 1,
    val maxFileSizeMB: Int? = null,
)

@Serializable
data class PublicRoomsResponse(
    val rooms: List<RoomSummary> = emptyList(),
    val count: Int = 0,
)

@Serializable
data class RandomRoomRequest(
    val excludeCode: String = "",
)

@Serializable
data class AppReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val releaseNotes: String = "",
    // 企业级 OTA 扩展字段（均带默认值，完全向后兼容旧版 version.json）
    val fallbackUrl: String = "",
    val minSupportedCode: Int = 0,
    val forceUpdate: Boolean = false,
)

@Serializable
data class TransferRecord(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val direction: String, // send | receive
    val roomCode: String,
    val peerName: String = "",
    val savedDisplayPath: String? = null,
    val localPath: String? = null,
    val contentUri: String? = null,
    val verified: Boolean = false,
    val recipientsDone: Int = 1,
    val recipientsTotal: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class PartialFileMeta(
    val partFileName: String,
    val fileName: String,
    val fileSize: Long,
    val roomCode: String,
    val peerName: String = "",
    val resumeKey: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

data class PartialResumeItem(
    val partFileName: String,
    val fileName: String,
    val savedBytes: Long,
    val totalBytes: Long,
    val roomCode: String,
    val peerName: String,
    val lastModified: Long,
    val resumeKey: String = "",
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
data class FileUploadResponse(
    val fileId: String,
    val mediaId: String = "",
    val fileName: String = "",
    val mime: String = "application/octet-stream",
    val size: Long = 0L,
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
    val clientId: String? = null,
)

@Serializable
data class SavedFixedRoom(
    val code: String,
    val hasPassword: Boolean = false,
    val password: String = "",
    val lastVisitedAt: Long = System.currentTimeMillis(),
    val ownerToken: String = "",
    val alias: String = "",
)

@Serializable
data class RecentRoom(
    val code: String,
    val mode: String = "ephemeral", // ephemeral | fixed
    val hasPassword: Boolean = false,
    val password: String = "",
    val lastVisitedAt: Long = System.currentTimeMillis(),
    val ownerToken: String = "",
)

// 持久化已屏蔽成员信息（优先匹配 clientId，兜底匹配名称）
@Serializable
data class BlockedPeer(
    val clientId: String,
    val name: String,
    val blockedAt: Long = System.currentTimeMillis(),
)

// 进程被杀后用于 10 分钟内快速恢复的最近活跃房间快照
@Serializable
data class LastActiveRoom(
    val code: String,
    val password: String = "",
    val ownerToken: String = "",
    val at: Long = System.currentTimeMillis(),
)

// 本地已发出的离线取件码记录
@Serializable
data class SavedPickupRecord(
    val code: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String = "application/octet-stream",
    val manageToken: String = "",
    val fileId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = 0L,
    val burnAfterRead: Boolean = false,
    val downloadCount: Int = 0,
)

// 取件码上传/查询响应与元信息模型（与 Go server/internal/pickup/pickup.go 100% 字节对齐）
@Serializable
data class PickupCreateFileInput(
    val name: String,
    val size: Long,
    val mime: String = "application/octet-stream",
)

@Serializable
data class PickupCreateInitRequest(
    val files: List<PickupCreateFileInput>,
    val expireHours: Int = 24, // 1 | 24 | 72
    val burnAfterRead: Boolean = false,
    val password: String? = null,
)

@Serializable
data class PickupFileInfo(
    val fileId: String,
    val name: String,
    val size: Long,
    val mime: String = "application/octet-stream",
    val ready: Boolean = false,
)

@Serializable
data class PickupCreateResponse(
    val code: String,
    val ownerToken: String = "",
    val expiresAt: Long = 0L,
    val files: List<PickupFileInfo> = emptyList(),
)

@Serializable
data class PickupInfo(
    val code: String,
    val files: List<PickupFileInfo> = emptyList(),
    val expiresAt: Long = 0L,
    val burnAfterRead: Boolean = false,
    val downloads: Int = 0,
    val hasPassword: Boolean = false,
    val burnedAt: Long = 0L,
) {
    val primaryFile: PickupFileInfo? get() = files.firstOrNull()
    val fileName: String get() = primaryFile?.name.orEmpty()
    val fileSize: Long get() = files.sumOf { it.size }
    val mimeType: String get() = primaryFile?.mime ?: "application/octet-stream"
}

data class PickupTransferProgress(
    val active: Boolean = false,
    val code: String = "",
    val fileName: String = "",
    val transferredBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBps: Double = 0.0,
    val isUpload: Boolean = true,
    val errorMessage: String? = null,
)

// 附近设备发现与邀请协议模型（与 Go server/internal/nearby/nearby.go 100% 对齐）
@Serializable
data class NearbyPeer(
    val clientId: String,
    val name: String,
    val avatar: String = "preset:cat",
    val device: String = "android",
) {
    val deviceType: String get() = device
}

@Serializable
data class NearbyInvite(
    val inviteId: String,
    val fromClientId: String,
    val fromName: String,
    val fromAvatar: String = "preset:cat",
    val roomCode: String,
    val expiresAt: Long = 0L,
)

@Serializable
data class NearbyHeartbeatRequest(
    val clientId: String,
    val name: String,
    val avatar: String,
    val device: String = "android",
    val network: String = "wifi", // wifi | cellular | unknown
)

@Serializable
data class NearbyHeartbeatResponse(
    val devices: List<NearbyPeer> = emptyList(),
    val invites: List<NearbyInvite> = emptyList(),
)

@Serializable
data class NearbyInviteRequest(
    val fromClientId: String,
    val toClientId: String,
    val fromName: String,
    val fromAvatar: String,
)

@Serializable
data class NearbyInviteResponse(
    val inviteId: String,
    val room: RoomSummary? = null,
    val ownerToken: String = "",
)

@Serializable
data class NearbyRespondRequest(
    val clientId: String,
    val accept: Boolean,
)

@Serializable
data class NearbyRespondResponse(
    val status: String,
    val roomCode: String = "",
)

// 举报接口请求体（与 Go server/internal/room/report.go 对齐）
@Serializable
data class ReportRequest(
    val roomCode: String,
    val reportedName: String,
    val reportedClientId: String = "",
    val reason: String = "违规内容",
    val messageId: String? = null,
    val reporterClientId: String = "",
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
    val ownerToken: String? = null,
    val isOwner: Boolean? = null,
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
    val allowRandom: Boolean? = null,
    val topic: String? = null,
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

// 云端附件备份上传状态
enum class CloudUploadStatus {
    NONE,
    UPLOADING,
    DONE,
    FAILED,
}

// 文本/聊天消息发送状态
enum class MessageSendStatus {
    SENDING,
    SENT,
    FAILED,
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
    val contentUri: String? = null,
    val savedDisplayPath: String? = null,
    val cachedDownloadUrl: String? = null,
    val isHistoryRecord: Boolean = false,
    val resumedFromBytes: Long = 0L,
    val canResumeBytes: Long = 0L,
    val errorMessage: String? = null,
    val resumeKey: String? = null,
    val cloudStatus: CloudUploadStatus = CloudUploadStatus.NONE,
    val cloudError: String? = null,
    val recipientsDone: Int = 0,
    val recipientsTotal: Int = 1,
)

// 消息种类定义：保持与既有 UI when 分支兼容，同时提供 CLIP 剪贴板同步类型
class ChatKind private constructor(val name: String) {
    override fun equals(other: Any?): Boolean = other is ChatKind && other.name == name
    override fun hashCode(): Int = name.hashCode()
    override fun toString(): String = name

    companion object {
        val TEXT = ChatKind("TEXT")
        val IMAGE = ChatKind("IMAGE")
        val AUDIO = ChatKind("AUDIO")
        val FILE = ChatKind("FILE")
        val SYSTEM = ChatKind("SYSTEM")
        val CLIP = ChatKind("CLIP")

        val entries: List<ChatKind> = listOf(TEXT, IMAGE, AUDIO, FILE, SYSTEM, CLIP)
    }
}

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
    val sendStatus: MessageSendStatus = MessageSendStatus.SENT,
    val transfer: FileTransferInfo? = null,
    // 自己撤回标记与可重新编辑的纯文本内容（仅当前会话内存）
    val isSelfRecall: Boolean = false,
    val recalledText: String? = null,
    // 文本消息是否 @ 提及了当前用户
    val mentionedMe: Boolean = false,
)
