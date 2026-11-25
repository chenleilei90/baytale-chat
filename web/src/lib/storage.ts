import type { RecentRoom, SavedFixedRoom, ThemeMode, UserProfile } from '../types';
import { generateRandomName, pickRandomPresetAvatar } from './avatar';

const KEY_PROFILE = 'baychat_profile_v1';
const KEY_THEME = 'baychat_theme_v1';
const KEY_SAVED_ROOMS = 'baychat_fixed_rooms_v1';
const KEY_RECENT_ROOMS = 'baychat_recent_rooms_v1';
const KEY_PREFS = 'baychat_prefs_v1';
const KEY_TAB_PEER_ID = 'baychat_tab_peer_id_v1';

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
}

const DEFAULT_PREFS: UserPrefs = {
  autoAcceptSmallFiles: true,
  autoAcceptMaxMB: 50,
  imageSendMode: 'original',
  soundEnabled: true,
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

const VALID_THEMES: ThemeMode[] = ['peach', 'mint', 'berry', 'lime', 'neon'];

export function loadTheme(): ThemeMode {
  try {
    const saved = localStorage.getItem(KEY_THEME) as ThemeMode | null;
    if (saved && VALID_THEMES.includes(saved)) {
      return saved;
    }
  } catch {
    // 忽略异常
  }
  return 'peach';
}

export function saveTheme(theme: ThemeMode): void {
  try {
    localStorage.setItem(KEY_THEME, theme);
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
