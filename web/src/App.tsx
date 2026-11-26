import React, { useCallback, useEffect, useState } from 'react';
import type {
  AppearanceMode,
  BlockedPeer,
  ChatItem,
  FavoriteItem,
  MinimizedTransferState,
  MyPickupRecord,
  PendingNearbyInvite,
  RecentRoom,
  RoomMode,
  SavedFixedRoom,
  ServerConfig,
  ThemeMode,
  TransferRecord,
  UserProfile,
} from './types';
import {
  addFavorite,
  addTransferRecord,
  clearFavorites,
  clearLocalCacheData,
  clearRecentRooms,
  clearTransferRecords,
  loadAppearance,
  loadBlockedPeers,
  loadFavorites,
  loadMutedRooms,
  loadMyPickups,
  loadRecentRooms,
  loadSavedFixedRooms,
  loadTheme,
  loadTransferRecords,
  loadUserPrefs,
  loadUserProfile,
  removeBlockedPeer,
  removeFavorite,
  removeMyPickup,
  removeRecentRoom,
  removeSavedFixedRoom,
  removeTransferRecord,
  saveAppearance,
  saveTheme,
  saveUserPrefs,
  saveUserProfile,
  toggleMutedRoom,
  updateSavedFixedRoomLabel,
  upsertRecentRoom,
  upsertSavedFixedRoom,
  type UserPrefs,
} from './lib/storage';
import { deletePickup, fetchRoomInfo, fetchServerConfig } from './lib/signaling';
import { stopTitleFlash } from './lib/notifications';
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

// BayChat 主入口：驱动 5 色强调色与浅/深外观切换、免账号身份、4-Tab 工作台与聊天室实时协同
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
  const [transferRecords, setTransferRecords] = useState<TransferRecord[]>(() =>
    loadTransferRecords()
  );
  const [favorites, setFavorites] = useState<FavoriteItem[]>(() => loadFavorites());
  const [blockedPeers, setBlockedPeers] = useState<BlockedPeer[]>(() => loadBlockedPeers());
  const [mutedRooms, setMutedRooms] = useState<string[]>(() => loadMutedRooms());
  const [myPickups, setMyPickups] = useState<MyPickupRecord[]>(() => loadMyPickups());

  const [serverConfig, setServerConfig] = useState<ServerConfig>(FALLBACK_CONFIG);
  const [pickupRouteCode, setPickupRouteCode] = useState<string | null>(() =>
    parsePickupCodeFromLocation()
  );
  const [activeRoom, setActiveRoom] = useState<ActiveRoomSession | null>(null);
  const [pendingNearbyInvite, setPendingNearbyInvite] = useState<PendingNearbyInvite | null>(null);
  const [roomMinimized, setRoomMinimized] = useState(false);
  const [minimizedStatus, setMinimizedStatus] = useState('后台运行中');
  const [minimizedTransferState, setMinimizedTransferState] = useState<MinimizedTransferState>({
    status: 'idle',
    percent: 0,
  });
  const [minimizedUnreadCount, setMinimizedUnreadCount] = useState(0);
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

  const syncUrlRoom = (code: string | null) => {
    const url = new URL(window.location.href);
    if (url.pathname.startsWith('/r/') || url.pathname.startsWith('/p/')) {
      url.pathname = '/';
    }
    if (code) url.searchParams.set('room', code);
    else url.searchParams.delete('room');
    window.history.replaceState({}, '', url.toString());
  };

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
        const saved = loadSavedFixedRooms().find((r) => r.code === info.code);
        const recent = loadRecentRooms().find((r) => r.code === info.code);
        // 刷新页面从 URL 恢复房间时，一并恢复本地持久化保存的房主令牌 ownerToken
        const restoredOwnerToken = saved?.ownerToken || recent?.ownerToken;

        if (!info.hasPassword) {
          setActiveRoom({ code: info.code, password: '', ownerToken: restoredOwnerToken });
          syncUrlRoom(info.code);
          setRecentRooms(
            upsertRecentRoom({
              code: info.code,
              mode: info.mode,
              hasPassword: false,
              ownerToken: restoredOwnerToken,
              lastVisitedAt: Date.now(),
            })
          );
          if (info.mode === 'fixed') {
            setSavedFixedRooms(
              upsertSavedFixedRoom({
                code: info.code,
                label: saved?.label,
                hasPassword: false,
                ownerToken: restoredOwnerToken,
                lastVisitedAt: Date.now(),
              })
            );
          }
        } else {
          const restoredPwd = saved?.password || recent?.password;
          if (restoredPwd) {
            setActiveRoom({
              code: info.code,
              password: restoredPwd,
              ownerToken: restoredOwnerToken,
            });
            syncUrlRoom(info.code);
            setRecentRooms(
              upsertRecentRoom({
                code: info.code,
                mode: info.mode,
                hasPassword: true,
                password: restoredPwd,
                ownerToken: restoredOwnerToken,
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
      const existingSaved = loadSavedFixedRooms().find((r) => r.code === code);
      const resolvedOwnerToken =
        ownerToken ||
        existingSaved?.ownerToken ||
        loadRecentRooms().find((r) => r.code === code)?.ownerToken;

      setPickupRouteCode(null);
      setLobbyNotice('');
      setInitialJoinCode('');
      setRoomMinimized(false);
      setMinimizedUnreadCount(0);
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
            label: existingSaved?.label,
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
      const existingSaved = loadSavedFixedRooms().find((r) => r.code === newCode);
      const resolvedOwnerToken =
        ownerToken ||
        existingSaved?.ownerToken ||
        loadRecentRooms().find((r) => r.code === newCode)?.ownerToken;

      setRoomMinimized(false);
      setMinimizedUnreadCount(0);
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
      if (mode === 'fixed' && existingSaved) {
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
    },
    []
  );

  // 房主在线更改房间密码：原位更新房间会话配置与本地凭证，不断开 WebSocket 也不清空聊天区
  const handlePasswordUpdatedInPlace = useCallback(
    (code: string, nextPwd: string, mode: RoomMode, ownerToken?: string) => {
      setActiveRoom((prev) =>
        prev && prev.code === code
          ? { ...prev, password: nextPwd, ownerToken: ownerToken || prev.ownerToken }
          : prev
      );
      setRecentRooms(
        upsertRecentRoom({
          code,
          mode,
          hasPassword: Boolean(nextPwd),
          password: nextPwd || undefined,
          ownerToken,
          lastVisitedAt: Date.now(),
        })
      );
      const existingSaved = loadSavedFixedRooms().find((r) => r.code === code);
      if (mode === 'fixed' && existingSaved) {
        setSavedFixedRooms(
          upsertSavedFixedRoom({
            ...existingSaved,
            hasPassword: Boolean(nextPwd),
            password: nextPwd || undefined,
            ownerToken: ownerToken ?? existingSaved.ownerToken,
            lastVisitedAt: Date.now(),
          })
        );
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
      setMinimizedUnreadCount(0);
      setInitialJoinCode('');
      setLobbyNotice('');
      stopTitleFlash('湾聊 · BayChat');
      syncUrlRoom(null);
      if (notice) pushToast(notice);
    },
    [pushToast]
  );

  const handleMinimizeRoom = useCallback(() => {
    setRoomMinimized(true);
    pushToast('房间已最小化挂起，连接与文件传输继续保持中');
  }, [pushToast]);

  const handleRestoreRoom = useCallback(() => {
    setRoomMinimized(false);
    setMinimizedUnreadCount(0);
    stopTitleFlash();
  }, []);

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
    pushToast(`已从常驻收藏移除 #${code}`);
  };

  const handleRenameSavedRoom = (code: string, label: string) => {
    setSavedFixedRooms(updateSavedFixedRoomLabel(code, label));
    pushToast(label.trim() ? `已更新房间备注「${label.trim()}」` : '已恢复默认房间名');
  };

  const handleRecordTransfer = useCallback((record: TransferRecord) => {
    setTransferRecords(addTransferRecord(record));
  }, []);

  const handleRemoveTransferRecord = (id: string) => {
    setTransferRecords(removeTransferRecord(id));
  };

  const handleClearTransferRecords = () => {
    setTransferRecords(clearTransferRecords());
    pushToast('已清空传输历史记录');
  };

  const handleFavoriteMessage = useCallback(
    (roomCode: string, item: ChatItem) => {
      const kind: FavoriteItem['kind'] =
        item.kind === 'image' || item.kind === 'file' || item.kind === 'audio' ? item.kind : 'text';
      const next = addFavorite({
        id: `fav_${item.id}`,
        kind,
        roomCode,
        senderName: item.senderName,
        text: item.text || item.caption,
        fileName: item.transfer?.fileName,
        fileSize: item.transfer?.fileSize,
        mimeType: item.transfer?.mimeType,
        cachedDownloadUrl: item.transfer?.cachedDownloadUrl,
        imageUrl: item.imageUrl,
        createdAt: Date.now(),
      });
      setFavorites(next);
      pushToast('已收藏到本地「我的收藏」');
    },
    [pushToast]
  );

  const handleRemoveFavorite = (id: string) => {
    setFavorites(removeFavorite(id));
  };

  const handleClearFavorites = () => {
    setFavorites(clearFavorites());
    pushToast('已清空本地收藏');
  };

  const handleUnblockPeer = (clientId: string) => {
    setBlockedPeers(removeBlockedPeer(clientId));
    pushToast('已解除屏蔽');
  };

  const handleToggleMuteRoom = useCallback(
    (code: string) => {
      const next = toggleMutedRoom(code);
      setMutedRooms(next);
      const isNowMuted = next.includes(code.trim().toLowerCase());
      pushToast(isNowMuted ? `房间 #${code} 已开启消息免打扰` : `已恢复房间 #${code} 消息提醒`);
    },
    [pushToast]
  );

  const handleRevokePickup = async (item: MyPickupRecord) => {
    try {
      await deletePickup(item.code, item.ownerToken);
      setMyPickups(removeMyPickup(item.code));
      pushToast(`已撤销取件码 #${item.code}`);
    } catch (err) {
      pushToast(err instanceof Error ? err.message : '撤销取件码失败');
    }
  };

  const handleClearLocalData = () => {
    clearLocalCacheData();
    setRecentRooms([]);
    setMyPickups([]);
    setMutedRooms([]);
    setTransferRecords([]);
    setFavorites([]);
    pushToast('已清理本地历史记录、收藏与取件码缓存');
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
            isMinimized={roomMinimized}
            isMutedRoom={mutedRooms.includes(activeRoom.code.trim().toLowerCase())}
            onSelectTheme={setTheme}
            onSelectAppearance={setAppearance}
            onUpdatePrefs={handleUpdatePrefs}
            onEditProfile={() => setProfileModalOpen(true)}
            onSwitchRoom={handleSwitchRoom}
            onPasswordUpdated={handlePasswordUpdatedInPlace}
            onClearPendingNearbyInvite={() => setPendingNearbyInvite(null)}
            onRemoveRecentRoom={handleRemoveRecentRoom}
            onMinimizeRoom={handleMinimizeRoom}
            onRestoreFromNotification={handleRestoreRoom}
            onStatusSummary={setMinimizedStatus}
            onTransferStateChange={setMinimizedTransferState}
            onUnreadChange={setMinimizedUnreadCount}
            onToggleMuteRoom={handleToggleMuteRoom}
            onRecordTransfer={handleRecordTransfer}
            onFavoriteMessage={(item) => handleFavoriteMessage(activeRoom.code, item)}
            onBlockedPeersChanged={() => setBlockedPeers(loadBlockedPeers())}
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
          transferRecords={transferRecords}
          favorites={favorites}
          blockedPeers={blockedPeers}
          myPickups={myPickups}
          initialJoinCode={initialJoinCode}
          initialNotice={lobbyNotice}
          onSelectTheme={setTheme}
          onSelectAppearance={setAppearance}
          onUpdatePrefs={handleUpdatePrefs}
          onQuickRandomProfile={handleSaveProfile}
          onEditProfile={() => setProfileModalOpen(true)}
          onEnterRoom={handleEnterRoom}
          onOpenPickupPage={handleOpenPickupPage}
          onPickupsChange={setMyPickups}
          onRevokePickup={(item) => void handleRevokePickup(item)}
          onRemoveSavedRoom={handleRemoveSavedRoom}
          onRenameSavedRoom={handleRenameSavedRoom}
          onRemoveRecentRoom={handleRemoveRecentRoom}
          onClearRecentRooms={handleClearRecentRooms}
          onRemoveTransferRecord={handleRemoveTransferRecord}
          onClearTransferRecords={handleClearTransferRecords}
          onRemoveFavorite={handleRemoveFavorite}
          onClearFavorites={handleClearFavorites}
          onUnblockPeer={handleUnblockPeer}
          onClearLocalData={handleClearLocalData}
          onToast={pushToast}
        />
      )}

      {/* 后台挂起房间悬浮胶囊条（含单条变色动效下载状态线与百分比） */}
      {activeRoom && roomMinimized && (() => {
        const trState = minimizedTransferState;
        const hasTransferLine = trState.status !== 'idle';
        const linePercent =
          trState.status === 'completed'
            ? 100
            : trState.status === 'error'
              ? Math.max(18, trState.percent)
              : Math.max(4, Math.min(100, trState.percent));
        const statusLabel =
          trState.status === 'downloading'
            ? `正在下载 ${trState.percent}%`
            : trState.status === 'paused'
              ? `下载暂停 ${trState.percent}%`
              : trState.status === 'error'
                ? '下载失败'
                : trState.status === 'completed'
                  ? '已完成 100%'
                  : '';
        return (
          <div className="minimized-room-dock" onClick={handleRestoreRoom}>
            <span className="status-dot emerald" />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="mono" style={{ fontSize: 13.5, fontWeight: 700, display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                <span>房间 #{activeRoom.code}</span>
                {hasTransferLine && (
                  <span className={`dock-status-pill ${trState.status}`}>
                    {statusLabel}
                  </span>
                )}
                {minimizedUnreadCount > 0 && (
                  <span className="unread-dock-badge" aria-label={`${minimizedUnreadCount} 条未读消息`}>
                    {minimizedUnreadCount > 99 ? '99+' : minimizedUnreadCount}
                  </span>
                )}
              </div>
              {!hasTransferLine && (
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
              )}
            </div>
            <button
              type="button"
              className="btn btn-jelly btn-sm"
              onClick={(e) => {
                e.stopPropagation();
                handleRestoreRoom();
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
            {hasTransferLine && (
              <div className="dock-status-track" aria-hidden="true">
                <div
                  className={`dock-status-fill ${trState.status}`}
                  style={{ width: `${linePercent}%` }}
                >
                  {trState.status === 'downloading' && <span className="dock-status-runner" />}
                </div>
              </div>
            )}
          </div>
        );
      })()}

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
