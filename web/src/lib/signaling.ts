import type {
  CachedMessage,
  HistoryMeta,
  NearbyHeartbeatResponse,
  NearbyInviteResponse,
  PaperPlane,
  PeerProfile,
  PickupCreateResponse,
  PickupInfo,
  PlaneFileMeta,
  PlaneJoinResult,
  RoomMode,
  RoomSummary,
  ServerConfig,
  SignalEnvelope,
} from '../types';

export class ApiError extends Error {
  status: number;
  errorCode: string;

  constructor(status: number, errorCode: string, message: string) {
    super(message);
    this.status = status;
    this.errorCode = errorCode;
  }
}

async function requestJSON<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init);
  let data: Record<string, unknown> = {};
  try {
    data = (await res.json()) as Record<string, unknown>;
  } catch {
    // 忽略非 JSON 响应
  }
  if (!res.ok) {
    throw new ApiError(
      res.status,
      String(data.errorCode || 'REQUEST_FAILED'),
      String(data.error || `请求失败 (${res.status})`)
    );
  }
  return data as T;
}

// 获取服务端配置与动态临时 TURN 凭证
export function fetchServerConfig(clientId: string): Promise<ServerConfig> {
  return requestJSON<ServerConfig>(`/api/config?clientId=${encodeURIComponent(clientId)}`);
}

// 创建房间（固定或一次性）
export function createRoom(params: {
  mode: RoomMode;
  code?: string;
  password?: string;
  allowRandom?: boolean;
  topic?: string;
  maxPeers?: number;
  retentionDays: number;
  maxFileSizeMB?: number;
}): Promise<RoomSummary> {
  return requestJSON<RoomSummary>('/api/rooms', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  });
}

// 获取当前可进入的免密公开房间列表
export function fetchPublicRooms(): Promise<{ rooms: RoomSummary[]; count: number }> {
  return requestJSON<{ rooms: RoomSummary[]; count: number }>('/api/rooms/public');
}

// 随机抽取一间免密公开房间（支持排除当前房间号）
export function pickRandomPublicRoom(excludeCode?: string): Promise<RoomSummary> {
  return requestJSON<RoomSummary>('/api/rooms/random', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ excludeCode: excludeCode || '' }),
  });
}

// 查询房间状态
export function fetchRoomInfo(code: string): Promise<RoomSummary> {
  return requestJSON<RoomSummary>(`/api/rooms/${encodeURIComponent(code)}`);
}

// 校验房间访问密码
export function verifyRoomPassword(code: string, password: string): Promise<RoomSummary> {
  return requestJSON<RoomSummary>(`/api/rooms/${encodeURIComponent(code)}/verify`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  });
}

// 销毁房间
export function deleteRoom(
  code: string,
  password: string,
  actorName: string,
  ownerToken?: string
): Promise<{ deleted: boolean }> {
  const headers: Record<string, string> = {
    'X-Room-Password': password,
    'X-Actor-Name': encodeURIComponent(actorName),
  };
  if (ownerToken) headers['X-Owner-Token'] = ownerToken;
  return requestJSON<{ deleted: boolean }>(`/api/rooms/${encodeURIComponent(code)}`, {
    method: 'DELETE',
    headers,
  });
}

// 修改服务器聊天记录缓存保留天数（0~7天）
export function updateRoomRetention(
  code: string,
  password: string,
  retentionDays: number,
  actorName: string,
  ownerToken?: string
): Promise<{ history: HistoryMeta; cleared: boolean }> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Room-Password': password,
  };
  if (ownerToken) headers['X-Owner-Token'] = ownerToken;
  return requestJSON<{ history: HistoryMeta; cleared: boolean }>(
    `/api/rooms/${encodeURIComponent(code)}/retention`,
    {
      method: 'PUT',
      headers,
      body: JSON.stringify({ retentionDays, password, actorName }),
    }
  );
}

// 房主修改房间密码与主题（password 传 "" 表示移除密码，不传表示不修改）
export function updateRoomProfile(
  code: string,
  opts: {
    ownerToken?: string;
    roomPassword?: string;
    password?: string | null;
    topic?: string;
    actorName?: string;
  }
): Promise<RoomSummary> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  if (opts.ownerToken) headers['X-Owner-Token'] = opts.ownerToken;
  if (opts.roomPassword) headers['X-Room-Password'] = opts.roomPassword;
  if (opts.actorName) headers['X-Actor-Name'] = encodeURIComponent(opts.actorName);
  const body: Record<string, unknown> = {};
  if (opts.password !== undefined) body.password = opts.password;
  if (opts.topic !== undefined) body.topic = opts.topic;
  if (opts.actorName) body.actorName = opts.actorName;
  return requestJSON<RoomSummary>(`/api/rooms/${encodeURIComponent(code)}/profile`, {
    method: 'PUT',
    headers,
    body: JSON.stringify(body),
  });
}

// 立即删除房间全部服务器缓存聊天记录
export function clearRoomHistory(
  code: string,
  password: string,
  actorName: string,
  ownerToken?: string
): Promise<{ cleared: boolean; history: HistoryMeta }> {
  const headers: Record<string, string> = {
    'X-Room-Password': password,
    'X-Actor-Name': encodeURIComponent(actorName),
  };
  if (ownerToken) headers['X-Owner-Token'] = ownerToken;
  return requestJSON<{ cleared: boolean; history: HistoryMeta }>(
    `/api/rooms/${encodeURIComponent(code)}/history`,
    {
      method: 'DELETE',
      headers,
    }
  );
}

// 提交违规成员或消息举报
export async function submitReport(params: {
  roomCode: string;
  reportedName: string;
  reportedClientId: string;
  reason: string;
  messageId?: string;
  reporterClientId: string;
}): Promise<void> {
  const res = await fetch('/api/report', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  });
  if (!res.ok) {
    const data = (await res.json().catch(() => ({}))) as Record<string, unknown>;
    throw new ApiError(res.status, String(data.errorCode || 'REPORT_FAILED'), String(data.error || '举报提交失败'));
  }
}

// ================= 取件码离线投递接口 =================

export function createPickup(params: {
  files: { name: string; size: number; mime: string }[];
  expireHours: 1 | 24 | 72;
  burnAfterRead: boolean;
  password?: string;
}): Promise<PickupCreateResponse> {
  return requestJSON<PickupCreateResponse>('/api/pickups', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  });
}

export async function headPickupFile(
  code: string,
  fileId: string,
  ownerToken: string
): Promise<number> {
  const res = await fetch(
    `/api/pickups/${encodeURIComponent(code)}/files/${encodeURIComponent(fileId)}`,
    {
      method: 'HEAD',
      headers: { 'X-Owner-Token': ownerToken },
    }
  );
  if (!res.ok) return 0;
  const raw = res.headers.get('X-Uploaded-Bytes');
  const parsed = raw ? Number(raw) : 0;
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}

// 使用 XMLHttpRequest 流式上传取件码文件以获取实时上传进度，并支持断点偏移续传
export function uploadPickupFile(
  code: string,
  fileId: string,
  ownerToken: string,
  file: File,
  offset = 0,
  onProgress?: (uploadedBytes: number) => void
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(
      'PUT',
      `/api/pickups/${encodeURIComponent(code)}/files/${encodeURIComponent(fileId)}`,
      true
    );
    xhr.setRequestHeader('X-Owner-Token', ownerToken);
    if (offset > 0) {
      xhr.setRequestHeader('X-Upload-Offset', String(offset));
    }
    xhr.upload.onprogress = (ev) => {
      if (ev.lengthComputable && onProgress) {
        onProgress(Math.min(file.size, offset + ev.loaded));
      }
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        onProgress?.(file.size);
        resolve();
      } else {
        let errCode = 'UPLOAD_FAILED';
        let errMsg = `上传失败 (${xhr.status})`;
        try {
          const data = JSON.parse(xhr.responseText) as Record<string, unknown>;
          if (data.errorCode) errCode = String(data.errorCode);
          if (data.error) errMsg = String(data.error);
        } catch {
          // 忽略解析异常
        }
        reject(new ApiError(xhr.status, errCode, errMsg));
      }
    };
    xhr.onerror = () => reject(new ApiError(0, 'NETWORK_ERROR', '网络连接中断，可重试续传'));
    xhr.onabort = () => reject(new ApiError(0, 'ABORTED', '上传已取消'));
    xhr.send(offset > 0 ? file.slice(offset) : file);
  });
}

export function fetchPickupInfo(code: string, password?: string): Promise<PickupInfo> {
  const headers: Record<string, string> = {};
  if (password) headers['X-Pickup-Password'] = password;
  return requestJSON<PickupInfo>(`/api/pickups/${encodeURIComponent(code)}`, {
    headers,
  });
}

export function buildPickupFileDownloadUrl(
  code: string,
  fileId: string,
  password?: string
): string {
  const base = `/api/pickups/${encodeURIComponent(code)}/files/${encodeURIComponent(fileId)}`;
  if (!password) return base;
  return `${base}?password=${encodeURIComponent(password)}`;
}

export async function deletePickup(code: string, ownerToken: string): Promise<void> {
  const res = await fetch(`/api/pickups/${encodeURIComponent(code)}`, {
    method: 'DELETE',
    headers: { 'X-Owner-Token': ownerToken },
  });
  if (!res.ok) {
    const data = (await res.json().catch(() => ({}))) as Record<string, unknown>;
    throw new ApiError(res.status, String(data.errorCode || 'DELETE_FAILED'), String(data.error || '撤销取件码失败'));
  }
}

// ================= 附近设备（同 IP）发现与邀请接口 =================

export function sendNearbyHeartbeat(params: {
  clientId: string;
  name: string;
  avatar: string;
  device: 'web' | 'android';
  network: 'wifi' | 'cellular' | 'unknown';
}): Promise<NearbyHeartbeatResponse> {
  return requestJSON<NearbyHeartbeatResponse>('/api/nearby/heartbeat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  });
}

export function sendNearbyInvite(params: {
  fromClientId: string;
  toClientId: string;
  fromName: string;
  fromAvatar: string;
}): Promise<NearbyInviteResponse> {
  return requestJSON<NearbyInviteResponse>('/api/nearby/invite', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  });
}

export function respondNearbyInvite(
  inviteId: string,
  params: { clientId: string; accept: boolean }
): Promise<{ status: string; roomCode?: string }> {
  return requestJSON<{ status: string; roomCode?: string }>(
    `/api/nearby/invite/${encodeURIComponent(inviteId)}/respond`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params),
    }
  );
}

export function fetchNearbyInviteStatus(
  inviteId: string
): Promise<{ status: 'pending' | 'accepted' | 'declined' | 'expired' }> {
  return requestJSON<{ status: 'pending' | 'accepted' | 'declined' | 'expired' }>(
    `/api/nearby/invite/${encodeURIComponent(inviteId)}`
  );
}

// ================= 纸飞机公开分享卡片接口 =================

export function fetchPlanes(limit = 24): Promise<{ planes: PaperPlane[] }> {
  return requestJSON<{ planes: PaperPlane[] }>(`/api/planes?limit=${limit}`);
}

export function fetchPlaneDetail(id: string): Promise<PaperPlane> {
  return requestJSON<PaperPlane>(`/api/planes/${encodeURIComponent(id)}`);
}

export function createPlane(params: {
  clientId: string;
  roomCode: string;
  senderName: string;
  senderAvatar: string;
  message: string;
  files: PlaneFileMeta[];
  allowEntry: boolean;
  ownerToken?: string;
  password?: string;
}): Promise<PaperPlane> {
  return requestJSON<PaperPlane>('/api/planes', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  });
}

export function joinPlane(id: string): Promise<PlaneJoinResult> {
  return requestJSON<PlaneJoinResult>(`/api/planes/${encodeURIComponent(id)}/join`, {
    method: 'POST',
  });
}

export function reportPlane(
  id: string,
  clientId: string
): Promise<{ ok: boolean; deleted: boolean }> {
  return requestJSON<{ ok: boolean; deleted: boolean }>(
    `/api/planes/${encodeURIComponent(id)}/report`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ clientId }),
    }
  );
}

// 上传图片/截图到房间服务器缓存（仅当房间开启服务器缓存时调用）
export async function uploadCachedMedia(
  code: string,
  password: string,
  blob: Blob
): Promise<{ mediaId: string; mime: string; size: number }> {
  const res = await fetch(`/api/rooms/${encodeURIComponent(code)}/media`, {
    method: 'POST',
    headers: {
      'Content-Type': blob.type || 'image/webp',
      'X-Room-Password': password,
    },
    body: blob,
  });
  const data = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  if (!res.ok) {
    throw new ApiError(res.status, String(data.errorCode || 'UPLOAD_FAILED'), String(data.error || '缓存图片上传失败'));
  }
  return data as { mediaId: string; mime: string; size: number };
}

// 构建缓存图片访问地址
export function buildCachedMediaUrl(code: string, mediaId: string, password: string): string {
  const base = `/api/rooms/${encodeURIComponent(code)}/media/${encodeURIComponent(mediaId)}`;
  if (!password) return base;
  return `${base}?password=${encodeURIComponent(password)}`;
}

// 上传普通历史附件到房间服务器限时缓存（支持 XHR 实时进度回调，受服务端 historyMaxFileBytes 限制）
export function uploadCachedFile(
  code: string,
  password: string,
  file: Blob,
  fileName: string,
  onProgress?: (uploadedBytes: number) => void
): Promise<{ fileId: string; mediaId: string; fileName: string; mime: string; size: number }> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/api/rooms/${encodeURIComponent(code)}/files`, true);
    xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream');
    xhr.setRequestHeader('X-Room-Password', password);
    xhr.setRequestHeader('X-File-Name', encodeURIComponent(fileName));
    xhr.upload.onprogress = (ev) => {
      if (ev.lengthComputable && onProgress) {
        onProgress(Math.min(file.size, ev.loaded));
      }
    };
    xhr.onload = () => {
      let data: Record<string, unknown> = {};
      try {
        data = JSON.parse(xhr.responseText || '{}') as Record<string, unknown>;
      } catch {
        // 忽略非 JSON 响应
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        onProgress?.(file.size);
        resolve(data as { fileId: string; mediaId: string; fileName: string; mime: string; size: number });
      } else {
        reject(
          new ApiError(
            xhr.status,
            String(data.errorCode || 'UPLOAD_FAILED'),
            String(data.error || '历史附件缓存上传失败')
          )
        );
      }
    };
    xhr.onerror = () => reject(new ApiError(0, 'UPLOAD_FAILED', '历史附件缓存上传网络中断'));
    xhr.onabort = () => reject(new ApiError(0, 'ABORTED', '历史附件上传已取消'));
    xhr.send(file);
  });
}

// 调整房间通用设置（允许随机进入等，对应 PUT /api/rooms/{code}/settings）
export function updateRoomSettingsApi(
  code: string,
  opts: {
    ownerToken?: string;
    password?: string;
    allowRandom?: boolean;
    maxFileSizeMB?: number;
    actorName?: string;
  }
): Promise<{ allowRandom?: boolean; maxFileSizeMB?: number }> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  if (opts.ownerToken) headers['X-Owner-Token'] = opts.ownerToken;
  if (opts.password) headers['X-Room-Password'] = opts.password;
  if (opts.actorName) headers['X-Actor-Name'] = encodeURIComponent(opts.actorName);
  const body: Record<string, unknown> = {};
  if (opts.allowRandom !== undefined) body.allowRandom = opts.allowRandom;
  if (opts.maxFileSizeMB !== undefined) body.maxFileSizeMB = opts.maxFileSizeMB;
  if (opts.actorName) body.actorName = opts.actorName;
  return requestJSON<{ allowRandom?: boolean; maxFileSizeMB?: number }>(
    `/api/rooms/${encodeURIComponent(code)}/settings`,
    {
      method: 'PUT',
      headers,
      body: JSON.stringify(body),
    }
  );
}

// 构建历史附件限时缓存下载地址
export function buildCachedFileUrl(code: string, fileId: string, password: string): string {
  const base = `/api/rooms/${encodeURIComponent(code)}/files/${encodeURIComponent(fileId)}`;
  if (!password) return base;
  return `${base}?password=${encodeURIComponent(password)}`;
}

// 从用户输入中提取房间码（支持完整邀请链接 ?room=849205 或短链 /r/849205）
export function extractRoomCodeInput(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) return '';
  try {
    if (trimmed.includes('://') || trimmed.includes('?room=') || trimmed.includes('/r/')) {
      const url = new URL(
        trimmed.startsWith('http')
          ? trimmed
          : trimmed.startsWith('/')
            ? `https://chat.baytale.com${trimmed}`
            : `https://${trimmed}`
      );
      const q = url.searchParams.get('room');
      if (q) return q.trim().toLowerCase();
      const match = url.pathname.match(/^\/r\/([a-zA-Z0-9_-]{3,24})\/?$/);
      if (match) return match[1].toLowerCase();
    }
  } catch {
    // 非 URL 按普通房间码处理
  }
  return trimmed.toLowerCase();
}

export interface SignalingHandlers {
  onJoined: (env: SignalEnvelope) => void;
  onPeerJoined: (profile: PeerProfile) => void;
  onPeerUpdated: (profile: PeerProfile) => void;
  onPeerLeft: (peerId: string) => void;
  onSignal: (env: SignalEnvelope) => void;
  onMessageCached: (env: SignalEnvelope) => void;
  onMessageRecalled: (env: SignalEnvelope) => void;
  onRetentionUpdated: (env: SignalEnvelope) => void;
  onRoomSettingsUpdated: (env: SignalEnvelope) => void;
  onRoomStatsUpdated: (env: SignalEnvelope) => void;
  onHistoryCleared: (env: SignalEnvelope) => void;
  onRoomDestroyed: (reason: string) => void;
  onError: (code: string, message: string) => void;
  onConnectionState: (connected: boolean) => void;
}

// WebSocket 信令客户端，负责房间保活、自动重连与信令转发
export class SignalingClient {
  private ws: WebSocket | null = null;
  private heartbeatTimer: number | null = null;
  private reconnectTimer: number | null = null;
  private reconnectAttempts = 0;
  private closedManually = false;

  private roomCode: string;
  private password: string;
  private ownerToken?: string;
  private profile: PeerProfile;
  private handlers: SignalingHandlers;

  constructor(
    roomCode: string,
    password: string,
    profile: PeerProfile,
    handlers: SignalingHandlers,
    ownerToken?: string
  ) {
    this.roomCode = roomCode;
    this.password = password;
    this.profile = profile;
    this.handlers = handlers;
    this.ownerToken = ownerToken;
  }

  connect(): void {
    this.closedManually = false;
    this.reconnectAttempts = 0;
    this.openSocket();
  }

  private openSocket(): void {
    if (this.closedManually) return;
    this.cleanupSocketOnly();

    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${proto}//${window.location.host}/ws`;
    const ws = new WebSocket(wsUrl);
    this.ws = ws;

    ws.onopen = () => {
      if (this.ws !== ws) return;
      this.reconnectAttempts = 0;
      this.handlers.onConnectionState(true);
      this.send({
        type: 'join',
        roomCode: this.roomCode,
        peerId: this.profile.peerId,
        password: this.password,
        ownerToken: this.ownerToken || undefined,
        profile: this.profile,
      });
      this.startHeartbeat();
    };

    ws.onmessage = (event) => {
      if (this.ws !== ws) return;
      try {
        const env = JSON.parse(String(event.data)) as SignalEnvelope;
        switch (env.type) {
          case 'joined':
            this.handlers.onJoined(env);
            break;
          case 'peer_joined':
            if (env.profile) this.handlers.onPeerJoined(env.profile);
            break;
          case 'peer_updated':
            if (env.profile) this.handlers.onPeerUpdated(env.profile);
            break;
          case 'peer_left':
            if (env.peerId) this.handlers.onPeerLeft(env.peerId);
            break;
          case 'offer':
          case 'answer':
          case 'ice-candidate':
            this.handlers.onSignal(env);
            break;
          case 'message_cached':
            this.handlers.onMessageCached(env);
            break;
          case 'message_recalled':
            this.handlers.onMessageRecalled(env);
            break;
          case 'retention_updated':
            this.handlers.onRetentionUpdated(env);
            break;
          case 'room_settings_updated':
            this.handlers.onRoomSettingsUpdated(env);
            break;
          case 'room_stats_updated':
            this.handlers.onRoomStatsUpdated(env);
            break;
          case 'history_cleared':
            this.handlers.onHistoryCleared(env);
            break;
          case 'room_destroyed':
            this.closedManually = true;
            this.handlers.onRoomDestroyed(env.reason || '房间已销毁');
            break;
          case 'error':
            // 鉴权/房间不存在等致命错误不再自动重连
            if (
              env.errorCode === 'ROOM_NOT_FOUND' ||
              env.errorCode === 'ROOM_EXPIRED' ||
              env.errorCode === 'WRONG_PASSWORD' ||
              env.errorCode === 'ROOM_FULL' ||
              env.errorCode === 'INVALID_JOIN'
            ) {
              this.closedManually = true;
            }
            this.handlers.onError(env.errorCode || 'ERROR', env.error || '连接失败');
            break;
          case 'pong':
          default:
            break;
        }
      } catch {
        // 忽略非法信令帧
      }
    };

    ws.onclose = () => {
      if (this.ws !== ws) return;
      this.stopHeartbeat();
      this.handlers.onConnectionState(false);
      if (!this.closedManually) {
        // 指数退避自动重连（1s -> 2s -> 4s -> 8s，上限 10s）
        const delayMs = Math.min(10000, 1000 * Math.pow(2, this.reconnectAttempts));
        this.reconnectAttempts = Math.min(this.reconnectAttempts + 1, 5);
        this.reconnectTimer = window.setTimeout(() => {
          if (!this.closedManually) {
            this.openSocket();
          }
        }, delayMs);
      }
    };

    ws.onerror = () => {
      // onclose 会接管退避重连
    };
  }

  send(env: SignalEnvelope): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(env));
    }
  }

  updateProfile(profile: PeerProfile): void {
    this.profile = profile;
    this.send({ type: 'profile_update', profile });
  }

  // 房主在线改密后原位刷新信令认证密码，避免触发 WS 断线清屏
  updatePassword(nextPassword: string): void {
    this.password = nextPassword;
  }

  cacheMessage(message: CachedMessage): void {
    this.send({ type: 'cache_message', message });
  }

  updateRetention(days: number): void {
    this.send({ type: 'update_retention', retentionDays: days });
  }

  updateMaxFileSize(maxFileSizeMB: number): void {
    this.send({ type: 'update_max_file_size', maxFileSizeMB });
  }

  recallMessage(messageId: string): void {
    this.send({ type: 'recall_message', messageId });
  }

  recordActivity(kind: 'message' | 'file' | 'pomodoro', bytes = 0): void {
    this.send({ type: 'record_activity', activityKind: kind, activityBytes: bytes });
  }

  clearHistory(): void {
    this.send({ type: 'clear_history' });
  }

  leaveRoom(): void {
    this.closedManually = true;
    this.send({ type: 'leave_room' });
    this.cleanup();
  }

  destroyRoom(): void {
    this.closedManually = true;
    this.send({ type: 'destroy_room' });
    this.cleanup();
  }

  disconnect(): void {
    this.closedManually = true;
    this.cleanup();
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatTimer = window.setInterval(() => {
      this.send({ type: 'ping' });
    }, 18000);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer !== null) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private cleanupSocketOnly(): void {
    if (this.ws) {
      const old = this.ws;
      this.ws = null;
      old.onopen = null;
      old.onmessage = null;
      old.onclose = null;
      old.onerror = null;
      try {
        old.close();
      } catch {
        // 忽略关闭异常
      }
    }
  }

  private cleanup(): void {
    this.stopHeartbeat();
    this.cleanupSocketOnly();
  }
}
