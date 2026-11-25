import React, { useCallback, useEffect, useState } from 'react';
import type {
  AppearanceMode,
  PendingNearbyInvite,
  RecentRoom,
  RoomMode,
  SavedFixedRoom,
  ServerConfig,
  ThemeMode,
  UserProfile,
} from './types';
import {
  clearRecentRooms,
  loadAppearance,
  loadRecentRooms,
  loadSavedFixedRooms,
  loadTheme,
  loadUserPrefs,
  loadUserProfile,
  removeRecentRoom,
  removeSavedFixedRoom,
  saveAppearance,
  saveTheme,
  saveUserPrefs,
  saveUserProfile,
  upsertRecentRoom,
  upsertSavedFixedRoom,
  type UserPrefs,
} from './lib/storage';
import { fetchRoomInfo, fetchServerConfig } from './lib/signaling';
import { Lobby } from './components/Lobby';
import { ChatRoom } from './components/ChatRoom';
import { ProfileModal } from './components/ProfileModal';
import { PickupPage } from './components/PickupPage';

interface ActiveRoomSession {
  code: string;
  password: string;
  ownerToken?: string;
}

function parsePickupCodeFromLocation(): string | null {
  if (typeof window === 'undefined') return null;
  const m = window.location.pathname.match(/^\/p\/(\d{6})\/?$/);
  return m ? m[1] : null;
}

const FALLBACK_CONFIG: ServerConfig = {
  domain: typeof window !== 'undefined' ? window.location.host : 'chat.baytale.com',
  maxRoomPeers: 6,
  maxRetentionDays: 7,
  historyMaxMediaBytes: 10 * 1024 * 1024,
  historyMaxFileBytes: 100 * 1024 * 1024,
  maxAttachmentMB: 0,
  defaultRoomMaxFileMB: 500,
  hasTurn: false,
  iceServers: [{ urls: ['stun:stun.cloudflare.com:3478', 'stun:stun.l.google.com:19302'] }],
};

// BayChat 主入口：驱动 5 色强调色与浅/深外观切换、免账号身份、大厅与聊天室联动
export const App: React.FC = () => {
  const [profile, setProfile] = useState<UserProfile>(() => loadUserProfile());
  const [theme, setTheme] = useState<ThemeMode>(() => loadTheme());
  const [appearance, setAppearance] = useState<AppearanceMode>(() => loadAppearance());
  const [systemDark, setSystemDark] = useState<boolean>(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return false;
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  });
  const [prefs, setPrefs] = useState<UserPrefs>(() => loadUserPrefs());
  const [savedFixedRooms, setSavedFixedRooms] = useState<SavedFixedRoom[]>(() =>
    loadSavedFixedRooms()
  );
  const [recentRooms, setRecentRooms] = useState<RecentRoom[]>(() => loadRecentRooms());

  const [serverConfig, setServerConfig] = useState<ServerConfig>(FALLBACK_CONFIG);
  const [pickupRouteCode, setPickupRouteCode] = useState<string | null>(() =>
    parsePickupCodeFromLocation()
  );
  const [activeRoom, setActiveRoom] = useState<ActiveRoomSession | null>(null);
  const [pendingNearbyInvite, setPendingNearbyInvite] = useState<PendingNearbyInvite | null>(null);
  const [roomMinimized, setRoomMinimized] = useState(false);
  const [minimizedStatus, setMinimizedStatus] = useState('后台运行中');
  const [initialJoinCode, setInitialJoinCode] = useState<string>('');
  const [lobbyNotice, setLobbyNotice] = useState<string>('');
  const [profileModalOpen, setProfileModalOpen] = useState(false);
  const [toasts, setToasts] = useState<{ id: number; text: string }[]>([]);

  const pushToast = useCallback((text: string) => {
    const id = Date.now() + Math.random();
    setToasts((prev) => [...prev.slice(-3), { id, text }]);
    window.setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 2800);
  }, []);

  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    setSystemDark(mq.matches);
    const onChange = (e: MediaQueryListEvent) => setSystemDark(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  const resolvedAppearance: 'light' | 'dark' =
    appearance === 'system' ? (systemDark ? 'dark' : 'light') : appearance;

  useEffect(() => {
    document.documentElement.setAttribute('data-accent', theme);
    saveTheme(theme);
  }, [theme]);

  useEffect(() => {
    const root = document.documentElement;
    root.setAttribute('data-appearance', resolvedAppearance);
    saveAppearance(appearance);
    const bgColor = getComputedStyle(root).getPropertyValue('--bg').trim();
    const metaThemeColor = document.querySelector('meta[name="theme-color"]');
    if (metaThemeColor && bgColor) {
      metaThemeColor.setAttribute('content', bgColor);
    }
  }, [appearance, resolvedAppearance]);

  useEffect(() => {
    fetchServerConfig(profile.clientId)
      .then((cfg) => setServerConfig(cfg))
      .catch(() => {});
  }, [profile.clientId]);

  useEffect(() => {
    const onPopState = () => {
      setPickupRouteCode(parsePickupCodeFromLocation());
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  useEffect(() => {
    if (pickupRouteCode) return;
    const params = new URLSearchParams(window.location.search);
    let code = (params.get('room') || '').trim().toLowerCase();
    if (!code) {
      const m = window.location.pathname.match(/^\/r\/([a-zA-Z0-9_-]{3,24})\/?$/);
      if (m) code = m[1].toLowerCase();
    }
    if (!code) return;

    fetchRoomInfo(code)
      .then((info) => {
        if (!info.hasPassword) {
          setActiveRoom({ code: info.code, password: '' });
          syncUrlRoom(info.code);
          setRecentRooms(
            upsertRecentRoom({
              code: info.code,
              mode: info.mode,
              hasPassword: false,
              lastVisitedAt: Date.now(),
            })
          );
          if (info.mode === 'fixed') {
            setSavedFixedRooms(
              upsertSavedFixedRoom({
                code: info.code,
                hasPassword: false,
                lastVisitedAt: Date.now(),
              })
            );
          }
        } else {
          const saved =
            loadSavedFixedRooms().find((r) => r.code === info.code) ||
            loadRecentRooms().find((r) => r.code === info.code);
          if (saved?.password) {
            setActiveRoom({ code: info.code, password: saved.password });
            syncUrlRoom(info.code);
            setRecentRooms(
              upsertRecentRoom({
                code: info.code,
                mode: info.mode,
                hasPassword: true,
                password: saved.password,
                lastVisitedAt: Date.now(),
              })
            );
          } else {
            setInitialJoinCode(info.code);
          }
        }
      })
      .catch((err) => {
        setInitialJoinCode(code);
        setLobbyNotice(err instanceof Error ? err.message : '房间不存在或已失效');
      });
  }, []);

  const syncUrlRoom = (code: string | null) => {
    const url = new URL(window.location.href);
    if (url.pathname.startsWith('/r/') || url.pathname.startsWith('/p/')) {
      url.pathname = '/';
    }
    if (code) url.searchParams.set('room', code);
    else url.searchParams.delete('room');
    window.history.replaceState({}, '', url.toString());
  };

  const handleOpenPickupPage = useCallback((code: string) => {
    const url = new URL(window.location.href);
    url.pathname = `/p/${encodeURIComponent(code)}`;
    url.search = '';
    window.history.pushState({}, '', url.toString());
    setPickupRouteCode(code);
  }, []);

  const handleBackFromPickupPage = useCallback(() => {
    const url = new URL(window.location.href);
    url.pathname = '/';
    url.search = '';
    window.history.pushState({}, '', url.toString());
    setPickupRouteCode(null);
  }, []);

  const handleEnterRoom = useCallback(
    (
      code: string,
      password: string,
      mode: RoomMode,
      rememberFixed?: boolean,
      ownerToken?: string,
      nearbyInvite?: PendingNearbyInvite
    ) => {
      const resolvedOwnerToken =
        ownerToken ||
        loadSavedFixedRooms().find((r) => r.code === code)?.ownerToken ||
        loadRecentRooms().find((r) => r.code === code)?.ownerToken;

      setPickupRouteCode(null);
      setLobbyNotice('');
      setInitialJoinCode('');
      setRoomMinimized(false);
      setPendingNearbyInvite(nearbyInvite || null);
      setActiveRoom({ code, password, ownerToken: resolvedOwnerToken });
      syncUrlRoom(code);

      setRecentRooms(
        upsertRecentRoom({
          code,
          mode,
          hasPassword: Boolean(password),
          password: password || undefined,
          ownerToken: resolvedOwnerToken,
          lastVisitedAt: Date.now(),
        })
      );

      if (mode === 'fixed' && rememberFixed) {
        setSavedFixedRooms(
          upsertSavedFixedRoom({
            code,
            hasPassword: Boolean(password),
            password: password || undefined,
            ownerToken: resolvedOwnerToken,
            lastVisitedAt: Date.now(),
          })
        );
      }
    },
    []
  );

  const handleSwitchRoom = useCallback(
    (newCode: string, password: string, mode: RoomMode = 'ephemeral', ownerToken?: string) => {
      const resolvedOwnerToken =
        ownerToken ||
        loadSavedFixedRooms().find((r) => r.code === newCode)?.ownerToken ||
        loadRecentRooms().find((r) => r.code === newCode)?.ownerToken;

      setRoomMinimized(false);
      setPendingNearbyInvite(null);
      setActiveRoom({ code: newCode, password, ownerToken: resolvedOwnerToken });
      syncUrlRoom(newCode);
      setRecentRooms(
        upsertRecentRoom({
          code: newCode,
          mode,
          hasPassword: Boolean(password),
          password: password || undefined,
          ownerToken: resolvedOwnerToken,
          lastVisitedAt: Date.now(),
        })
      );
      if (mode === 'fixed') {
        const existingSaved = loadSavedFixedRooms().find((r) => r.code === newCode);
        if (existingSaved) {
          setSavedFixedRooms(
            upsertSavedFixedRoom({
              ...existingSaved,
              hasPassword: Boolean(password),
              password: password || undefined,
              ownerToken: resolvedOwnerToken ?? existingSaved.ownerToken,
              lastVisitedAt: Date.now(),
            })
          );
        }
      }
    },
    []
  );

  const handleRemoveRecentRoom = useCallback((code: string) => {
    setRecentRooms(removeRecentRoom(code));
  }, []);

  const handleClearRecentRooms = useCallback(() => {
    setRecentRooms(clearRecentRooms());
    pushToast('已清空最近房间记录');
  }, [pushToast]);

  const handleExitToLobby = useCallback(
    (notice?: string) => {
      setActiveRoom(null);
      setPendingNearbyInvite(null);
      setRoomMinimized(false);
      setInitialJoinCode('');
      setLobbyNotice('');
      syncUrlRoom(null);
      if (notice) pushToast(notice);
    },
    [pushToast]
  );

  const handleMinimizeRoom = useCallback(() => {
    setRoomMinimized(true);
    pushToast('房间已最小化挂起，连接与文件传输继续保持中');
  }, [pushToast]);

  const handleSaveProfile = (next: UserProfile) => {
    saveUserProfile(next);
    setProfile(next);
    pushToast('已换上新身份');
  };

  const handleUpdatePrefs = (next: UserPrefs) => {
    saveUserPrefs(next);
    setPrefs(next);
  };

  const handleRemoveSavedRoom = (code: string) => {
    setSavedFixedRooms(removeSavedFixedRoom(code));
    pushToast(`已从秘密基地移除 #${code}`);
  };

  return (
    <>
      {pickupRouteCode && (!activeRoom || roomMinimized) ? (
        <PickupPage
          code={pickupRouteCode}
          theme={theme}
          appearance={appearance}
          onSelectTheme={setTheme}
          onSelectAppearance={setAppearance}
          onBackToLobby={handleBackFromPickupPage}
        />
      ) : null}

      {/* 最小化挂起时通过 display:none 保持 ChatRoom 组件存活，WS、WebRTC 与大文件传输 100% 不断线 */}
      {activeRoom && (
        <div
          style={{
            display: roomMinimized ? 'none' : 'contents',
          }}
        >
          <ChatRoom
            roomCode={activeRoom.code}
            password={activeRoom.password}
            ownerToken={activeRoom.ownerToken}
            pendingNearbyInvite={pendingNearbyInvite}
            profile={profile}
            prefs={prefs}
            theme={theme}
            appearance={appearance}
            serverConfig={serverConfig}
            savedFixedRooms={savedFixedRooms}
            recentRooms={recentRooms}
            onSelectTheme={setTheme}
            onSelectAppearance={setAppearance}
            onUpdatePrefs={handleUpdatePrefs}
            onEditProfile={() => setProfileModalOpen(true)}
            onSwitchRoom={handleSwitchRoom}
            onClearPendingNearbyInvite={() => setPendingNearbyInvite(null)}
            onRemoveRecentRoom={handleRemoveRecentRoom}
            onMinimizeRoom={handleMinimizeRoom}
            onStatusSummary={setMinimizedStatus}
            onExitToLobby={handleExitToLobby}
            onToast={pushToast}
          />
        </div>
      )}

      {!pickupRouteCode && (!activeRoom || roomMinimized) && (
        <Lobby
          profile={profile}
          prefs={prefs}
          theme={theme}
          appearance={appearance}
          isActiveLobby={!pickupRouteCode && (!activeRoom || roomMinimized)}
          serverConfig={serverConfig}
          savedFixedRooms={savedFixedRooms}
          recentRooms={recentRooms}
          initialJoinCode={initialJoinCode}
          initialNotice={lobbyNotice}
          onSelectTheme={setTheme}
          onSelectAppearance={setAppearance}
          onUpdatePrefs={handleUpdatePrefs}
          onQuickRandomProfile={handleSaveProfile}
          onEditProfile={() => setProfileModalOpen(true)}
          onEnterRoom={handleEnterRoom}
          onOpenPickupPage={handleOpenPickupPage}
          onRemoveSavedRoom={handleRemoveSavedRoom}
          onRemoveRecentRoom={handleRemoveRecentRoom}
          onClearRecentRooms={handleClearRecentRooms}
          onToast={pushToast}
        />
      )}

      {/* 后台挂起房间悬浮胶囊条 */}
      {activeRoom && roomMinimized && (
        <div className="minimized-room-dock" onClick={() => setRoomMinimized(false)}>
          <span className="status-dot emerald" />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="mono" style={{ fontSize: 13.5, fontWeight: 700 }}>
              房间 #{activeRoom.code} 后台运行中
            </div>
            <div
              style={{
                fontSize: 11.5,
                color: 'var(--text-secondary)',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {minimizedStatus}
            </div>
          </div>
          <button
            type="button"
            className="btn btn-jelly btn-sm"
            onClick={(e) => {
              e.stopPropagation();
              setRoomMinimized(false);
            }}
          >
            回到房间
          </button>
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            style={{ padding: '0 8px' }}
            title="彻底断开该房间"
            onClick={(e) => {
              e.stopPropagation();
              handleExitToLobby('已断开后台挂起的房间');
            }}
          >
            断开
          </button>
        </div>
      )}

      {profileModalOpen && (
        <ProfileModal
          profile={profile}
          onSave={handleSaveProfile}
          onClose={() => setProfileModalOpen(false)}
        />
      )}

      {toasts.length > 0 && (
        <div className="toast-stack">
          {toasts.map((t) => (
            <div key={t.id} className="toast-pill">
              {t.text}
            </div>
          ))}
        </div>
      )}
    </>
  );
};
