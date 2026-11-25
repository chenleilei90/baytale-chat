// BayChat 前端核心数据结构与协议类型定义

export type RoomMode = 'fixed' | 'ephemeral';

export type ThemeMode = 'peach' | 'mint' | 'berry' | 'lime' | 'neon';

export type PropThrowType = 'tomato' | 'flower' | 'freeze' | 'bomb';

export type FunEffectType = 'confetti' | 'hearts' | 'fire' | 'rocket' | 'poke' | PropThrowType;

// 秘密基地成长统计（持久化于固定房间，赋予长期回访成就感）
export interface RoomStats {
  totalMessages: number;
  totalFiles: number;
  totalBytes: number;
  totalPomodoros: number;
  streakDays: number;
  lastActiveDate?: string;
}

// 成员音视频连麦/投屏实时状态
export interface PeerMediaState {
  micOn: boolean;
  micMuted: boolean;
  screenOn: boolean;
}

// 房间同步番茄钟状态
export type PomodoroMode = 'focus25' | 'deep45' | 'break5';

export interface PomodoroState {
  running: boolean;
  mode: PomodoroMode;
  remainingSec: number;
  endAt: number; // 运行中时的目标毫秒时间戳
  startedBy?: string;
  updatedAt: number;
}

// 多人协作白板数据结构（使用 0~1 归一化坐标，跨任意分辨率精准同步）
export type WhiteboardBrushMode = 'neon' | 'rainbow' | 'pen' | 'eraser';
export type WhiteboardBgStyle = 'night' | 'cream';
export type StickyNoteColor = 'yellow' | 'pink' | 'mint' | 'violet';

export interface WhiteboardStroke {
  id: string;
  peerId: string;
  mode: WhiteboardBrushMode;
  color: string;
  size: number;
  points: [number, number][];
}

export interface WhiteboardNote {
  id: string;
  peerId: string;
  authorName: string;
  x: number; // 0..1
  y: number; // 0..1
  text: string;
  color: StickyNoteColor;
  updatedAt: number;
}

export type WhiteboardEvent =
  | { type: 'opened'; actorName: string }
  | { type: 'cursor'; nx: number; ny: number; name: string; color: string }
  | { type: 'stroke-start'; stroke: WhiteboardStroke }
  | { type: 'stroke-append'; strokeId: string; points: [number, number][] }
  | { type: 'stroke-undo'; strokeId: string }
  | { type: 'note-upsert'; note: WhiteboardNote }
  | { type: 'note-delete'; noteId: string }
  | { type: 'bg-change'; bgStyle: WhiteboardBgStyle }
  | { type: 'clear'; actorName: string }
  | { type: 'sync-req' }
  | {
      type: 'sync-res';
      bgStyle: WhiteboardBgStyle;
      strokes: WhiteboardStroke[];
      notes: WhiteboardNote[];
    };

// 本地用户个人资料（免账号，保存在 localStorage）
export interface UserProfile {
  clientId: string;
  name: string;
  avatar: string; // 格式：preset:<id> 或 data:image/... base64
}

// 房间内在线成员资料
export interface PeerProfile {
  peerId: string;
  clientId?: string;
  name: string;
  avatar: string;
  joinedAt: number;
}

// 服务器聊天缓存状态与滑动过期信息
export interface HistoryMeta {
  retentionDays: number; // 0 表示不缓存/立即删除；1~7 表示自最后一次聊天起保留天数
  messageCount: number;
  lastMessageAt?: number;
  expiresAt?: number;
}

// 房间元信息摘要
export interface RoomSummary {
  code: string;
  mode: RoomMode;
  hasPassword: boolean;
  hasOwner?: boolean; // 是否已绑定房主令牌
  ownerToken?: string; // 仅在创建房间时返回一次
  allowRandom?: boolean; // 是否允许陌生人随机进入（仅无密码房有效）
  topic?: string; // 可选房间主题标签
  maxPeers: number;
  peerCount: number;
  retentionDays: number;
  maxFileSizeMB: number; // 单文件附件大小上限（MB），0 表示不限制
  stats?: RoomStats; // 基地成长火花与累计互动统计
  createdAt: number;
  lastActiveAt: number;
  history: HistoryMeta;
}

// 服务端全局配置（含动态 TURN 凭证与附件限制默认值）
export interface ServerConfig {
  domain: string;
  maxRoomPeers: number;
  maxRetentionDays: number;
  historyMaxMediaBytes: number;
  historyMaxFileBytes?: number; // 单个历史附件云端限时缓存上限（字节），默认 100MB
  maxAttachmentMB: number; // 服务端允许的最高硬上限（MB），0 表示不限制
  defaultRoomMaxFileMB: number; // 新建房间默认单附件上限（MB），0 表示不限制
  pickupMaxFileMB?: number; // 取件码单文件上限（MB）
  pickupMaxTotalMB?: number; // 单个取件码总上限（MB）
  hasTurn: boolean;
  iceServers: RTCIceServer[];
}

// 收藏的固定房间项（保存在本地，方便一键再次进入）
export interface SavedFixedRoom {
  code: string;
  label?: string;
  hasPassword: boolean;
  password?: string; // 仅在用户主动勾选记住密码时保存在本机
  ownerToken?: string; // 房主管理令牌
  lastVisitedAt: number;
}

// 最近访问过的房间记录（同时记录临时闪传房与常驻固定房，方便快速重进）
export interface RecentRoom {
  code: string;
  mode: RoomMode;
  hasPassword: boolean;
  password?: string;
  ownerToken?: string; // 房主管理令牌
  lastVisitedAt: number;
}

// 端到端连接路径状态
export type LinkTransportType = 'connecting' | 'p2p-lan' | 'p2p-wan' | 'relay-turn' | 'disconnected';

export interface PeerLinkStatus {
  peerId: string;
  transport: LinkTransportType;
  rttMs?: number;
  dataReady: boolean;
}

// 引用回复摘要
export interface ReplyQuote {
  id: string;
  senderName: string;
  preview: string;
}

// 服务端缓存消息格式
export interface CachedMessage {
  id: string;
  kind: 'text' | 'image' | 'audio' | 'file_notice';
  senderId: string;
  senderName: string;
  senderAvatar?: string;
  text?: string;
  caption?: string;
  mediaId?: string;
  mediaMime?: string;
  fileName?: string;
  fileSize?: number;
  isScreenshot?: boolean;
  replyToId?: string;
  replyToSender?: string;
  replyToPreview?: string;
  timestamp: number;
}

// 文件传输状态
export type TransferStatus =
  | 'offering'
  | 'waiting_accept'
  | 'transferring'
  | 'completed'
  | 'rejected'
  | 'cancelled'
  | 'error';

export interface FileTransferInfo {
  transferId: string;
  streamId: number;
  fileName: string;
  fileSize: number;
  mimeType: string;
  isImage: boolean;
  isAudio?: boolean;
  isScreenshot?: boolean;
  direction: 'send' | 'receive';
  targetPeerId?: string; // 接收方为发送者 peerId；发送方为接收者 peerId
  targetPeerName?: string; // 多人房间展示发往或来自哪位成员
  status: TransferStatus;
  transferredBytes: number;
  speedBps: number;
  etaSeconds: number;
  sha256?: string;
  verified?: boolean;
  objectUrl?: string; // 内存 Blob 下载或图片预览链接
  cachedDownloadUrl?: string; // 服务器限时缓存历史附件直链（刷新/重进后可直接下载）
  isHistoryRecord?: boolean; // 是否恢复自服务器历史附件记录
  savedToDisk?: boolean; // 是否通过 File System Access API 流式直写硬盘
  resumedFromBytes?: number; // 若本次传输从断点续传，记录起始字节偏移
  canResumeBytes?: number; // 本地检测到可续传的已落盘字节数（大于 0 时显示断点续传按钮）
  resumeKey?: string; // 基于文件指纹的断点续传唯一键
  hashScope?: 'full'; // 校验范围标识（full 表示覆盖完整文件）
  errorMessage?: string;
}

// 聊天界面渲染用统一消息项
export interface ChatItem {
  id: string;
  kind: 'text' | 'image' | 'audio' | 'file' | 'system';
  senderId: string;
  senderName: string;
  senderAvatar?: string;
  isSelf: boolean;
  timestamp: number;
  text?: string;
  caption?: string;
  imageUrl?: string;
  audioUrl?: string;
  mediaId?: string;
  isScreenshot?: boolean;
  isDoodle?: boolean;
  isSecret?: boolean;
  isClipSync?: boolean;
  replyTo?: ReplyQuote;
  reactions?: Record<string, string[]>; // emoji -> peerIds
  fromCache?: boolean; // 标识该消息恢复自服务器缓存
  transfer?: FileTransferInfo;
}

// WebSocket 双向信令信封
export interface SignalEnvelope {
  type: string;
  roomCode?: string;
  peerId?: string;
  targetPeerId?: string;
  fromPeerId?: string;
  password?: string;
  ownerToken?: string;
  isOwner?: boolean;
  hasPassword?: boolean;
  profile?: PeerProfile;
  peers?: PeerProfile[];
  room?: RoomSummary;
  payload?: unknown;
  message?: CachedMessage;
  messages?: CachedMessage[];
  messageId?: string;
  history?: HistoryMeta;
  stats?: RoomStats;
  activityKind?: 'message' | 'file' | 'pomodoro';
  activityBytes?: number;
  retentionDays?: number;
  maxFileSizeMB?: number;
  allowRandom?: boolean;
  topic?: string;
  reason?: string;
  errorCode?: string;
  error?: string;
  actorName?: string;
}

// 取件码离线文件元信息
export interface PickupFileMeta {
  fileId: string;
  name: string;
  size: number;
  mime: string;
  ready?: boolean;
}

export interface PickupCreateResponse {
  code: string;
  ownerToken: string;
  expiresAt: number;
  files: PickupFileMeta[];
}

export interface PickupInfo {
  code: string;
  files: PickupFileMeta[];
  expiresAt: number;
  burnAfterRead: boolean;
  downloads: number;
  hasPassword: boolean;
  burnedAt?: number;
}

export interface MyPickupRecord {
  code: string;
  ownerToken: string;
  expiresAt: number;
  fileCount: number;
  totalSize: number;
  burnAfterRead: boolean;
  hasPassword: boolean;
  createdAt: number;
}

// 附近设备与邀请数据结构
export interface NearbyDevice {
  clientId: string;
  name: string;
  avatar: string;
  device: 'android' | 'web';
}

export interface NearbyInviteNotice {
  inviteId: string;
  fromClientId: string;
  fromName: string;
  fromAvatar: string;
  roomCode: string;
  expiresAt: number;
}

export interface NearbyHeartbeatResponse {
  devices: NearbyDevice[];
  invites: NearbyInviteNotice[];
}

export interface NearbyInviteResponse {
  inviteId: string;
  room: RoomSummary;
  ownerToken: string;
}

export interface PendingNearbyInvite {
  inviteId: string;
  targetName: string;
}

// 本地持久化屏蔽成员记录
export interface BlockedPeer {
  clientId: string;
  name: string;
  blockedAt: number;
}
