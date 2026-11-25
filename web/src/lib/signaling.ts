import type {
  CachedMessage,
  HistoryMeta,
  PeerProfile,
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
export function deleteRoom(code: string, password: string, actorName: string): Promise<{ deleted: boolean }> {
  return requestJSON<{ deleted: boolean }>(`/api/rooms/${encodeURIComponent(code)}`, {
    method: 'DELETE',
    headers: {
      'X-Room-Password': password,
      'X-Actor-Name': encodeURIComponent(actorName),
    },
  });
}

// 修改服务器聊天记录缓存保留天数（0~7天）
export function updateRoomRetention(
  code: string,
  password: string,
  retentionDays: number,
  actorName: string
): Promise<{ history: HistoryMeta; cleared: boolean }> {
  return requestJSON<{ history: HistoryMeta; cleared: boolean }>(
    `/api/rooms/${encodeURIComponent(code)}/retention`,
    {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'X-Room-Password': password,
      },
      body: JSON.stringify({ retentionDays, password, actorName }),
    }
  );
}

// 立即删除房间全部服务器缓存聊天记录
export function clearRoomHistory(
  code: string,
  password: string,
  actorName: string
): Promise<{ cleared: boolean; history: HistoryMeta }> {
  return requestJSON<{ cleared: boolean; history: HistoryMeta }>(
    `/api/rooms/${encodeURIComponent(code)}/history`,
    {
      method: 'DELETE',
      headers: {
        'X-Room-Password': password,
        'X-Actor-Name': encodeURIComponent(actorName),
      },
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
  private profile: PeerProfile;
  private handlers: SignalingHandlers;

  constructor(
    roomCode: string,
    password: string,
    profile: PeerProfile,
    handlers: SignalingHandlers
  ) {
    this.roomCode = roomCode;
    this.password = password;
    this.profile = profile;
    this.handlers = handlers;
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
