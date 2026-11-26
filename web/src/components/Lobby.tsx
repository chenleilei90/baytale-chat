import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Flame,
  Pin,
  Lock,
  ArrowRight,
  Copy,
  Trash2,
  Send,
  Dices,
  ClipboardPaste,
  AlertCircle,
  Clock,
  SlidersHorizontal,
  ChevronDown,
  ChevronUp,
  Globe,
  X,
  Package,
  Radio,
  Smartphone,
  Laptop,
  KeyRound,
  HardDrive,
  Settings2,
  RefreshCw,
  UserRound,
} from 'lucide-react';
import type {
  AppearanceMode,
  NearbyDevice,
  NearbyInviteNotice,
  PaperPlane,
  PendingNearbyInvite,
  RecentRoom,
  RoomMode,
  SavedFixedRoom,
  ServerConfig,
  ThemeMode,
  UserProfile,
} from '../types';
import {
  ApiError,
  createRoom,
  extractRoomCodeInput,
  fetchPlanes,
  fetchRoomInfo,
  joinPlane,
  pickRandomPublicRoom,
  reportPlane,
  respondNearbyInvite,
  sendNearbyHeartbeat,
  sendNearbyInvite,
  verifyRoomPassword,
} from '../lib/signaling';
import { copyTextToClipboard, formatBytes, formatRelativeTime } from '../lib/platform';
import { generateRandomName, pickRandomPresetAvatar } from '../lib/avatar';
import type { UserPrefs } from '../lib/storage';
import { Avatar } from './Avatar';
import { Switch } from './Controls';
import { PickupLookupModal, PickupSendModal } from './PickupModal';
import { SettingsPopover } from './SettingsPopover';
import { PlaneViewerModal, renderPlaneKindIcon } from './PlaneViewerModal';

interface LobbyProps {
  profile: UserProfile;
  prefs: UserPrefs;
  theme: ThemeMode;
  appearance: AppearanceMode;
  isActiveLobby: boolean;
  serverConfig: ServerConfig | null;
  savedFixedRooms: SavedFixedRoom[];
  recentRooms: RecentRoom[];
  initialJoinCode?: string;
  initialNotice?: string;
  onSelectTheme: (theme: ThemeMode) => void;
  onSelectAppearance: (mode: AppearanceMode) => void;
  onUpdatePrefs: (next: UserPrefs) => void;
  onQuickRandomProfile: (next: UserProfile) => void;
  onEditProfile: () => void;
  onEnterRoom: (
    code: string,
    password: string,
    mode: RoomMode,
    rememberFixed?: boolean,
    ownerToken?: string,
    pendingNearbyInvite?: PendingNearbyInvite
  ) => void;
  onOpenPickupPage: (code: string) => void;
  onRemoveSavedRoom: (code: string) => void;
  onRemoveRecentRoom: (code: string) => void;
  onClearRecentRooms: () => void;
  onToast: (msg: string) => void;
}

const RETENTION_STEPS = [
  { days: 0, label: '阅后即焚' },
  { days: 1, label: '1天' },
  { days: 2, label: '2天' },
  { days: 3, label: '3天' },
  { days: 5, label: '5天' },
  { days: 7, label: '7天' },
];

const FILE_LIMIT_PRESETS = [
  { mb: 50, label: '50MB' },
  { mb: 200, label: '200MB' },
  { mb: 500, label: '500MB' },
  { mb: 1024, label: '1GB' },
  { mb: 0, label: '不限制' },
];

// BayChat 大厅主视图：以房间存储与取件码为核心路径，底部收纳纸飞机公开文件清单
export const Lobby: React.FC<LobbyProps> = ({
  profile,
  prefs,
  theme,
  appearance,
  isActiveLobby,
  serverConfig,
  savedFixedRooms,
  recentRooms,
  initialJoinCode,
  initialNotice,
  onSelectTheme,
  onSelectAppearance,
  onUpdatePrefs,
  onQuickRandomProfile,
  onEditProfile,
  onEnterRoom,
  onOpenPickupPage,
  onRemoveSavedRoom,
  onRemoveRecentRoom,
  onClearRecentRooms,
  onToast,
}) => {
  const [roomInput, setRoomInput] = useState(initialJoinCode || '');
  const [roomPassword, setRoomPassword] = useState('');
  const [needPassword, setNeedPassword] = useState(false);
  const [showAdvanced, setShowAdvanced] = useState(false);

  const [mode, setMode] = useState<RoomMode>('ephemeral');
  const [allowRandom, setAllowRandom] = useState(false);
  const [privatePairOnly, setPrivatePairOnly] = useState(false);
  const [retentionDays, setRetentionDays] = useState(1);
  const [maxFileSizeMB, setMaxFileSizeMB] = useState<number>(
    serverConfig?.defaultRoomMaxFileMB ?? 500
  );
  const [saveToFavorites, setSaveToFavorites] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [randomJoining, setRandomJoining] = useState(false);
  const [errorText, setErrorText] = useState(initialNotice || '');

  // 顶栏浮层与列表标签状态
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [roomTab, setRoomTab] = useState<'recent' | 'saved'>(() =>
    recentRooms.length === 0 && savedFixedRooms.length > 0 ? 'saved' : 'recent'
  );

  // 不存在常驻房时的创建确认弹窗
  const [confirmCreateCode, setConfirmCreateCode] = useState<string | null>(null);

  // 取件码弹窗状态
  const [pickupSendOpen, setPickupSendOpen] = useState(false);
  const [pickupLookupOpen, setPickupLookupOpen] = useState(false);

  // 附近设备状态
  const [nearbyDevices, setNearbyDevices] = useState<NearbyDevice[]>([]);
  const [incomingInvite, setIncomingInvite] = useState<NearbyInviteNotice | null>(null);
  const [invitingId, setInvitingId] = useState<string | null>(null);
  const handledInviteIdsRef = useRef<Set<string>>(new Set());

  // 纸飞机广场状态
  const [planes, setPlanes] = useState<PaperPlane[]>([]);
  const [planesLoading, setPlanesLoading] = useState(false);
  const [planesError, setPlanesError] = useState('');
  const [viewingPlane, setViewingPlane] = useState<PaperPlane | null>(null);
  const [joiningPlane, setJoiningPlane] = useState(false);

  const nearbySectionRef = useRef<HTMLDivElement | null>(null);
  const codeInputRef = useRef<HTMLInputElement | null>(null);
  const passwordInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (serverConfig && typeof serverConfig.defaultRoomMaxFileMB === 'number') {
      setMaxFileSizeMB(serverConfig.defaultRoomMaxFileMB);
    }
  }, [serverConfig?.defaultRoomMaxFileMB]);

  useEffect(() => {
    if (initialJoinCode) {
      setRoomInput(initialJoinCode);
      void probeRoomInput(initialJoinCode);
    }
  }, [initialJoinCode]);

  useEffect(() => {
    if (initialNotice) setErrorText(initialNotice);
  }, [initialNotice]);

  // 点击空白处或按 Esc 收起顶栏弹出菜单
  useEffect(() => {
    if (!settingsOpen && !userMenuOpen) return;
    const onDocClick = () => {
      setSettingsOpen(false);
      setUserMenuOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setSettingsOpen(false);
        setUserMenuOpen(false);
      }
    };
    window.addEventListener('click', onDocClick);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('click', onDocClick);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [settingsOpen, userMenuOpen]);

  const loadPlanes = useCallback(async () => {
    setPlanesLoading(true);
    setPlanesError('');
    try {
      const res = await fetchPlanes(24);
      setPlanes(res.planes || []);
    } catch (err) {
      setPlanesError(err instanceof Error ? err.message : '纸飞机列表加载失败');
    } finally {
      setPlanesLoading(false);
    }
  }, []);

  useEffect(() => {
    if (isActiveLobby) {
      void loadPlanes();
    }
  }, [isActiveLobby, loadPlanes]);

  // 附近设备心跳：仅当大厅可见、处于前台且开启可被发现时每 5 秒发送一次
  useEffect(() => {
    if (!isActiveLobby || !prefs.nearbyDiscoverable) {
      setNearbyDevices([]);
      return;
    }

    let timer: number | null = null;
    let cancelled = false;

    const beat = async () => {
      if (cancelled || document.hidden) return;
      try {
        const res = await sendNearbyHeartbeat({
          clientId: profile.clientId,
          name: profile.name,
          avatar: profile.avatar,
          device: 'web',
          network: 'wifi',
        });
        if (cancelled) return;
        setNearbyDevices(res.devices || []);
        const nextInv = (res.invites || []).find(
          (inv) => !handledInviteIdsRef.current.has(inv.inviteId)
        );
        if (nextInv) {
          setIncomingInvite((cur) => cur || nextInv);
        }
      } catch {
        // 心跳瞬态失败静默忽略
      }
    };

    const startLoop = () => {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
      if (!document.hidden) {
        void beat();
        timer = window.setInterval(() => {
          void beat();
        }, 5000);
      }
    };

    const onVisChange = () => {
      if (document.hidden) {
        if (timer !== null) {
          clearInterval(timer);
          timer = null;
        }
      } else {
        startLoop();
      }
    };

    startLoop();
    document.addEventListener('visibilitychange', onVisChange);
    return () => {
      cancelled = true;
      if (timer !== null) clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisChange);
    };
  }, [isActiveLobby, prefs.nearbyDiscoverable, profile.clientId, profile.name, profile.avatar]);

  const findStoredOwnerToken = (code: string): string | undefined => {
    return (
      savedFixedRooms.find((r) => r.code === code)?.ownerToken ||
      recentRooms.find((r) => r.code === code)?.ownerToken
    );
  };

  const probeRoomInput = async (raw: string) => {
    const code = extractRoomCodeInput(raw);
    if (!code) {
      setNeedPassword(false);
      return;
    }
    try {
      const info = await fetchRoomInfo(code);
      setNeedPassword(info.hasPassword);
      setErrorText('');
    } catch (err) {
      if (err instanceof ApiError && err.errorCode === 'ROOM_NOT_FOUND') {
        setNeedPassword(false);
        setErrorText('');
      } else if (err instanceof ApiError) {
        setErrorText(err.message);
      }
    }
  };

  const handleModeChange = (nextMode: RoomMode) => {
    setMode(nextMode);
    setErrorText('');
    if (nextMode === 'ephemeral' && retentionDays === 3) setRetentionDays(1);
    else if (nextMode === 'fixed' && retentionDays === 1) setRetentionDays(3);
  };

  const createQuickEphemeralRoom = async () => {
    if (submitting || randomJoining) return;
    setErrorText('');
    const cleanPwd = roomPassword.trim();
    const maxLimit = serverConfig?.maxRoomPeers || 6;
    setSubmitting(true);
    try {
      const summary = await createRoom({
        mode,
        password: cleanPwd || undefined,
        allowRandom: cleanPwd ? false : allowRandom,
        maxPeers: privatePairOnly ? 2 : maxLimit,
        retentionDays,
        maxFileSizeMB,
      });
      onEnterRoom(
        summary.code,
        cleanPwd,
        summary.mode,
        mode === 'fixed' && saveToFavorites,
        summary.ownerToken
      );
    } catch (err) {
      setErrorText(err instanceof Error ? err.message : '创建房间失败');
    } finally {
      setSubmitting(false);
    }
  };

  // 单入口智能直达：留空直接创建房间；填口令/链接时先探测是否存在
  const handleUnifiedSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorText('');
    const code = extractRoomCodeInput(roomInput);
    const cleanPwd = roomPassword.trim();

    if (!code) {
      await createQuickEphemeralRoom();
      return;
    }

    setSubmitting(true);
    try {
      let existing = null;
      try {
        existing = await fetchRoomInfo(code);
      } catch (err) {
        if (
          err instanceof ApiError &&
          (err.errorCode === 'ROOM_NOT_FOUND' || err.errorCode === 'ROOM_EXPIRED')
        ) {
          existing = null;
        } else {
          throw err;
        }
      }

      if (existing) {
        if (existing.hasPassword) {
          if (!cleanPwd) {
            setNeedPassword(true);
            setErrorText(`房间 #${code} 设有访问密码，请输入密码后进入`);
            return;
          }
          await verifyRoomPassword(code, cleanPwd);
        }
        onEnterRoom(
          existing.code,
          cleanPwd,
          existing.mode,
          existing.mode === 'fixed',
          findStoredOwnerToken(existing.code)
        );
      } else if (/^\d{6}$/.test(code)) {
        setErrorText('临时房间已结束或口令有误');
      } else {
        setConfirmCreateCode(code);
      }
    } catch (err) {
      if (err instanceof ApiError && err.errorCode === 'WRONG_PASSWORD') {
        setNeedPassword(true);
      }
      setErrorText(err instanceof Error ? err.message : '进入房间失败');
    } finally {
      setSubmitting(false);
    }
  };

  const handleConfirmCreateFixedRoom = async () => {
    const code = confirmCreateCode;
    if (!code) return;
    setConfirmCreateCode(null);
    const cleanPwd = roomPassword.trim();
    const maxLimit = serverConfig?.maxRoomPeers || 6;
    setSubmitting(true);
    try {
      const summary = await createRoom({
        mode: 'fixed',
        code,
        password: cleanPwd || undefined,
        allowRandom: cleanPwd ? false : allowRandom,
        maxPeers: privatePairOnly ? 2 : maxLimit,
        retentionDays,
        maxFileSizeMB,
      });
      onToast(`已创建常驻存储房间 #${summary.code}`);
      onEnterRoom(summary.code, cleanPwd, summary.mode, saveToFavorites, summary.ownerToken);
    } catch (err) {
      setErrorText(err instanceof Error ? err.message : '创建常驻房间失败');
    } finally {
      setSubmitting(false);
    }
  };

  const handleInviteNearbyDevice = async (dev: NearbyDevice) => {
    if (invitingId) return;
    setInvitingId(dev.clientId);
    try {
      const res = await sendNearbyInvite({
        fromClientId: profile.clientId,
        toClientId: dev.clientId,
        fromName: profile.name,
        fromAvatar: profile.avatar,
      });
      onToast(`已向 ${dev.name} 发送传输邀请`);
      onEnterRoom(res.room.code, '', 'ephemeral', false, res.ownerToken, {
        inviteId: res.inviteId,
        targetName: dev.name,
      });
    } catch (err) {
      onToast(err instanceof Error ? err.message : '发起附近邀请失败');
    } finally {
      setInvitingId(null);
    }
  };

  const handleRespondIncomingInvite = async (accept: boolean) => {
    if (!incomingInvite) return;
    const inv = incomingInvite;
    handledInviteIdsRef.current.add(inv.inviteId);
    setIncomingInvite(null);
    try {
      const res = await respondNearbyInvite(inv.inviteId, {
        clientId: profile.clientId,
        accept,
      });
      if (accept && res.roomCode) {
        onToast(`已接受 ${inv.fromName} 的邀请，正在进入房间`);
        onEnterRoom(res.roomCode, '', 'ephemeral', false);
      } else if (!accept) {
        onToast(`已婉拒 ${inv.fromName} 的传输邀请`);
      }
    } catch (err) {
      onToast(err instanceof Error ? err.message : '邀请已过期或失效');
    }
  };

  const handleRandomJoin = async () => {
    setErrorText('');
    setRandomJoining(true);
    try {
      const target = await pickRandomPublicRoom();
      onToast(`已进入公开房间 #${target.code}`);
      onEnterRoom(target.code, '', target.mode, false);
    } catch (err) {
      setErrorText(err instanceof Error ? err.message : '当前暂无可随机进入的免密公开房间');
    } finally {
      setRandomJoining(false);
    }
  };

  const handleQuickEnterSaved = async (item: SavedFixedRoom) => {
    setErrorText('');
    try {
      const info = await fetchRoomInfo(item.code);
      if (info.hasPassword && !item.password) {
        setRoomInput(item.code);
        setNeedPassword(true);
        setErrorText('请输入该常驻房间的密码');
        return;
      }
      if (info.hasPassword && item.password) {
        await verifyRoomPassword(item.code, item.password);
      }
      onEnterRoom(item.code, item.password || '', 'fixed', true, item.ownerToken);
    } catch (err) {
      setRoomInput(item.code);
      if (err instanceof ApiError && err.errorCode === 'WRONG_PASSWORD') {
        setNeedPassword(true);
      }
      setErrorText(err instanceof Error ? err.message : '无法进入房间');
    }
  };

  const handleQuickEnterRecent = async (item: RecentRoom) => {
    setErrorText('');
    try {
      const info = await fetchRoomInfo(item.code);
      if (info.hasPassword && !item.password) {
        setRoomInput(item.code);
        setNeedPassword(true);
        setErrorText('请输入该房间的访问密码');
        return;
      }
      if (info.hasPassword && item.password) {
        await verifyRoomPassword(item.code, item.password);
      }
      onEnterRoom(item.code, item.password || '', info.mode, false, item.ownerToken);
    } catch (err) {
      if (
        err instanceof ApiError &&
        (err.errorCode === 'ROOM_NOT_FOUND' || err.errorCode === 'ROOM_EXPIRED')
      ) {
        onRemoveRecentRoom(item.code);
        onToast(`临时房间 #${item.code} 已销毁或过期，已从最近记录移除`);
        return;
      }
      setRoomInput(item.code);
      if (err instanceof ApiError && err.errorCode === 'WRONG_PASSWORD') {
        setNeedPassword(true);
      }
      setErrorText(err instanceof Error ? err.message : '无法进入该房间');
    }
  };

  const handleJoinFromPlane = async (plane: PaperPlane) => {
    if (joiningPlane) return;
    setJoiningPlane(true);
    try {
      const res = await joinPlane(plane.id);
      let resolvedMode: RoomMode = /^\d{6}$/.test(res.roomCode) ? 'ephemeral' : 'fixed';
      try {
        const info = await fetchRoomInfo(res.roomCode);
        resolvedMode = info.mode;
      } catch {
        // 保留格式推断模式
      }
      setViewingPlane(null);
      onEnterRoom(res.roomCode, res.password || '', resolvedMode, false);
    } catch (err) {
      if (err instanceof ApiError && err.errorCode === 'PLANE_ENTRY_EXPIRED') {
        // 房主改密后免密失效：更新本地卡片状态并引导手动输密进入
        setPlanes((prev) =>
          prev.map((item) => (item.id === plane.id ? { ...item, allowEntry: false } : item))
        );
        setViewingPlane(null);
        setRoomInput(plane.roomCode);
        setRoomPassword('');
        setNeedPassword(true);
        setErrorText('房主已修改密码，请输入密码进入');
        onToast('房主已修改密码，请输入密码进入');
        window.setTimeout(() => passwordInputRef.current?.focus(), 60);
      } else if (
        err instanceof ApiError &&
        (err.errorCode === 'PLANE_NOT_FOUND' || err.errorCode === 'PLANE_EXPIRED')
      ) {
        setPlanes((prev) => prev.filter((item) => item.id !== plane.id));
        setViewingPlane(null);
        onToast(err.message);
      } else {
        onToast(err instanceof Error ? err.message : '通过纸飞机进入房间失败');
      }
    } finally {
      setJoiningPlane(false);
    }
  };

  const handleReportPlane = async (plane: PaperPlane) => {
    try {
      const res = await reportPlane(plane.id, profile.clientId);
      if (res.deleted) {
        setPlanes((prev) => prev.filter((item) => item.id !== plane.id));
        setViewingPlane(null);
        onToast('该纸飞机已被多名用户举报并移除');
      } else {
        onToast('已收到举报，系统将在累计达到阈值后自动清理');
        setViewingPlane(null);
      }
    } catch (err) {
      onToast(err instanceof Error ? err.message : '举报提交失败');
    }
  };

  const handlePasteClipboard = async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (text) {
        setRoomInput(text.trim());
        void probeRoomInput(text);
      }
    } catch {
      onToast('请直接在输入框内按 Ctrl+V 粘贴');
    }
  };

  const hasInputCode = roomInput.trim().length > 0;
  const hasPasswordInput = roomPassword.trim().length > 0;
  const summaryTag = `${mode === 'fixed' || hasInputCode ? '常驻' : '临时'}·${retentionDays === 0 ? '即焚' : `${retentionDays}天`}·${maxFileSizeMB === 0 ? '不限' : maxFileSizeMB >= 1024 ? `${(maxFileSizeMB / 1024).toFixed(0)}GB` : `${maxFileSizeMB}MB`}`;
  const domainDisplay = serverConfig?.domain || window.location.host;

  return (
    <div className="lobby-shell">
      {/* 极简 60px 顶部栏 */}
      <header className="top-glass-nav lobby-top-nav">
        <div className="brand-pill">
          <div className="brand-jelly-logo">
            <HardDrive size={18} aria-hidden="true" />
          </div>
          <div className="brand-text-wrap">
            <div className="brand-title-text">湾聊 · BayChat</div>
            <div className="brand-subtitle">{domainDisplay}</div>
          </div>
        </div>

        <div className="lobby-nav-actions">
          {/* 设置浮层按钮（包含浅深外观 + 5色主题 + 接收偏好 + 附近发现） */}
          <div className="popover-anchor" onClick={(e) => e.stopPropagation()}>
            <button
              type="button"
              className={`btn btn-sm lobby-touch-pill ${settingsOpen ? 'btn-jelly' : 'btn-glass'}`}
              onClick={() => {
                setSettingsOpen((v) => !v);
                setUserMenuOpen(false);
              }}
              aria-expanded={settingsOpen}
              aria-label="打开外观与偏好设置"
            >
              <Settings2 size={15} aria-hidden="true" />
              <span className="hide-mobile">设置</span>
            </button>

            {settingsOpen && (
              <SettingsPopover
                appearance={appearance}
                theme={theme}
                prefs={prefs}
                onSelectAppearance={onSelectAppearance}
                onSelectTheme={onSelectTheme}
                onUpdatePrefs={onUpdatePrefs}
                onClose={() => setSettingsOpen(false)}
                extraSection={
                  <Switch
                    checked={prefs.nearbyDiscoverable}
                    onChange={(v) => onUpdatePrefs({ ...prefs, nearbyDiscoverable: v })}
                    label="允许附近设备发现我"
                    description="同局域网打开大厅可互相看到"
                  />
                }
              />
            )}
          </div>

          {/* 精简用户菜单（仅保留 1 处头像） */}
          <div className="popover-anchor" onClick={(e) => e.stopPropagation()}>
            <button
              type="button"
              className="user-chip lobby-touch-pill"
              onClick={() => {
                setUserMenuOpen((v) => !v);
                setSettingsOpen(false);
              }}
              aria-expanded={userMenuOpen}
              aria-label={`当前身份：${profile.name}`}
            >
              <Avatar name={profile.name} avatar={profile.avatar} size={28} />
              <span className="hide-mobile lobby-user-name">{profile.name}</span>
              <ChevronDown size={13} className="hide-mobile" aria-hidden="true" />
            </button>

            {userMenuOpen && (
              <div className="floating-popover lobby-user-popover" role="menu">
                <div className="lobby-user-pop-head">
                  <Avatar name={profile.name} avatar={profile.avatar} size={40} />
                  <div className="lobby-profile-info">
                    <div className="lobby-profile-name">{profile.name}</div>
                    <div className="lobby-profile-sub">免注册本机身份</div>
                  </div>
                </div>
                <button
                  type="button"
                  className="btn btn-soft btn-sm lobby-menu-btn"
                  onClick={() => {
                    setUserMenuOpen(false);
                    onEditProfile();
                  }}
                >
                  <UserRound size={14} aria-hidden="true" /> 编辑个人名片
                </button>
                <button
                  type="button"
                  className="btn btn-glass btn-sm lobby-menu-btn"
                  onClick={() => {
                    setUserMenuOpen(false);
                    onQuickRandomProfile({
                      ...profile,
                      name: generateRandomName(),
                      avatar: pickRandomPresetAvatar(),
                    });
                  }}
                >
                  <Dices size={14} aria-hidden="true" /> 随机换一个身份
                </button>
              </div>
            )}
          </div>
        </div>
      </header>

      {/* 主体居中画布（max-width: 1120px） */}
      <main className="lobby-scroll">
        <div className="lobby-canvas">
          {/* 1. Hero 聚焦区：直接铺在背景上，强调以房间文件存取为中心 */}
          <section className="lobby-hero-section">
            <div className="lobby-hero-header">
              <h1 className="lobby-hero-display">文件存进房间，随时随地取</h1>
              <p className="lobby-hero-sub">
                房间文件按 1~7 天保鲜期保存或阅后即焚，支持单文件大体积跨端 P2P 直传、6 位取件码暂存与多设备收藏重连
              </p>
            </div>

            <form onSubmit={handleUnifiedSubmit} className="lobby-hero-bar">
              <div className="lobby-bar-row">
                <div className="input-group lobby-bar-input-wrap">
                  <input
                    ref={codeInputRef}
                    className="field-input with-suffix mono lobby-code-input"
                    value={roomInput}
                    onChange={(e) => {
                      setRoomInput(e.target.value);
                      setErrorText('');
                      if (!e.target.value.trim()) setNeedPassword(false);
                    }}
                    onBlur={() => void probeRoomInput(roomInput)}
                    placeholder="留空直接新建存储房间，或输入 849205 / bay-8888"
                    aria-label="房间口令或邀请链接"
                  />
                  <div className="input-suffix lobby-input-suffix">
                    {roomInput && (
                      <button
                        type="button"
                        className="btn btn-ghost btn-sm lobby-clear-btn"
                        onClick={() => {
                          setRoomInput('');
                          setNeedPassword(false);
                          setErrorText('');
                        }}
                        title="清空"
                        aria-label="清空房间口令"
                      >
                        <X size={14} aria-hidden="true" />
                      </button>
                    )}
                    <button
                      type="button"
                      className="btn btn-soft btn-sm lobby-touch-pill"
                      onClick={() => void handlePasteClipboard()}
                    >
                      <ClipboardPaste size={13} aria-hidden="true" /> 粘贴
                    </button>
                  </div>
                </div>

                <div className="lobby-bar-actions">
                  <button
                    type="submit"
                    className="btn btn-jelly btn-xl lobby-cta-primary"
                    disabled={submitting || randomJoining}
                  >
                    <span>
                      {submitting
                        ? '正在进入...'
                        : hasInputCode
                          ? '进入指定房间'
                          : mode === 'fixed'
                            ? '创建常驻房间'
                            : '创建临时房间'}
                    </span>
                    <ArrowRight size={17} aria-hidden="true" />
                  </button>
                  <button
                    type="button"
                    className="btn btn-soft btn-xl"
                    disabled={submitting || randomJoining}
                    onClick={() => void handleRandomJoin()}
                    title="随机加入免密开放房间"
                  >
                    <Dices size={17} aria-hidden="true" />
                    <span>{randomJoining ? '匹配中...' : '随机'}</span>
                  </button>
                </div>
              </div>

              {(needPassword || (!showAdvanced && hasPasswordInput)) && (
                <div className="lobby-field-group lobby-pwd-inline">
                  <span className="lobby-field-label">访问密码</span>
                  <div className="input-group">
                    <span className="input-prefix-icon">
                      <Lock size={15} aria-hidden="true" />
                    </span>
                    <input
                      ref={passwordInputRef}
                      className="field-input with-prefix"
                      type="password"
                      value={roomPassword}
                      onChange={(e) => {
                        setRoomPassword(e.target.value);
                        setErrorText('');
                      }}
                      placeholder="输入该房间的访问密码..."
                      autoFocus
                    />
                  </div>
                </div>
              )}

              {errorText && (
                <div className="alert-strip">
                  <AlertCircle size={15} aria-hidden="true" />
                  <span>{errorText}</span>
                </div>
              )}

              <div className="lobby-control-strip">
                <button
                  type="button"
                  className={`btn btn-sm lobby-touch-pill ${!hasPasswordInput && allowRandom ? 'btn-soft' : 'btn-glass'}`}
                  disabled={hasPasswordInput}
                  onClick={() => {
                    const next = !allowRandom;
                    setAllowRandom(next);
                    onToast(next ? '已允许随机加入' : '仅限凭口令进入');
                  }}
                >
                  {!hasPasswordInput && allowRandom ? (
                    <Globe size={13} aria-hidden="true" />
                  ) : (
                    <Lock size={13} aria-hidden="true" />
                  )}
                  <span>
                    {hasPasswordInput
                      ? '已设访问密码'
                      : allowRandom
                        ? '允许随机加入'
                        : '仅限口令进入'}
                  </span>
                </button>

                <button
                  type="button"
                  className={`btn btn-ghost btn-sm lobby-touch-pill lobby-advanced-toggle ${showAdvanced ? 'open' : ''}`}
                  onClick={() => setShowAdvanced((prev) => !prev)}
                  aria-expanded={showAdvanced}
                >
                  <SlidersHorizontal size={13} aria-hidden="true" />
                  <span>保留期与上限设置</span>
                  {!showAdvanced && <span className="lobby-advanced-summary">({summaryTag})</span>}
                  {showAdvanced ? (
                    <ChevronUp size={14} aria-hidden="true" />
                  ) : (
                    <ChevronDown size={14} aria-hidden="true" />
                  )}
                </button>
              </div>

              {showAdvanced && (
                <div className="lobby-advanced-panel">
                  <div className="candy-mode-grid">
                    <button
                      type="button"
                      className={`candy-card ${mode === 'ephemeral' ? 'selected' : ''}`}
                      onClick={() => handleModeChange('ephemeral')}
                    >
                      <div className="candy-badge-icon candy-badge-ephemeral">
                        <Flame size={17} aria-hidden="true" />
                      </div>
                      <div className="candy-card-title">临时房间 · 离开自动销毁</div>
                      <div className="candy-card-desc">
                        6 位随机口令，会话结束或过期后自动清理缓存
                      </div>
                    </button>

                    <button
                      type="button"
                      className={`candy-card ${mode === 'fixed' ? 'selected' : ''}`}
                      onClick={() => handleModeChange('fixed')}
                    >
                      <div className="candy-badge-icon candy-badge-fixed">
                        <Pin size={17} aria-hidden="true" />
                      </div>
                      <div className="candy-card-title">常驻房间 · 长期保留收藏</div>
                      <div className="candy-card-desc">
                        固定房间口令，多设备按保鲜期持续存取文件
                      </div>
                    </button>
                  </div>

                  <div className="lobby-field-group">
                    <div className="lobby-field-head">
                      <span>服务器文件与记录保鲜期（最长 7 天）</span>
                      <span className="lobby-field-hint">
                        {retentionDays === 0 ? '服务器零留存' : `活跃起 ${retentionDays} 天后自动清空`}
                      </span>
                    </div>
                    <div className="segmented full seg-mobile-grid">
                      {RETENTION_STEPS.map((st) => {
                        const active = retentionDays === st.days;
                        const cls = active
                          ? st.days === 0
                            ? 'seg-item danger-active'
                            : 'seg-item active'
                          : 'seg-item';
                        return (
                          <button
                            key={st.days}
                            type="button"
                            className={cls}
                            onClick={() => setRetentionDays(st.days)}
                          >
                            {st.label}
                          </button>
                        );
                      })}
                    </div>
                  </div>

                  <div className="lobby-field-group">
                    <div className="lobby-field-head">
                      <span>单文件传输上限</span>
                      <span className="lobby-field-hint">
                        {maxFileSizeMB === 0
                          ? '不限制（流式直传大文件）'
                          : `单文件 ≤ ${maxFileSizeMB >= 1024 ? `${(maxFileSizeMB / 1024).toFixed(maxFileSizeMB % 1024 === 0 ? 0 : 1)} GB` : `${maxFileSizeMB} MB`}`}
                      </span>
                    </div>
                    <div className="segmented full seg-mobile-grid">
                      {FILE_LIMIT_PRESETS.filter((p) => {
                        const cap = serverConfig?.maxAttachmentMB || 0;
                        if (cap <= 0) return true;
                        return p.mb > 0 && p.mb <= cap;
                      }).map((p) => (
                        <button
                          key={p.mb}
                          type="button"
                          className={`seg-item ${maxFileSizeMB === p.mb ? 'active' : ''}`}
                          onClick={() => setMaxFileSizeMB(p.mb)}
                        >
                          {p.label}
                        </button>
                      ))}
                    </div>
                  </div>

                  <div className="lobby-two-col">
                    <div className="lobby-field-group">
                      <span className="lobby-field-label">房间容量</span>
                      <div className="segmented full lobby-capacity-seg">
                        <button
                          type="button"
                          className={`seg-item ${privatePairOnly ? 'active' : ''}`}
                          onClick={() => setPrivatePairOnly(true)}
                        >
                          1v1 双人私密
                        </button>
                        <button
                          type="button"
                          className={`seg-item ${!privatePairOnly ? 'active' : ''}`}
                          onClick={() => setPrivatePairOnly(false)}
                        >
                          小队 ({serverConfig?.maxRoomPeers || 6}人)
                        </button>
                      </div>
                    </div>

                    <div className="lobby-field-group">
                      <span className="lobby-field-label">访问密码（可选）</span>
                      <div className="input-group">
                        <span className="input-prefix-icon">
                          <Lock size={15} aria-hidden="true" />
                        </span>
                        <input
                          className="field-input with-prefix"
                          type="password"
                          value={roomPassword}
                          onChange={(e) => setRoomPassword(e.target.value)}
                          placeholder="留空免密；设密码禁随机"
                        />
                      </div>
                    </div>
                  </div>

                  {(mode === 'fixed' || hasInputCode) && (
                    <Switch
                      checked={saveToFavorites}
                      onChange={setSaveToFavorites}
                      label="收藏到「常驻收藏」"
                      description="下次打开首页可直接点击重进"
                    />
                  )}
                </div>
              )}
            </form>
          </section>

          {/* 2. 四格快捷操作区：以存储与取件主路径优先 */}
          <section className="lobby-quick-grid" aria-label="核心存储与直传快捷操作">
            <button
              type="button"
              className="lobby-quick-tile primary-tint"
              onClick={() => setPickupSendOpen(true)}
            >
              <span className="lobby-quick-icon accent">
                <Package size={18} aria-hidden="true" />
              </span>
              <span className="lobby-quick-title">发送文件</span>
              <span className="lobby-quick-sub">生成 6 位取件码离线存传</span>
            </button>

            <button
              type="button"
              className="lobby-quick-tile"
              onClick={() => setPickupLookupOpen(true)}
            >
              <span className="lobby-quick-icon">
                <KeyRound size={18} aria-hidden="true" />
              </span>
              <span className="lobby-quick-title">输入取件码</span>
              <span className="lobby-quick-sub">凭 6 位取件码提取文件</span>
            </button>

            <button
              type="button"
              className="lobby-quick-tile"
              onClick={() => {
                if (!roomInput.trim()) {
                  void createQuickEphemeralRoom();
                } else {
                  codeInputRef.current?.focus();
                }
              }}
            >
              <span className="lobby-quick-icon">
                <HardDrive size={18} aria-hidden="true" />
              </span>
              <span className="lobby-quick-title">新建房间</span>
              <span className="lobby-quick-sub">建房存文件，支持 1~7 天保鲜</span>
            </button>

            <button
              type="button"
              className="lobby-quick-tile"
              onClick={() => {
                if (!prefs.nearbyDiscoverable) {
                  onUpdatePrefs({ ...prefs, nearbyDiscoverable: true });
                }
                nearbySectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
              }}
            >
              <span className="lobby-quick-icon">
                <Radio size={18} aria-hidden="true" />
              </span>
              <span className="lobby-quick-title">
                附近设备 {nearbyDevices.length > 0 ? `(${nearbyDevices.length})` : ''}
              </span>
              <span className="lobby-quick-sub">同局域网免输码直接传输</span>
            </button>
          </section>

          {/* 3. 下方两列主区：左列我的房间（最近/收藏），右列取件码主卡 + 附近设备 */}
          <section className="lobby-main-grid">
            {/* 左列：房间仓库（分段切换 最近 / 收藏） */}
            <div className="glass-bento lobby-rooms-card">
              <div className="lobby-card-head">
                <span className="lobby-card-title">
                  <Clock size={15} className="lobby-card-title-icon" aria-hidden="true" />
                  <span>我的房间</span>
                </span>
                <div className="lobby-card-actions">
                  <div className="segmented lobby-mini-seg" role="tablist" aria-label="房间列表分类">
                    <button
                      type="button"
                      role="tab"
                      aria-selected={roomTab === 'recent'}
                      className={`seg-item ${roomTab === 'recent' ? 'active' : ''}`}
                      onClick={() => setRoomTab('recent')}
                    >
                      最近 ({recentRooms.length})
                    </button>
                    <button
                      type="button"
                      role="tab"
                      aria-selected={roomTab === 'saved'}
                      className={`seg-item ${roomTab === 'saved' ? 'active' : ''}`}
                      onClick={() => setRoomTab('saved')}
                    >
                      收藏 ({savedFixedRooms.length})
                    </button>
                  </div>
                  {roomTab === 'recent' && recentRooms.length > 0 && (
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm lobby-clear-recent-btn"
                      onClick={onClearRecentRooms}
                    >
                      清空
                    </button>
                  )}
                </div>
              </div>

              {roomTab === 'recent' ? (
                recentRooms.length === 0 ? (
                  <div className="lobby-empty-card">
                    <div className="lobby-empty-title">
                      <Clock size={14} className="lobby-card-title-icon" aria-hidden="true" />
                      <span>暂无最近进入的房间</span>
                    </div>
                    <div className="lobby-empty-desc">
                      新建或加入任意房间后会自动记录在此处，方便随时回到原房间查看历史消息与已存文件。
                    </div>
                  </div>
                ) : (
                  <div className="lobby-room-list">
                    {recentRooms.slice(0, 6).map((item) => (
                      <div key={item.code} className="lobby-room-item">
                        <div className="lobby-room-meta">
                          <div className="mono lobby-room-code">
                            <span>#{item.code}</span>
                            {item.hasPassword && (
                              <Lock size={11} className="lobby-room-lock" aria-hidden="true" />
                            )}
                          </div>
                          <div className="lobby-room-sub">
                            <span className="lobby-inline-tag">
                              {item.mode === 'fixed' ? (
                                <Pin size={10} aria-hidden="true" />
                              ) : (
                                <Flame size={10} aria-hidden="true" />
                              )}
                              <span>{item.mode === 'fixed' ? '常驻' : '临时'}</span>
                            </span>
                            <span>· {formatRelativeTime(item.lastVisitedAt)}</span>
                          </div>
                        </div>
                        <div className="lobby-room-actions">
                          <button
                            type="button"
                            className="btn btn-soft btn-sm lobby-touch-pill"
                            onClick={() => void handleQuickEnterRecent(item)}
                          >
                            重进
                          </button>
                          <button
                            type="button"
                            className="btn btn-ghost btn-icon lobby-touch-icon"
                            title="复制邀请链接"
                            aria-label={`复制房间 ${item.code} 的邀请链接`}
                            onClick={async () => {
                              const url = `${window.location.origin}/?room=${encodeURIComponent(item.code)}`;
                              if (await copyTextToClipboard(url)) onToast(`已复制 #${item.code} 链接`);
                            }}
                          >
                            <Copy size={13} aria-hidden="true" />
                          </button>
                          <button
                            type="button"
                            className="btn btn-ghost btn-icon lobby-touch-icon"
                            title="移除记录"
                            aria-label={`移除房间 ${item.code}`}
                            onClick={() => onRemoveRecentRoom(item.code)}
                          >
                            <Trash2 size={13} aria-hidden="true" />
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                )
              ) : savedFixedRooms.length === 0 ? (
                <div className="lobby-empty-card">
                  <div className="lobby-empty-title">
                    <Pin size={14} className="lobby-card-title-icon" aria-hidden="true" />
                    <span>暂无常驻收藏房间</span>
                  </div>
                  <div className="lobby-empty-desc">
                    展开上方「保留期与上限设置」选择常驻房间并勾选收藏，即可固定在此处方便多设备取用文件。
                  </div>
                </div>
              ) : (
                <div className="lobby-room-list">
                  {savedFixedRooms.map((item) => (
                    <div key={item.code} className="lobby-room-item">
                      <div className="lobby-room-meta">
                        <div className="mono lobby-room-code">
                          <span>#{item.code}</span>
                          {item.hasPassword && (
                            <Lock size={12} className="lobby-room-lock" aria-hidden="true" />
                          )}
                        </div>
                        <div className="lobby-room-sub">
                          <span>常驻直连通道 · {formatRelativeTime(item.lastVisitedAt)}</span>
                        </div>
                      </div>
                      <div className="lobby-room-actions">
                        <button
                          type="button"
                          className="btn btn-jelly btn-sm lobby-touch-pill"
                          onClick={() => void handleQuickEnterSaved(item)}
                        >
                          进入
                        </button>
                        <button
                          type="button"
                          className="btn btn-ghost btn-icon lobby-touch-icon"
                          title="复制邀请链接"
                          aria-label={`复制常驻房间 ${item.code} 的邀请链接`}
                          onClick={async () => {
                            const url = `${window.location.origin}/?room=${encodeURIComponent(item.code)}`;
                            if (await copyTextToClipboard(url)) onToast(`已复制 #${item.code} 链接`);
                          }}
                        >
                          <Copy size={13} aria-hidden="true" />
                        </button>
                        <button
                          type="button"
                          className="btn btn-ghost btn-icon lobby-touch-icon"
                          title="取消收藏"
                          aria-label={`取消收藏房间 ${item.code}`}
                          onClick={() => onRemoveSavedRoom(item.code)}
                        >
                          <Trash2 size={13} aria-hidden="true" />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* 右列：取件码主卡 + 附近设备卡 */}
            <div className="lobby-right-stack">
              {/* 离线取件码主卡 */}
              <div className="glass-bento lobby-pickup-main-card">
                <div className="lobby-card-head">
                  <span className="lobby-card-title">
                    <Package size={16} className="lobby-card-title-icon" aria-hidden="true" />
                    <span>取件码 · 离线文件暂存</span>
                  </span>
                  <span className="pill pill-accent">6 位数字取件</span>
                </div>
                <p className="lobby-pickup-desc">
                  接收方不在线也能存：上传文件生成 6 位取件码，支持设置提取密码与取后即焚自动清理，打开链接或输入取件码即可下载。
                </p>
                <div className="lobby-two-btn-grid">
                  <button
                    type="button"
                    className="btn btn-jelly lobby-touch-pill"
                    onClick={() => setPickupSendOpen(true)}
                  >
                    <Package size={14} aria-hidden="true" /> 上传发文件
                  </button>
                  <button
                    type="button"
                    className="btn btn-soft lobby-touch-pill"
                    onClick={() => setPickupLookupOpen(true)}
                  >
                    <KeyRound size={14} aria-hidden="true" /> 输入取件码
                  </button>
                </div>
              </div>

              {/* 附近同局域网设备发现卡 */}
              <div className="glass-bento lobby-nearby-main-card" ref={nearbySectionRef}>
                <div className="lobby-card-head">
                  <span className="lobby-card-title">
                    <Radio size={15} className="lobby-card-title-icon" aria-hidden="true" />
                    <span>附近设备（同局域网免输码）</span>
                  </span>
                  <span className="pill pill-neutral mono">{nearbyDevices.length}</span>
                </div>
                <Switch
                  checked={prefs.nearbyDiscoverable}
                  onChange={(v) => onUpdatePrefs({ ...prefs, nearbyDiscoverable: v })}
                  label="允许附近设备发现我"
                  description="仅在大厅前台运行时生效，点击设备直接发起传输"
                />
                {prefs.nearbyDiscoverable && (
                  <div className="lobby-nearby-box">
                    {nearbyDevices.length === 0 ? (
                      <div className="lobby-nearby-empty">
                        <Radio size={15} className="lobby-card-title-icon" aria-hidden="true" />
                        <span>正在扫描同一网络下的湾聊设备（手机或电脑打开大厅即可互相发现）...</span>
                      </div>
                    ) : (
                      <div className="lobby-nearby-strip">
                        {nearbyDevices.map((dev) => (
                          <button
                            key={dev.clientId}
                            type="button"
                            className="btn btn-glass lobby-nearby-card"
                            disabled={invitingId === dev.clientId}
                            onClick={() => void handleInviteNearbyDevice(dev)}
                            title={`点击向 ${dev.name} 发送传输邀请`}
                          >
                            <Avatar name={dev.name} avatar={dev.avatar} size={36} />
                            <span className="lobby-nearby-name">{dev.name}</span>
                            <span className="lobby-nearby-plat">
                              {dev.device === 'android' ? (
                                <Smartphone size={10} aria-hidden="true" />
                              ) : (
                                <Laptop size={10} aria-hidden="true" />
                              )}
                              {invitingId === dev.clientId
                                ? '邀请中'
                                : dev.device === 'android'
                                  ? 'Android'
                                  : 'Web'}
                            </span>
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          </section>

          {/* 4. 次级区块：纸飞机 · 公开的文件分享（置于房间与取件区下方） */}
          <section className="lobby-planes-section" aria-label="纸飞机公开文件分享">
            <div className="lobby-planes-head">
              <div className="lobby-planes-title-group">
                <h2 className="lobby-planes-title">
                  <Send size={15} className="lobby-card-title-icon" aria-hidden="true" />
                  <span>纸飞机 · 公开的文件分享</span>
                </h2>
                <span className="lobby-planes-sub">
                  仅展示房间内公开的文件清单与留言（24 小时有效），点击卡片可进房下载
                </span>
              </div>
              <button
                type="button"
                className="btn btn-ghost btn-sm lobby-touch-pill"
                onClick={() => void loadPlanes()}
                disabled={planesLoading}
              >
                <RefreshCw size={13} className={planesLoading ? 'bay-spin' : ''} aria-hidden="true" />
                <span>刷新</span>
              </button>
            </div>

            {planesLoading && planes.length === 0 ? (
              <div className="lobby-planes-grid">
                {[0, 1, 2].map((idx) => (
                  <div key={idx} className="plane-skeleton-card" />
                ))}
              </div>
            ) : planesError ? (
              <div className="lobby-plane-light-note">
                <span>{planesError}</span>
                <button
                  type="button"
                  className="btn btn-soft btn-sm"
                  onClick={() => void loadPlanes()}
                >
                  重试
                </button>
              </div>
            ) : planes.length === 0 ? (
              <div className="lobby-plane-light-note">
                当前广场暂无公开文件纸飞机；进入任意房间后，可在右上角「更多」菜单点击「放飞纸飞机（分享房间文件）」。
              </div>
            ) : (
              <div className="lobby-planes-grid">
                {planes.map((plane) => (
                  <button
                    key={plane.id}
                    type="button"
                    className="lobby-plane-card"
                    onClick={() => setViewingPlane(plane)}
                  >
                    {/* 文件清单优先放在卡片视觉核心位 */}
                    {plane.files.length > 0 ? (
                      <div className="plane-card-files">
                        {plane.files.slice(0, 3).map((file, idx) => (
                          <div key={`${file.name}-${idx}`} className="plane-card-file-row">
                            <span className="plane-card-file-icon">
                              {renderPlaneKindIcon(file.kind, 15)}
                            </span>
                            <span className="plane-card-file-name">{file.name}</span>
                            {file.size > 0 && (
                              <span className="mono plane-card-file-size">
                                {formatBytes(file.size)}
                              </span>
                            )}
                          </div>
                        ))}
                        {plane.files.length > 3 && (
                          <div className="plane-card-file-more">
                            另外还有 {plane.files.length - 3} 个文件
                          </div>
                        )}
                      </div>
                    ) : (
                      <div className="plane-card-text-only">
                        <span className="mono">#{plane.roomCode}</span>
                        <span>房间邀请</span>
                      </div>
                    )}

                    {/* 留言作为次要说明文字 */}
                    {plane.message && (
                      <div className="plane-card-message">{plane.message}</div>
                    )}

                    {/* 卡片底部：放飞者与入房权限标签 */}
                    <div className="plane-card-foot">
                      <div className="plane-card-sender">
                        <Avatar name={plane.senderName} avatar={plane.senderAvatar} size={22} />
                        <span className="plane-card-sender-name">{plane.senderName}</span>
                        <span className="plane-card-time">· {formatRelativeTime(plane.createdAt)}</span>
                      </div>

                      {!plane.allowEntry ? (
                        <span className="pill pill-neutral plane-card-badge">仅展示</span>
                      ) : plane.hasPassword ? (
                        <span className="pill pill-accent plane-card-badge">
                          <KeyRound size={11} aria-hidden="true" /> 免密进入
                        </span>
                      ) : (
                        <span className="pill pill-emerald plane-card-badge">可直接加入</span>
                      )}
                    </div>
                  </button>
                ))}
              </div>
            )}
          </section>
        </div>
      </main>

      {/* 不存在常驻房时创建确认弹窗 */}
      {confirmCreateCode && (
        <div className="modal-overlay" onClick={() => setConfirmCreateCode(null)}>
          <div
            className="modal-sheet modal-sheet-md"
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-label={`确认创建常驻房间 #${confirmCreateCode}`}
          >
            <div className="modal-head">
              <h3 className="modal-head-title">
                <Pin size={17} className="lobby-card-title-icon" aria-hidden="true" />
                <span>是否创建常驻房间 #{confirmCreateCode}？</span>
              </h3>
              <button
                type="button"
                className="btn btn-ghost btn-icon modal-close-btn"
                aria-label="关闭弹窗"
                onClick={() => setConfirmCreateCode(null)}
              >
                <X size={16} aria-hidden="true" />
              </button>
            </div>
            <p className="modal-body-text">
              未找到房间 <strong className="mono">#{confirmCreateCode}</strong>。是否立即以此口令创建常驻房间并进入？
            </p>
            <div className="modal-actions-end">
              <button
                type="button"
                className="btn btn-glass lobby-touch-pill"
                onClick={() => setConfirmCreateCode(null)}
              >
                取消
              </button>
              <button
                type="button"
                className="btn btn-jelly lobby-touch-pill"
                onClick={() => void handleConfirmCreateFixedRoom()}
              >
                确认创建并进入
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 收到附近设备邀请弹窗 */}
      {incomingInvite && (
        <div className="modal-overlay" onClick={() => void handleRespondIncomingInvite(false)}>
          <div
            className="modal-sheet modal-sheet-sm"
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-label="附近设备传输邀请"
          >
            <div className="modal-head">
              <h3 className="modal-head-title">
                <Radio size={17} className="lobby-card-title-icon" aria-hidden="true" />
                <span>附近设备传输邀请</span>
              </h3>
              <button
                type="button"
                className="btn btn-ghost btn-icon modal-close-btn"
                aria-label="婉拒并关闭弹窗"
                onClick={() => void handleRespondIncomingInvite(false)}
              >
                <X size={16} aria-hidden="true" />
              </button>
            </div>
            <div className="modal-invite-peer">
              <Avatar name={incomingInvite.fromName} avatar={incomingInvite.fromAvatar} size={46} />
              <div className="modal-invite-info">
                <div className="modal-invite-name">{incomingInvite.fromName}</div>
                <div className="modal-invite-desc">
                  邀请你加入私密传输房间 <span className="mono">#{incomingInvite.roomCode}</span>
                </div>
              </div>
            </div>
            <div className="modal-actions-end">
              <button
                type="button"
                className="btn btn-glass lobby-touch-pill"
                onClick={() => void handleRespondIncomingInvite(false)}
              >
                婉拒
              </button>
              <button
                type="button"
                className="btn btn-jelly lobby-touch-pill"
                onClick={() => void handleRespondIncomingInvite(true)}
              >
                接受并进入
              </button>
            </div>
          </div>
        </div>
      )}

      {viewingPlane && (
        <PlaneViewerModal
          plane={viewingPlane}
          joining={joiningPlane}
          onJoinPlane={(p) => void handleJoinFromPlane(p)}
          onReportPlane={(p) => void handleReportPlane(p)}
          onClose={() => {
            if (!joiningPlane) setViewingPlane(null);
          }}
        />
      )}

      {pickupSendOpen && (
        <PickupSendModal
          serverConfig={serverConfig}
          onClose={() => setPickupSendOpen(false)}
          onOpenCodePage={onOpenPickupPage}
          onToast={onToast}
        />
      )}

      {pickupLookupOpen && (
        <PickupLookupModal
          onClose={() => setPickupLookupOpen(false)}
          onSubmitCode={(code) => {
            setPickupLookupOpen(false);
            onOpenPickupPage(code);
          }}
        />
      )}
    </div>
  );
};
