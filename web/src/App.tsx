import React, { useCallback, useEffect, useState } from 'react';
import type {
  RoomMode,
  SavedFixedRoom,
  ServerConfig,
  ThemeMode,
  UserProfile,
} from './types';
import {
  loadSavedFixedRooms,
  loadTheme,
  loadUserPrefs,
  loadUserProfile,
  removeSavedFixedRoom,
  saveTheme,
  saveUserPrefs,
  saveUserProfile,
  upsertSavedFixedRoom,
  type UserPrefs,
} from './lib/storage';
import { fetchRoomInfo, fetchServerConfig } from './lib/signaling';
import { Lobby } from './components/Lobby';
import { ChatRoom } from './components/ChatRoom';
import { ProfileModal } from './components/ProfileModal';

interface ActiveRoomSession {
  code: string;
  password: string;
}

const FALLBACK_CONFIG: ServerConfig = {
  domain: typeof window !== 'undefined' ? window.location.host : 'chat.baytale.com',
  maxRoomPeers: 6,
  maxRetentionDays: 7,
  historyMaxMediaBytes: 10 * 1024 * 1024,
  maxAttachmentMB: 0,
  defaultRoomMaxFileMB: 500,
  hasTurn: false,
  iceServers: [{ urls: ['stun:stun.cloudflare.com:3478', 'stun:stun.l.google.com:19302'] }],
};

// BayChat v3 主入口：驱动多巴胺 5 色主题切换、免账号百变身份、大厅与创意聊天室联动
export const App: React.FC = () => {
  const [profile, setProfile] = useState<UserProfile>(() => loadUserProfile());
  const [theme, setTheme] = useState<ThemeMode>(() => loadTheme());
  const [prefs, setPrefs] = useState<UserPrefs>(() => loadUserPrefs());
  const [savedFixedRooms, setSavedFixedRooms] = useState<SavedFixedRoom[]>(() =>
    loadSavedFixedRooms()
  );

  const [serverConfig, setServerConfig] = useState<ServerConfig>(FALLBACK_CONFIG);
  const [activeRoom, setActiveRoom] = useState<ActiveRoomSession | null>(null);
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
    document.documentElement.setAttribute('data-theme', theme);
    saveTheme(theme);
  }, [theme]);

  useEffect(() => {
    fetchServerConfig(profile.clientId)
      .then((cfg) => setServerConfig(cfg))
      .catch(() => {});
  }, [profile.clientId]);

  useEffect(() => {
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
          const saved = loadSavedFixedRooms().find((r) => r.code === info.code);
          if (saved?.password) {
            setActiveRoom({ code: info.code, password: saved.password });
            syncUrlRoom(info.code);
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
    if (url.pathname.startsWith('/r/')) {
      url.pathname = '/';
    }
    if (code) url.searchParams.set('room', code);
    else url.searchParams.delete('room');
    window.history.replaceState({}, '', url.toString());
  };

  const handleEnterRoom = useCallback(
    (code: string, password: string, mode: RoomMode, rememberFixed?: boolean) => {
      setLobbyNotice('');
      setInitialJoinCode('');
      setActiveRoom({ code, password });
      syncUrlRoom(code);

      if (mode === 'fixed' && rememberFixed) {
        setSavedFixedRooms(
          upsertSavedFixedRoom({
            code,
            hasPassword: Boolean(password),
            password: password || undefined,
            lastVisitedAt: Date.now(),
          })
        );
      }
    },
    []
  );

  const handleSwitchRoom = useCallback((newCode: string, password: string) => {
    setActiveRoom({ code: newCode, password });
    syncUrlRoom(newCode);
  }, []);

  const handleExitToLobby = useCallback(
    (notice?: string) => {
      setActiveRoom(null);
      setInitialJoinCode('');
      setLobbyNotice('');
      syncUrlRoom(null);
      if (notice) pushToast(notice);
    },
    [pushToast]
  );

  const handleSaveProfile = (next: UserProfile) => {
    saveUserProfile(next);
    setProfile(next);
    pushToast('✨ 已换上新身份');
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
      {activeRoom ? (
        <ChatRoom
          roomCode={activeRoom.code}
          password={activeRoom.password}
          profile={profile}
          prefs={prefs}
          theme={theme}
          serverConfig={serverConfig}
          savedFixedRooms={savedFixedRooms}
          onSelectTheme={setTheme}
          onUpdatePrefs={handleUpdatePrefs}
          onEditProfile={() => setProfileModalOpen(true)}
          onSwitchRoom={handleSwitchRoom}
          onExitToLobby={handleExitToLobby}
          onToast={pushToast}
        />
      ) : (
        <Lobby
          profile={profile}
          theme={theme}
          serverConfig={serverConfig}
          savedFixedRooms={savedFixedRooms}
          initialJoinCode={initialJoinCode}
          initialNotice={lobbyNotice}
          onSelectTheme={setTheme}
          onQuickRandomProfile={handleSaveProfile}
          onEditProfile={() => setProfileModalOpen(true)}
          onEnterRoom={handleEnterRoom}
          onRemoveSavedRoom={handleRemoveSavedRoom}
          onToast={pushToast}
        />
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
