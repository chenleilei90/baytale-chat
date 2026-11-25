import React, { useEffect, useRef, useState } from 'react';
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
  UserRound,
  Compass,
  HardDrive,
  Headphones,
  PenTool,
  Timer,
  Image as ImageIcon,
} from 'lucide-react';
import type {
  AppearanceMode,
  NearbyDevice,
  NearbyInviteNotice,
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
  fetchRoomInfo,
  pickRandomPublicRoom,
  respondNearbyInvite,
  sendNearbyHeartbeat,
  sendNearbyInvite,
  verifyRoomPassword,
} from '../lib/signaling';
import { copyTextToClipboard, formatRelativeTime } from '../lib/platform';
import { generateRandomName, pickRandomPresetAvatar } from '../lib/avatar';
import type { UserPrefs } from '../lib/storage';
import { Avatar } from './Avatar';
import { AppearancePicker, PalettePicker, Switch } from './Controls';
import { PickupLookupModal, PickupSendModal } from './PickupModal';

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

// BayChat 大厅主视图：智能直达入口 + 外观/强调色控制 + 身份名片、历史收藏与附近设备发现
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
  const [allowRandom, setAllowRandom] = useState(false); // 默认私密：不开放陌生人随机加入
  const [privatePairOnly, setPrivatePairOnly] = useState(false);
  const [retentionDays, setRetentionDays] = useState(1);
  const [maxFileSizeMB, setMaxFileSizeMB] = useState<number>(
    serverConfig?.defaultRoomMaxFileMB ?? 500
  );
  const [saveToFavorites, setSaveToFavorites] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [randomJoining, setRandomJoining] = useState(false);
  const [errorText, setErrorText] = useState(initialNotice || '');

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
        // 心跳瞬态失败静默忽略，等待下个周期重试
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

  // 单入口智能直达：留空直接创建房间；填口令/链接时先探测是否存在，6位数字不存在则报错，自定义码弹窗确认后建房
  const handleUnifiedSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorText('');
    const code = extractRoomCodeInput(roomInput);
    const cleanPwd = roomPassword.trim();
    const maxLimit = serverConfig?.maxRoomPeers || 6;

    setSubmitting(true);
    try {
      if (!code) {
        // 留空：零配置直接创建新房间
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
        return;
      }

      // 输入了口令/链接：先探测是否已存在
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
        // 6 位纯数字仅用于临时房，不存在时直接提示，绝不自动建成常驻房
        setErrorText('临时房间已结束或口令有误');
      } else {
        // 自定义非 6 位口令不存在时，弹窗让用户确认是否创建常驻房间
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
      onToast(`已创建专属常驻房间 #${summary.code}`);
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
      {/* 顶部纯色导航栏 */}
      <header className="top-glass-nav lobby-top-nav">
        <div className="brand-pill">
          <div className="brand-jelly-logo">
            <Send size={18} aria-hidden="true" />
          </div>
          <div>
            <div className="brand-title-text">湾聊 · BayChat</div>
            <div className="brand-subtitle">
              {domainDisplay} · 跨端 P2P 直传
            </div>
          </div>
        </div>

        <div className="lobby-nav-actions">
          <div className="lobby-theme-controls">
            <AppearancePicker appearance={appearance} onSelect={onSelectAppearance} />
            <PalettePicker theme={theme} onSelect={onSelectTheme} />
          </div>

          <button
            type="button"
            className="user-chip lobby-touch-pill"
            onClick={onEditProfile}
            title="点击编辑个人头像与昵称"
            aria-label={`编辑个人名片：${profile.name}`}
          >
            <Avatar name={profile.name} avatar={profile.avatar} size={32} />
            <span className="hide-mobile lobby-user-name">
              {profile.name}
            </span>
          </button>
        </div>
      </header>

      {/* 主体双栏工作区 */}
      <main className="bento-scroll">
        <div className="bento-stage lobby-stage-start">
          {/* 左主卡：连接状态概览 + 房间直达入口 */}
          <section className="glass-bento">
            <div className="sonic-hero">
              <button
                type="button"
                className="sonic-rings lobby-avatar-trigger"
                onClick={onEditProfile}
                aria-label="编辑个人身份名片"
              >
                <Avatar name={profile.name} avatar={profile.avatar} size={46} />
              </button>
              <div className="lobby-hero-content">
                <div className="lobby-hero-title-row">
                  <span className="lobby-hero-title">
                    跨设备文件直传与即时协作
                  </span>
                  <span className="pill pill-emerald">
                    <span className="status-dot emerald" aria-hidden="true" /> P2P 点对点直连
                  </span>
                </div>
                <div className="lobby-hero-desc">
                  跨网络终端直连与 TURN 中继兜底，支持大文件直传、实时聊天、语音通话与协作白板
                </div>
              </div>
            </div>

            <form onSubmit={handleUnifiedSubmit} className="lobby-form">
              {/* 单入口智能输入：留空直接新建房间，填口令有就进/无就建 */}
              <div className="lobby-field-group">
                <div className="lobby-field-head">
                  <span>房间口令或邀请链接</span>
                  <span className="lobby-field-hint">
                    {hasInputCode ? '输入口令直接进入' : '留空点击即可新建房间'}
                  </span>
                </div>
                <div className="input-group">
                  <input
                    className="field-input with-suffix mono lobby-code-input"
                    value={roomInput}
                    onChange={(e) => {
                      setRoomInput(e.target.value);
                      setErrorText('');
                      if (!e.target.value.trim()) setNeedPassword(false);
                    }}
                    onBlur={() => void probeRoomInput(roomInput)}
                    placeholder="留空新建房间，或输入 849205 / bay-8888"
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
              </div>

              {/* 仅当探测到加密房或未展开高级设置但已填密码时展示 */}
              {(needPassword || (!showAdvanced && hasPasswordInput)) && (
                <div className="lobby-field-group">
                  <span className="lobby-field-label">
                    输入房间访问密码
                  </span>
                  <div className="input-group">
                    <span className="input-prefix-icon">
                      <Lock size={15} aria-hidden="true" />
                    </span>
                    <input
                      className="field-input with-prefix"
                      type="password"
                      value={roomPassword}
                      onChange={(e) => {
                        setRoomPassword(e.target.value);
                        setErrorText('');
                      }}
                      placeholder="输入对方告知的密码..."
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

              {/* 主行动栏：新建/直达房间 + 随机加入 */}
              <div className="lobby-cta-row">
                <button
                  type="submit"
                  className="btn btn-jelly btn-xl lobby-cta-primary"
                  disabled={submitting || randomJoining}
                >
                  <span>
                    {submitting
                      ? '正在建立连接...'
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
                  title="仅加入无密码且允许随机访问的活跃房间"
                >
                  <Dices size={17} aria-hidden="true" />
                  <span>{randomJoining ? '匹配中...' : '随机加入'}</span>
                </button>
              </div>

              {/* 一行控制条：允许随机加入开关 + 高级设置折叠开关 */}
              <div className="lobby-control-strip">
                <button
                  type="button"
                  className={`btn btn-sm lobby-touch-pill ${!hasPasswordInput && allowRandom ? 'btn-soft' : 'btn-glass'}`}
                  disabled={hasPasswordInput}
                  onClick={() => {
                    const next = !allowRandom;
                    setAllowRandom(next);
                    onToast(next ? '已开启随机加入公开房间' : '已关闭随机加入，仅限口令进入');
                  }}
                  title="默认关闭仅限口令进入，开启后允许他人随机加入"
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
                  <span>高级设置</span>
                  {!showAdvanced && (
                    <span className="lobby-advanced-summary">
                      ({summaryTag})
                    </span>
                  )}
                  {showAdvanced ? (
                    <ChevronUp size={14} aria-hidden="true" />
                  ) : (
                    <ChevronDown size={14} aria-hidden="true" />
                  )}
                </button>
              </div>

              {/* 高级设置折叠区（默认收起，保持页面简洁） */}
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
                        生成 6 位随机口令，会话结束后自动清理
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
                      <div className="candy-card-title">常驻房间 · 长期保留</div>
                      <div className="candy-card-desc">
                        固定房间号，多设备随时重新连接
                      </div>
                    </button>
                  </div>

                  <div className="lobby-field-group">
                    <div className="lobby-field-head">
                      <span>服务器消息与文件保留期（最长 7 天）</span>
                      <span className="lobby-field-hint">
                        {retentionDays === 0
                          ? '服务器零留存'
                          : `活跃起 ${retentionDays} 天后自动清空`}
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
                      <span>单附件传输大小上限</span>
                      <span className="lobby-field-hint">
                        {maxFileSizeMB === 0
                          ? '不限制（支持大文件流式直传）'
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
                      <span className="lobby-field-label">
                        房间容量
                      </span>
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
                      <span className="lobby-field-label">
                        访问密码（可选）
                      </span>
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
                      description="下次打开网页可直接点击快速进入"
                    />
                  )}
                </div>
              )}
            </form>
          </section>

          {/* 右侧副栏：身份名片 + 最近房间 + 常驻收藏 + 离线取件 + 附近设备 + 核心能力 */}
          <aside className="lobby-aside">
            {/* 身份名片卡 */}
            <div className="glass-bento lobby-bento-card-lg">
              <div className="lobby-card-head lobby-card-head-lg">
                <span className="lobby-card-title">
                  <UserRound size={15} className="lobby-card-title-icon" aria-hidden="true" />
                  <span>我的身份名片</span>
                </span>
                <div className="lobby-card-actions">
                  <button
                    type="button"
                    className="btn btn-soft btn-sm lobby-touch-pill"
                    onClick={() =>
                      onQuickRandomProfile({
                        ...profile,
                        name: generateRandomName(),
                        avatar: pickRandomPresetAvatar(),
                      })
                    }
                    title="随机更换昵称与头像"
                  >
                    <Dices size={13} aria-hidden="true" /> 随机生成
                  </button>
                  <button
                    type="button"
                    className="btn btn-glass btn-sm lobby-touch-pill"
                    onClick={onEditProfile}
                  >
                    编辑名片
                  </button>
                </div>
              </div>

              <div className="lobby-profile-row">
                <button
                  type="button"
                  className="lobby-avatar-trigger"
                  onClick={onEditProfile}
                  aria-label="编辑个人身份名片"
                >
                  <Avatar name={profile.name} avatar={profile.avatar} size={54} />
                </button>
                <div className="lobby-profile-info">
                  <div className="lobby-profile-name">
                    {profile.name}
                  </div>
                  <div className="lobby-profile-sub">
                    免注册即用 · 支持上传图片或选用预设头像
                  </div>
                </div>
              </div>
            </div>

            {/* 最近访问过的房间（包含临时房与固定房，支持重进与自动失效清理） */}
            {recentRooms.length > 0 && (
              <div className="glass-bento lobby-bento-card">
                <div className="lobby-card-head">
                  <span className="lobby-card-title">
                    <Clock size={14} className="lobby-card-title-icon" aria-hidden="true" />
                    <span>最近房间</span>
                  </span>
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm lobby-clear-recent-btn"
                    onClick={onClearRecentRooms}
                  >
                    清空
                  </button>
                </div>

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
                          title="移除此记录"
                          aria-label={`移除房间 ${item.code} 的最近记录`}
                          onClick={() => onRemoveRecentRoom(item.code)}
                        >
                          <Trash2 size={13} aria-hidden="true" />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* 常驻收藏房间 */}
            <div className="glass-bento lobby-bento-card">
              <div className="lobby-card-head">
                <span className="lobby-card-title">
                  <Pin size={14} className="lobby-card-title-icon" aria-hidden="true" />
                  <span>常驻收藏</span>
                </span>
                <span className="pill pill-accent mono">{savedFixedRooms.length}</span>
              </div>

              {savedFixedRooms.length === 0 ? (
                <div className="lobby-empty-card">
                  <div className="lobby-empty-title">
                    <Pin size={14} className="lobby-card-title-icon" aria-hidden="true" />
                    <span>暂无常驻收藏房间</span>
                  </div>
                  <div className="lobby-empty-desc">
                    在左侧「高级设置」中选择常驻房间并勾选收藏，即可固定在此处，方便多设备随时重连与互传文件。
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
                        <div className="lobby-room-sub">常驻直连通道</div>
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

            {/* 取件码离线投递入口卡 */}
            <div className="glass-bento lobby-bento-card">
              <div className="lobby-card-head">
                <span className="lobby-card-title">
                  <Package size={15} className="lobby-card-title-icon" aria-hidden="true" />
                  <span>取件码 · 离线传文件</span>
                </span>
                <span className="pill pill-accent">6位码</span>
              </div>
              <div className="lobby-pickup-desc">
                对方不在线也能发：上传生成 6 位取件码，支持取后即焚与密码保护
              </div>
              <div className="lobby-two-btn-grid">
                <button
                  type="button"
                  className="btn btn-jelly btn-sm lobby-touch-pill"
                  onClick={() => setPickupSendOpen(true)}
                >
                  <Package size={13} aria-hidden="true" /> 发送文件
                </button>
                <button
                  type="button"
                  className="btn btn-soft btn-sm lobby-touch-pill"
                  onClick={() => setPickupLookupOpen(true)}
                >
                  <KeyRound size={13} aria-hidden="true" /> 输入取件码
                </button>
              </div>
            </div>

            {/* 附近同 Wi-Fi 设备发现卡 */}
            <div className="glass-bento lobby-bento-card">
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
                description="仅在首页前台运行时生效，点击设备即可直传"
              />
              {prefs.nearbyDiscoverable && (
                <div className="lobby-nearby-box">
                  {nearbyDevices.length === 0 ? (
                    <div className="lobby-nearby-empty">
                      <Radio size={15} className="lobby-card-title-icon" aria-hidden="true" />
                      <span>
                        正在扫描同一网络下的湾聊设备（手机或电脑打开大厅即可互相发现）...
                      </span>
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
                          <span className="lobby-nearby-name">
                            {dev.name}
                          </span>
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

            {/* 核心能力概览卡 */}
            <div className="glass-bento lobby-bento-card">
              <div className="lobby-card-head">
                <span className="lobby-card-title">
                  <Compass size={15} className="lobby-card-title-icon" aria-hidden="true" />
                  <span>房间核心能力</span>
                </span>
              </div>
              <div className="feature-tag-cloud">
                <span className="feature-mini-pill">
                  <HardDrive size={13} aria-hidden="true" /> 大文件 P2P 直传
                </span>
                <span className="feature-mini-pill">
                  <Headphones size={13} aria-hidden="true" /> 语音连麦与投屏
                </span>
                <span className="feature-mini-pill">
                  <PenTool size={13} aria-hidden="true" /> 多人协作白板
                </span>
                <span className="feature-mini-pill">
                  <Timer size={13} aria-hidden="true" /> 同步专注计时
                </span>
                <span className="feature-mini-pill">
                  <Clock size={13} aria-hidden="true" /> 0~7 天可控缓存
                </span>
                <span className="feature-mini-pill">
                  <ImageIcon size={13} aria-hidden="true" /> 剪贴板图片直发
                </span>
              </div>
            </div>
          </aside>
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
