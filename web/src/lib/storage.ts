import type {
  AppearanceMode,
  BlockedPeer,
  MyPickupRecord,
  RecentRoom,
  SavedFixedRoom,
  ThemeMode,
  UserProfile,
} from '../types';
import { generateRandomName, pickRandomPresetAvatar } from './avatar';

const KEY_PROFILE = 'baychat_profile_v1';
const KEY_THEME = 'baychat_theme_v1';
const KEY_APPEARANCE = 'baychat_appearance_v1';
const KEY_SAVED_ROOMS = 'baychat_fixed_rooms_v1';
const KEY_RECENT_ROOMS = 'baychat_recent_rooms_v1';
const KEY_PREFS = 'baychat_prefs_v1';
const KEY_TAB_PEER_ID = 'baychat_tab_peer_id_v1';
const KEY_BLOCKED_PEERS = 'baychat_blocked_peers_v1';
const KEY_MY_PICKUPS = 'baychat_my_pickups_v1';

// 为每个浏览器标签页分配独立 peerId（存在 sessionStorage），
// 既保证 F5 刷新后身份连续，又彻底解决同一电脑开两个标签页进同房间互相顶号重连的 Bug！
export function getTabPeerId(): string {
  try {
    const existing = sessionStorage.getItem(KEY_TAB_PEER_ID);
    if (existing) return existing;
    const fresh = randomClientId();
    sessionStorage.setItem(KEY_TAB_PEER_ID, fresh);
    return fresh;
  } catch {
    return randomClientId();
  }
}

export interface UserPrefs {
  autoAcceptSmallFiles: boolean; // 是否开启小文件免确认自动接收
  autoAcceptMaxMB: number; // 自动接收阈值（MB），可按需调整（默认 50MB）
  imageSendMode: 'original' | 'compressed'; // 图片发送画质：原图无损 或 智能轻巧压缩
  soundEnabled: boolean; // 新消息提示音
  nearbyDiscoverable: boolean; // 大厅是否允许附近同 Wi-Fi 设备发现自己
}

const DEFAULT_PREFS: UserPrefs = {
  autoAcceptSmallFiles: true,
  autoAcceptMaxMB: 50,
  imageSendMode: 'original',
  soundEnabled: true,
  nearbyDiscoverable: true,
};

function randomClientId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return 'u_' + crypto.randomUUID().replace(/-/g, '').slice(0, 16);
  }
  return 'u_' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

// 读取或初始化本地自定义姓名与头像配置
export function loadUserProfile(): UserProfile {
  try {
    const raw = localStorage.getItem(KEY_PROFILE);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<UserProfile>;
      if (parsed.clientId && parsed.name) {
        return {
          clientId: parsed.clientId,
          name: parsed.name,
          avatar: parsed.avatar || pickRandomPresetAvatar(),
        };
      }
    }
  } catch {
    // 忽略损坏缓存并重新初始化
  }

  const fresh: UserProfile = {
    clientId: randomClientId(),
    name: generateRandomName(),
    avatar: pickRandomPresetAvatar(),
  };
  saveUserProfile(fresh);
  return fresh;
}

export function saveUserProfile(profile: UserProfile): void {
  try {
    localStorage.setItem(KEY_PROFILE, JSON.stringify(profile));
  } catch {
    // 存储空间不足时静默降级
  }
}

const VALID_THEMES: ThemeMode[] = ['air', 'mint', 'berry', 'peach', 'lime'];
const VALID_APPEARANCES: AppearanceMode[] = ['system', 'light', 'dark'];

// 读取强调色主题：默认 'air'；旧 'neon' 自动迁移为 'air' 且若未保存外观偏好则迁移外观为 'dark'
export function loadTheme(): ThemeMode {
  try {
    const saved = localStorage.getItem(KEY_THEME);
    if (saved === 'neon') {
      saveTheme('air');
      if (localStorage.getItem(KEY_APPEARANCE) === null) {
        saveAppearance('dark');
      }
      return 'air';
    }
    if (saved && VALID_THEMES.includes(saved as ThemeMode)) {
      return saved as ThemeMode;
    }
  } catch {
    // 忽略异常
  }
  return 'air';
}

export function saveTheme(theme: ThemeMode): void {
  try {
    localStorage.setItem(KEY_THEME, theme);
  } catch {
    // 忽略异常
  }
}

// 读取外观模式偏好：默认 'system'；兼容首次从旧版 'neon' 迁移而来的深色偏好
export function loadAppearance(): AppearanceMode {
  try {
    const saved = localStorage.getItem(KEY_APPEARANCE);
    if (saved && VALID_APPEARANCES.includes(saved as AppearanceMode)) {
      return saved as AppearanceMode;
    }
    if (saved === null && localStorage.getItem(KEY_THEME) === 'neon') {
      saveAppearance('dark');
      return 'dark';
    }
  } catch {
    // 忽略异常
  }
  return 'system';
}

export function saveAppearance(mode: AppearanceMode): void {
  try {
    localStorage.setItem(KEY_APPEARANCE, mode);
  } catch {
    // 忽略异常
  }
}

export function loadUserPrefs(): UserPrefs {
  try {
    const raw = localStorage.getItem(KEY_PREFS);
    if (raw) {
      return { ...DEFAULT_PREFS, ...(JSON.parse(raw) as Partial<UserPrefs>) };
    }
  } catch {
    // 忽略异常
  }
  return { ...DEFAULT_PREFS };
}

export function saveUserPrefs(prefs: UserPrefs): void {
  try {
    localStorage.setItem(KEY_PREFS, JSON.stringify(prefs));
  } catch {
    // 忽略异常
  }
}

// 读取“我的常用固定房间”列表
export function loadSavedFixedRooms(): SavedFixedRoom[] {
  try {
    const raw = localStorage.getItem(KEY_SAVED_ROOMS);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return (parsed as SavedFixedRoom[]).sort((a, b) => b.lastVisitedAt - a.lastVisitedAt);
  } catch {
    return [];
  }
}

export function upsertSavedFixedRoom(room: SavedFixedRoom): SavedFixedRoom[] {
  const list = loadSavedFixedRooms().filter((r) => r.code !== room.code);
  const next = [room, ...list].slice(0, 20);
  try {
    localStorage.setItem(KEY_SAVED_ROOMS, JSON.stringify(next));
  } catch {
    // 忽略异常
  }
  return next;
}

export function removeSavedFixedRoom(code: string): SavedFixedRoom[] {
  const next = loadSavedFixedRooms().filter((r) => r.code !== code);
  try {
    localStorage.setItem(KEY_SAVED_ROOMS, JSON.stringify(next));
  } catch {
    // 忽略异常
  }
  return next;
}

// 读取“最近进入的房间”列表（按访问时间倒序，最多保留 10 条）
export function loadRecentRooms(): RecentRoom[] {
  try {
    const raw = localStorage.getItem(KEY_RECENT_ROOMS);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return (parsed as RecentRoom[]).sort((a, b) => b.lastVisitedAt - a.lastVisitedAt);
  } catch {
    return [];
  }
}

export function upsertRecentRoom(room: RecentRoom): RecentRoom[] {
  const list = loadRecentRooms().filter((r) => r.code !== room.code);
  const next = [room, ...list].slice(0, 10);
  try {
    localStorage.setItem(KEY_RECENT_ROOMS, JSON.stringify(next));
  } catch {
    // 忽略异常
  }
  return next;
}

export function removeRecentRoom(code: string): RecentRoom[] {
  const next = loadRecentRooms().filter((r) => r.code !== code);
  try {
    localStorage.setItem(KEY_RECENT_ROOMS, JSON.stringify(next));
  } catch {
    // 忽略异常
  }
  return next;
}

export function clearRecentRooms(): RecentRoom[] {
  try {
    localStorage.removeItem(KEY_RECENT_ROOMS);
  } catch {
    // 忽略异常
  }
  return [];
}

// 读取持久化屏蔽成员列表（按 clientId 标识）
export function loadBlockedPeers(): BlockedPeer[] {
  try {
    const raw = localStorage.getItem(KEY_BLOCKED_PEERS);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return (parsed as BlockedPeer[]).filter((b) => Boolean(b?.clientId));
  } catch {
    return [];
  }
}

export function addBlockedPeer(peer: BlockedPeer): BlockedPeer[] {
  const list = loadBlockedPeers().filter((b) => b.clientId !== peer.clientId);
  const next = [peer, ...list].slice(0, 200);
  try {
    localStorage.setItem(KEY_BLOCKED_PEERS, JSON.stringify(next));
  } catch {
    // 忽略异常
  }
  return next;
}

export function removeBlockedPeer(clientId: string): BlockedPeer[] {
  const next = loadBlockedPeers().filter((b) => b.clientId !== clientId);
  try {
    localStorage.setItem(KEY_BLOCKED_PEERS, JSON.stringify(next));
  } catch {
    // 忽略异常
  }
  return next;
}

export function isClientIdBlocked(clientId?: string): boolean {
  if (!clientId) return false;
  return loadBlockedPeers().some((b) => b.clientId === clientId);
}

// 读取本地保存的「我发出的取件码」记录（自动过滤已过期项）
export function loadMyPickups(): MyPickupRecord[] {
  try {
    const raw = localStorage.getItem(KEY_MY_PICKUPS);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    const now = Date.now();
    return (parsed as MyPickupRecord[])
      .filter((p) => Boolean(p?.code) && p.expiresAt > now)
      .sort((a, b) => b.createdAt - a.createdAt);
  } catch {
    return [];
  }
}

export function upsertMyPickup(record: MyPickupRecord): MyPickupRecord[] {
  const list = loadMyPickups().filter((p) => p.code !== record.code);
  const next = [record, ...list].slice(0, 30);
  try {
    localStorage.setItem(KEY_MY_PICKUPS, JSON.stringify(next));
  } catch {
    // 忽略异常
  }
  return next;
}

export function removeMyPickup(code: string): MyPickupRecord[] {
  const next = loadMyPickups().filter((p) => p.code !== code);
  try {
    localStorage.setItem(KEY_MY_PICKUPS, JSON.stringify(next));
  } catch {
    // 忽略异常
  }
  return next;
}
