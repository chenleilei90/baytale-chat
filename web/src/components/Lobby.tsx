import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Activity,
  AlertCircle,
  ArrowRight,
  Ban,
  Bell,
  Bookmark,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  ClipboardPaste,
  Compass,
  Copy,
  Database,
  Dices,
  Edit3,
  ExternalLink,
  File as FileIcon,
  Flame,
  FolderUp,
  Globe,
  HardDrive,
  History,
  Key,
  KeyRound,
  Lock,
  Package,
  Palette,
  Pin,
  Plus,
  Radio,
  RefreshCw,
  Search,
  Send,
  ShieldCheck,
  SlidersHorizontal,
  Trash2,
  Unlock,
  UserRound,
  Users,
  Wifi,
  X,
} from 'lucide-react';
import type {
  AppearanceMode,
  BlockedPeer,
  FavoriteItem,
  MyPickupRecord,
  NearbyDevice,
  NearbyInviteNotice,
  PaperPlane,
  PendingNearbyInvite,
  RecentRoom,
  RoomMode,
  RoomSummary,
  SavedFixedRoom,
  ServerConfig,
  ThemeMode,
  TransferRecord,
  UserProfile,
} from '../types';
import {
  ApiError,
  createRoom,
  extractRoomCodeInput,
  fetchPlanes,
  fetchPublicRooms,
  fetchRoomInfo,
  joinPlane,
  pickRandomPublicRoom,
  reportPlane,
  respondNearbyInvite,
  sendNearbyHeartbeat,
  sendNearbyInvite,
  verifyRoomPassword,
} from '../lib/signaling';
import { copyTextToClipboard, formatBytes, formatExpiryTime, formatRelativeTime } from '../lib/platform';
import { generateRandomName, pickRandomPresetAvatar } from '../lib/avatar';
import {
  getNotificationPermission,
  isNotificationSupported,
  requestNotificationPermission,
  showWebNotification,
} from '../lib/notifications';
import { estimateLocalStorageBytes, type UserPrefs } from '../lib/storage';
import { Avatar } from './Avatar';
import { AppearancePicker, PalettePicker, Switch } from './Controls';
import { PickupLookupModal, PickupSendModal } from './PickupModal';
import { PlaneViewerModal, renderPlaneKindIcon } from './PlaneViewerModal';
import { SettingsPopover } from './SettingsPopover';

export type WorkspaceTab = 'quick' | 'discover' | 'transfer' | 'mine';

const ROOM_CODE_REGEX = /^[a-zA-Z0-9_-]{3,24}$/;

interface LobbyProps {
  profile: UserProfile;
  prefs: UserPrefs;
  theme: ThemeMode;
  appearance: AppearanceMode;
  isActiveLobby: boolean;
  serverConfig: ServerConfig | null;
  savedFixedRooms: SavedFixedRoom[];
  recentRooms: RecentRoom[];
  transferRecords: TransferRecord[];
  favorites: FavoriteItem[];
  blockedPeers: BlockedPeer[];
  myPickups: MyPickupRecord[];
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
  onPickupsChange: (records: MyPickupRecord[]) => void;
  onRevokePickup: (item: MyPickupRecord) => void;
  onRemoveSavedRoom: (code: string) => void;
  onRenameSavedRoom: (code: string, label: string) => void;
  onRemoveRecentRoom: (code: string) => void;
  onClearRecentRooms: () => void;
  onRemoveTransferRecord: (id: string) => void;
  onClearTransferRecords: () => void;
  onRemoveFavorite: (id: string) => void;
  onClearFavorites: () => void;
  onUnblockPeer: (clientId: string) => void;
  onClearLocalData: () => void;
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

const AUTO_ACCEPT_THRESHOLDS = [10, 50, 100, 200, 500];

// BayChat Web 3.0 主工作台：以数据存储、跨端传输与文件取件为核心的 4-Tab 响应式布局
export const Lobby: React.FC<LobbyProps> = ({
  profile,
  prefs,
  theme,
  appearance,
  isActiveLobby,
  serverConfig,
  savedFixedRooms,
  recentRooms,
  transferRecords,
  favorites,
  blockedPeers,
  myPickups,
  initialJoinCode,
  initialNotice,
  onSelectTheme,
  onSelectAppearance,
  onUpdatePrefs,
  onQuickRandomProfile,
  onEditProfile,
  onEnterRoom,
  onOpenPickupPage,
  onPickupsChange,
  onRevokePickup,
  onRemoveSavedRoom: _onRemoveSavedRoom,
  onRenameSavedRoom: _onRenameSavedRoom,
  onRemoveRecentRoom,
  onClearRecentRooms,
  onRemoveTransferRecord,
  onClearTransferRecords,
  onRemoveFavorite,
  onClearFavorites,
  onUnblockPeer,
  onClearLocalData,
  onToast,
}) => {
  const [activeTab, setActiveTab] = useState<WorkspaceTab>('quick');

  // 快传工作台：房间进入 / 创建状态
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
  const [confirmCreateCode, setConfirmCreateCode] = useState<string | null>(null);

  // 取件码弹窗
  const [pickupSendOpen, setPickupSendOpen] = useState(false);
  const [pickupLookupOpen, setPickupLookupOpen] = useState(false);

  // 附近设备与邀请
  const [nearbyDevices, setNearbyDevices] = useState<NearbyDevice[]>([]);
  const [incomingInvite, setIncomingInvite] = useState<NearbyInviteNotice | null>(null);
  const [invitingId, setInvitingId] = useState<string | null>(null);
  const handledInviteIdsRef = useRef<Set<string>>(new Set());

  // 发现广场：公开房间 + 纸飞机
  const [publicRooms, setPublicRooms] = useState<RoomSummary[]>([]);
  const [publicRoomsLoading, setPublicRoomsLoading] = useState(false);
  const [publicRoomsError, setPublicRoomsError] = useState('');
  const [publicSearch, setPublicSearch] = useState('');
  const [createPublicOpen, setCreatePublicOpen] = useState(false);
  const [publicTopic, setPublicTopic] = useState('');
  const [publicRetentionDays, setPublicRetentionDays] = useState(1);
  const [creatingPublic, setCreatingPublic] = useState(false);

  const [planes, setPlanes] = useState<PaperPlane[]>([]);
  const [planesLoading, setPlanesLoading] = useState(false);
  const [planesError, setPlanesError] = useState('');
  const [planeSearch, setPlaneSearch] = useState('');
  const [viewingPlane, setViewingPlane] = useState<PaperPlane | null>(null);
  const [joiningPlane, setJoiningPlane] = useState(false);

  // 传输 Tab 筛选
  const [transferFilter, setTransferFilter] = useState<'all' | 'send' | 'receive'>('all');

  // 我的 Tab：通知权限 & 收藏筛选 & 清理确认
  const [notifPermission, setNotifPermission] = useState(() => getNotificationPermission());
  const [favSearch, setFavSearch] = useState('');
  const [storageBytes, setStorageBytes] = useState(() => estimateLocalStorageBytes());
  const [confirmClearLocalOpen, setConfirmClearLocalOpen] = useState(false);
  const [settingsPopoverOpen, setSettingsPopoverOpen] = useState(false);

  const codeInputRef = useRef<HTMLInputElement | null>(null);
  const passwordInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (serverConfig && typeof serverConfig.defaultRoomMaxFileMB === 'number') {
      setMaxFileSizeMB(serverConfig.defaultRoomMaxFileMB);
    }
  }, [serverConfig?.defaultRoomMaxFileMB]);

  useEffect(() => {
    if (initialJoinCode) {
      setActiveTab('quick');
      setRoomInput(initialJoinCode);
      void probeRoomInput(initialJoinCode);
    }
  }, [initialJoinCode]);

  useEffect(() => {
    if (initialNotice) setErrorText(initialNotice);
  }, [initialNotice]);

  useEffect(() => {
    setStorageBytes(estimateLocalStorageBytes());
    setNotifPermission(getNotificationPermission());
  }, [activeTab, transferRecords.length, favorites.length, myPickups.length, recentRooms.length]);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setConfirmCreateCode(null);
        setCreatePublicOpen(false);
        setConfirmClearLocalOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  const loadPlanes = useCallback(async () => {
    setPlanesLoading(true);
    setPlanesError('');
    try {
      const res = await fetchPlanes(32);
      setPlanes(res.planes || []);
    } catch (err) {
      setPlanesError(err instanceof Error ? err.message : '纸飞机列表加载失败');
    } finally {
      setPlanesLoading(false);
    }
  }, []);

  const loadPublicRooms = useCallback(async () => {
    setPublicRoomsLoading(true);
    setPublicRoomsError('');
    try {
      const res = await fetchPublicRooms();
      setPublicRooms(res.rooms || []);
    } catch (err) {
      setPublicRoomsError(err instanceof Error ? err.message : '公开房间列表加载失败');
    } finally {
      setPublicRoomsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (isActiveLobby && activeTab === 'discover') {
      void loadPublicRooms();
      void loadPlanes();
    }
  }, [isActiveLobby, activeTab, loadPublicRooms, loadPlanes]);

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
    if (!code || !ROOM_CODE_REGEX.test(code)) {
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

  // 直接创建新房间（无论口令输入框是否已有字符都执行新建流程）
  const handleDirectCreateNewRoom = async () => {
    if (submitting || randomJoining) return;
    setErrorText('');
    const cleanPwd = roomPassword.trim();
    if (cleanPwd && !/^\d{5,}$/.test(cleanPwd)) {
      setErrorText('新建房间密码至少输入 5 位数字（留空免密）');
      return;
    }
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

  const handleUnifiedSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorText('');
    const code = extractRoomCodeInput(roomInput);
    const cleanPwd = roomPassword.trim();

    if (!code) {
      await handleDirectCreateNewRoom();
      return;
    }

    if (!ROOM_CODE_REGEX.test(code)) {
      setErrorText('房间口令仅支持 3~24 位字母、数字、下划线或连字符');
      return;
    }

    setSubmitting(true);
    try {
      let existing: RoomSummary | null = null;
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
            window.setTimeout(() => passwordInputRef.current?.focus(), 50);
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
        setErrorText('6 位临时房间已结束或口令有误');
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
    const cleanPwd = roomPassword.trim();
    if (cleanPwd && !/^\d{5,}$/.test(cleanPwd)) {
      setConfirmCreateCode(null);
      setErrorText('新建房间密码至少输入 5 位数字（留空免密）');
      return;
    }
    setConfirmCreateCode(null);
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

  const handleCreatePublicRoom = async (e: React.FormEvent) => {
    e.preventDefault();
    if (creatingPublic) return;
    setCreatingPublic(true);
    try {
      const summary = await createRoom({
        mode: 'ephemeral',
        allowRandom: true,
        topic: publicTopic.trim().slice(0, 24) || undefined,
        maxPeers: serverConfig?.maxRoomPeers || 6,
        retentionDays: publicRetentionDays,
        maxFileSizeMB,
      });
      setCreatePublicOpen(false);
      setPublicTopic('');
      onToast(`已创建公开存储房间 #${summary.code}`);
      onEnterRoom(summary.code, '', summary.mode, false, summary.ownerToken);
    } catch (err) {
      onToast(err instanceof Error ? err.message : '创建公开房间失败');
    } finally {
      setCreatingPublic(false);
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
      const summary = await pickRandomPublicRoom();
      onEnterRoom(summary.code, '', summary.mode, false, findStoredOwnerToken(summary.code));
    } catch (err) {
      setErrorText(err instanceof Error ? err.message : '暂无开放随机加入的房间');
    } finally {
      setRandomJoining(false);
    }
  };

  const handleQuickEnterSaved = async (item: SavedFixedRoom) => {
    setErrorText('');
    try {
      const info = await fetchRoomInfo(item.code);
      if (info.hasPassword && !item.password) {
        setActiveTab('quick');
        setRoomInput(item.code);
        setNeedPassword(true);
        setErrorText('请输入该常驻房间的访问密码');
        return;
      }
      if (info.hasPassword && item.password) {
        await verifyRoomPassword(item.code, item.password);
      }
      onEnterRoom(item.code, item.password || '', 'fixed', true, item.ownerToken);
    } catch (err) {
      setActiveTab('quick');
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
        setActiveTab('quick');
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
      setActiveTab('quick');
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
        setPlanes((prev) =>
          prev.map((item) => (item.id === plane.id ? { ...item, allowEntry: false } : item))
        );
        setViewingPlane(null);
        setActiveTab('quick');
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
        const extracted = extractRoomCodeInput(text);
        setRoomInput(extracted);
        void probeRoomInput(extracted);
      }
    } catch {
      codeInputRef.current?.focus();
    }
  };

  const handleRequestNotifications = async () => {
    const res = await requestNotificationPermission();
    setNotifPermission(res);
    if (res === 'granted') {
      onUpdatePrefs({ ...prefs, desktopNotifications: true });
      showWebNotification({
        title: 'BayChat 桌面通知已开启',
        body: '后台挂起或切出标签页时，收到新消息与文件传输请求会及时提醒你',
      });
      onToast('已开启浏览器系统通知');
    } else if (res === 'denied') {
      onToast('通知权限已被浏览器拦截，请在地址栏左侧网站设置中允许通知');
    } else if (res === 'unsupported') {
      onToast('当前浏览器环境不支持桌面通知');
    }
  };

  const filteredPublicRooms = useMemo(() => {
    const kw = publicSearch.trim().toLowerCase();
    if (!kw) return publicRooms;
    return publicRooms.filter(
      (r) =>
        r.code.toLowerCase().includes(kw) ||
        (r.topic || '').toLowerCase().includes(kw)
    );
  }, [publicRooms, publicSearch]);

  const filteredPlanes = useMemo(() => {
    const kw = planeSearch.trim().toLowerCase();
    if (!kw) return planes;
    return planes.filter(
      (p) =>
        p.roomCode.toLowerCase().includes(kw) ||
        p.senderName.toLowerCase().includes(kw) ||
        p.message.toLowerCase().includes(kw) ||
        p.files.some((f) => f.name.toLowerCase().includes(kw))
    );
  }, [planes, planeSearch]);

  const filteredTransfers = useMemo(() => {
    if (transferFilter === 'all') return transferRecords;
    return transferRecords.filter((t) => t.direction === transferFilter);
  }, [transferRecords, transferFilter]);

  const filteredFavorites = useMemo(() => {
    const kw = favSearch.trim().toLowerCase();
    if (!kw) return favorites;
    return favorites.filter(
      (f) =>
        f.roomCode.toLowerCase().includes(kw) ||
        f.senderName.toLowerCase().includes(kw) ||
        (f.text || '').toLowerCase().includes(kw) ||
        (f.fileName || '').toLowerCase().includes(kw)
    );
  }, [favorites, favSearch]);

  const hasInputCode = roomInput.trim().length > 0;
  const hasPasswordInput = roomPassword.trim().length > 0;
  const summaryTag = `${mode === 'fixed' || hasInputCode ? '常驻' : '临时'} · ${retentionDays === 0 ? '即焚' : `${retentionDays}天`} · ${maxFileSizeMB === 0 ? '不限' : `${maxFileSizeMB}MB`}`;

  const navItems: { id: WorkspaceTab; label: string; icon: React.ReactNode; badge?: number }[] = [
    { id: 'quick', label: '快传', icon: <HardDrive size={18} aria-hidden="true" /> },
    { id: 'discover', label: '发现', icon: <Compass size={18} aria-hidden="true" /> },
    {
      id: 'transfer',
      label: '传输',
      icon: <History size={18} aria-hidden="true" />,
      badge: myPickups.length > 0 ? myPickups.length : undefined,
    },
    { id: 'mine', label: '我的', icon: <UserRound size={18} aria-hidden="true" /> },
  ];

  const totalTransferBytes = useMemo(
    () => transferRecords.reduce((sum, item) => sum + (item.fileSize || 0), 0),
    [transferRecords]
  );

  // 左侧导航栏「常用房间」快捷列表：合并常驻收藏与最近加入（最多 5 项，去重）
  const railQuickRooms = useMemo(() => {
    const merged: {
      key: string;
      code: string;
      label: string;
      hasPassword: boolean;
      kind: 'fixed' | 'recent';
      fixedItem?: SavedFixedRoom;
      recentItem?: RecentRoom;
    }[] = [];
    const seen = new Set<string>();
    for (const r of savedFixedRooms) {
      if (seen.has(r.code)) continue;
      seen.add(r.code);
      merged.push({
        key: `fixed_${r.code}`,
        code: r.code,
        label: r.label?.trim() || `#${r.code}`,
        hasPassword: r.hasPassword,
        kind: 'fixed',
        fixedItem: r,
      });
      if (merged.length >= 5) return merged;
    }
    for (const r of recentRooms) {
      if (seen.has(r.code)) continue;
      seen.add(r.code);
      merged.push({
        key: `recent_${r.code}`,
        code: r.code,
        label: `#${r.code}`,
        hasPassword: r.hasPassword,
        kind: 'recent',
        recentItem: r,
      });
      if (merged.length >= 5) return merged;
    }
    return merged;
  }, [savedFixedRooms, recentRooms]);

  const topbarMeta: Record<WorkspaceTab, { title: string; subtitle: string }> = {
    quick: { title: '快传工作台', subtitle: '以数据存储与直传为核心，支持 1~7 天服务器缓存及端到端传输' },
    discover: { title: '发现广场', subtitle: '浏览公开免密存储房间与实时放飞的纸飞机文件清单' },
    transfer: { title: '传输与取件中心', subtitle: '集中管理服务器 6 位离线取件码与本机文件传输历史' },
    mine: { title: '我的与偏好设置', subtitle: '本机身份名片、通知提醒、外观主题、收藏与缓存空间管理' },
  };

  return (
    <div className="workspace-shell" onClick={() => settingsPopoverOpen && setSettingsPopoverOpen(false)}>
      {/* 桌面端 248px 左侧导航栏 */}
      <aside className="workspace-rail" aria-label="主功能导航">
        <div className="workspace-rail-brand">
          <div className="brand-mark">
            <Send size={17} strokeWidth={2.3} aria-hidden="true" />
          </div>
          <div className="brand-text-wrap">
            <div className="brand-name">湾聊 · BayChat</div>
            <div className="brand-sub">跨端文件存储与直传</div>
          </div>
        </div>

        <button
          type="button"
          className="btn btn-jelly workspace-rail-cta"
          disabled={submitting || randomJoining}
          onClick={() => void handleDirectCreateNewRoom()}
        >
          <Plus size={16} aria-hidden="true" />
          <span>新建存储房间</span>
        </button>

        <nav className="workspace-rail-nav">
          {navItems.map((item) => (
            <button
              key={item.id}
              type="button"
              className={`workspace-rail-item ${activeTab === item.id ? 'active' : ''}`}
              onClick={() => setActiveTab(item.id)}
              aria-current={activeTab === item.id ? 'page' : undefined}
            >
              <span className="workspace-rail-icon">{item.icon}</span>
              <span className="workspace-rail-label">{item.label}</span>
              {item.badge !== undefined && (
                <span className="pill pill-accent mono workspace-rail-badge">{item.badge}</span>
              )}
            </button>
          ))}
        </nav>

        <div className="workspace-rail-rooms">
          <div className="workspace-rail-section-label">常用房间</div>
          {railQuickRooms.length === 0 ? (
            <div className="workspace-rail-rooms-empty">暂无最近房间记录</div>
          ) : (
            <div className="workspace-rail-room-list">
              {railQuickRooms.map((item) => (
                <button
                  key={item.key}
                  type="button"
                  className="workspace-rail-room-btn"
                  onClick={() =>
                    item.kind === 'fixed' && item.fixedItem
                      ? void handleQuickEnterSaved(item.fixedItem)
                      : item.recentItem
                        ? void handleQuickEnterRecent(item.recentItem)
                        : undefined
                  }
                >
                  <span className="workspace-rail-room-name mono">{item.label}</span>
                  {item.hasPassword && <Lock size={12} className="lobby-room-lock" aria-hidden="true" />}
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="workspace-rail-footer">
          <button
            type="button"
            className="workspace-rail-profile"
            onClick={() => setActiveTab('mine')}
            title="前往「我的」管理身份与偏好"
          >
            <Avatar name={profile.name} avatar={profile.avatar} size={34} />
            <div className="workspace-rail-profile-meta">
              <div className="workspace-rail-profile-name">{profile.name}</div>
              <div className="workspace-rail-profile-sub">
                {notifPermission === 'granted' ? '系统通知已开启' : '免注册本机身份'}
              </div>
            </div>
          </button>
        </div>
      </aside>

      {/* 右侧主内容区域（桌面端含自适应顶部栏，移动端含精简顶栏） */}
      <div className="workspace-content-col">
        <header className="workspace-desktop-topbar">
          <div className="workspace-desktop-topbar-left">
            <h1 className="workspace-desktop-title">{topbarMeta[activeTab].title}</h1>
            <span className="workspace-desktop-sub">{topbarMeta[activeTab].subtitle}</span>
          </div>
          <div className="workspace-desktop-topbar-actions">
            <button
              type="button"
              className="btn btn-soft btn-sm"
              onClick={() => setPickupSendOpen(true)}
            >
              <Package size={14} aria-hidden="true" /> 发取件码
            </button>
            <button
              type="button"
              className="btn btn-glass btn-sm"
              onClick={() => setPickupLookupOpen(true)}
            >
              <KeyRound size={14} aria-hidden="true" /> 输码取件
            </button>
            <div className="workspace-settings-anchor" onClick={(e) => e.stopPropagation()}>
              <button
                type="button"
                className="btn btn-ghost btn-icon"
                aria-label="外观与偏好设置"
                title="外观与偏好设置"
                onClick={() => setSettingsPopoverOpen((prev) => !prev)}
              >
                <Palette size={16} aria-hidden="true" />
              </button>
              {settingsPopoverOpen && (
                <SettingsPopover
                  appearance={appearance}
                  theme={theme}
                  prefs={prefs}
                  onSelectAppearance={onSelectAppearance}
                  onSelectTheme={onSelectTheme}
                  onUpdatePrefs={onUpdatePrefs}
                  onClose={() => setSettingsPopoverOpen(false)}
                />
              )}
            </div>
          </div>
        </header>

        <header className="workspace-mobile-topbar">
          <div className="brand-pill">
            <div className="brand-mark">
              <Send size={16} strokeWidth={2.3} aria-hidden="true" />
            </div>
            <div className="brand-text-wrap">
              <div className="brand-name">湾聊 · BayChat</div>
              <div className="brand-sub">文件存储与直传工作台</div>
            </div>
          </div>
          <button
            type="button"
            className="user-chip lobby-touch-pill"
            onClick={() => setActiveTab('mine')}
            aria-label={`当前身份：${profile.name}`}
          >
            <Avatar name={profile.name} avatar={profile.avatar} size={28} />
          </button>
        </header>

        <main className="workspace-scroll">
          <div className="workspace-canvas">
            {/* ================== TAB 1: P2PLink 1:1 三栏极客直连工作台 ================== */}
            {activeTab === 'quick' && (
              <div className="p2p-lobby-workbench">
                {/* ---------- 左栏：我的节点 + 在线设备与常用房间 + E2EE 加密卡 ---------- */}
                <aside className="p2p-col-panel p2p-col-left">
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 12, minHeight: 0, flex: 1 }}>
                    <div className="p2p-node-card">
                      <div className="p2p-node-head">
                        <span className="p2p-node-title">
                          <span className="p2p-pulse-dot" />
                          我的节点 (Web 端)
                        </span>
                        <span className="p2p-node-chip mono">P2P Ready</span>
                      </div>
                      <button
                        type="button"
                        className="p2p-node-name-btn"
                        onClick={onEditProfile}
                        title="点击修改本机昵称与头像"
                      >
                        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {profile.name}
                        </span>
                        <Edit3 size={12} style={{ color: 'var(--accent)', flexShrink: 0 }} aria-hidden="true" />
                      </button>
                      <div className="p2p-node-fp mono">
                        <Key size={11} style={{ color: 'var(--accent)', flexShrink: 0 }} aria-hidden="true" />
                        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {profile.clientId}
                        </span>
                      </div>
                    </div>

                    <div className="sidebar-section-head" style={{ padding: '0 2px' }}>
                      <span>直连设备与节点 ({nearbyDevices.length + savedFixedRooms.length + recentRooms.length})</span>
                      {recentRooms.length > 0 ? (
                        <button
                          type="button"
                          className="btn btn-ghost btn-sm"
                          style={{ height: 22, padding: '0 6px', fontSize: 10.5 }}
                          onClick={onClearRecentRooms}
                        >
                          清空最近
                        </button>
                      ) : (
                        <Users size={13} aria-hidden="true" />
                      )}
                    </div>

                    <div className="p2p-peer-list geek-scroll">
                      {nearbyDevices.map((dev) => (
                        <button
                          key={dev.clientId}
                          type="button"
                          className="p2p-peer-row"
                          disabled={invitingId === dev.clientId}
                          onClick={() => void handleInviteNearbyDevice(dev)}
                          title={`点击邀请 ${dev.name} 建立直连`}
                        >
                          <Avatar name={dev.name} avatar={dev.avatar} size={34} />
                          <div className="p2p-peer-meta">
                            <div className="p2p-peer-top">
                              <span className="p2p-peer-name">{dev.name}</span>
                              <span className="mono" style={{ fontSize: 10, color: 'var(--success)' }}>
                                在线
                              </span>
                            </div>
                            <div className="p2p-peer-sub">
                              <span style={{ color: 'var(--accent)', fontWeight: 600 }}>
                                {dev.device === 'android' ? 'Android LAN' : 'Web LAN'}
                              </span>
                              <span style={{ color: 'var(--text-muted)' }}>
                                {invitingId === dev.clientId ? '邀请中...' : '点击直连'}
                              </span>
                            </div>
                          </div>
                        </button>
                      ))}

                      {savedFixedRooms.map((item) => (
                        <button
                          key={`saved_${item.code}`}
                          type="button"
                          className="p2p-peer-row"
                          onClick={() => void handleQuickEnterSaved(item)}
                        >
                          <div
                            style={{
                              width: 34,
                              height: 34,
                              borderRadius: 10,
                              background: 'var(--accent-soft)',
                              color: 'var(--accent)',
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'center',
                              flexShrink: 0,
                            }}
                          >
                            <Pin size={15} aria-hidden="true" />
                          </div>
                          <div className="p2p-peer-meta">
                            <div className="p2p-peer-top">
                              <span className="p2p-peer-name mono">
                                {item.label ? `${item.label} (#${item.code})` : `#${item.code}`}
                              </span>
                              {item.hasPassword && <Lock size={11} style={{ color: 'var(--accent)' }} aria-hidden="true" />}
                            </div>
                            <div className="p2p-peer-sub">
                              <span style={{ color: 'var(--accent)' }}>常驻基地</span>
                              <span style={{ color: 'var(--text-muted)' }}>{formatRelativeTime(item.lastVisitedAt)}</span>
                            </div>
                          </div>
                        </button>
                      ))}

                      {recentRooms.map((item) => (
                        <button
                          key={`rec_${item.code}`}
                          type="button"
                          className="p2p-peer-row"
                          onClick={() => void handleQuickEnterRecent(item)}
                        >
                          <div
                            style={{
                              width: 34,
                              height: 34,
                              borderRadius: 10,
                              background: 'var(--surface)',
                              color: 'var(--text-secondary)',
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'center',
                              flexShrink: 0,
                            }}
                          >
                            <Flame size={15} aria-hidden="true" />
                          </div>
                          <div className="p2p-peer-meta">
                            <div className="p2p-peer-top">
                              <span className="p2p-peer-name mono">#{item.code}</span>
                              {item.hasPassword && <Lock size={11} style={{ color: 'var(--accent)' }} aria-hidden="true" />}
                            </div>
                            <div className="p2p-peer-sub">
                              <span style={{ color: 'var(--text-secondary)' }}>
                                {item.mode === 'fixed' ? '常驻房间' : '临时闪传'}
                              </span>
                              <span style={{ color: 'var(--text-muted)' }}>{formatRelativeTime(item.lastVisitedAt)}</span>
                            </div>
                          </div>
                        </button>
                      ))}

                      {nearbyDevices.length === 0 && savedFixedRooms.length === 0 && recentRooms.length === 0 && (
                        <div className="lobby-empty-card centered" style={{ padding: '14px 10px' }}>
                          <div className="lobby-empty-desc">
                            暂无在线设备或历史房间，点击右侧「创建房间」即可生成 6 位数字口令开启直传。
                          </div>
                        </div>
                      )}
                    </div>
                  </div>

                  <div className="sidebar-e2ee-badge">
                    <ShieldCheck size={16} style={{ color: 'var(--success)', flexShrink: 0 }} aria-hidden="true" />
                    <div style={{ minWidth: 0 }}>
                      <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-primary)' }}>
                        端到端加密已启用
                      </div>
                      <div className="mono" style={{ fontSize: 10.5, color: 'var(--accent)' }}>
                        AES-256-GCM + DTLS 1.3
                      </div>
                    </div>
                  </div>
                </aside>

                {/* ---------- 中栏：P2P 直连控制流 + 最近传输卡片 + 虚线拖拽区 ---------- */}
                <section className="p2p-col-panel">
                  <div className="p2p-center-head">
                    <div style={{ minWidth: 0 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                        <span style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)' }}>
                          BayChat · P2P 跨端工作台
                        </span>
                        <span className="pill pill-emerald mono" style={{ fontSize: 10.5, height: 22 }}>
                          <span className="status-dot emerald" />
                          LAN Direct · 零限速直连
                        </span>
                      </div>
                      <div style={{ fontSize: 11.5, color: 'var(--text-muted)', marginTop: 2 }}>
                        1~7 天服务器限时缓存或阅后即焚 · 无注册零压缩端到端传输
                      </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                      <button
                        type="button"
                        className="btn btn-soft btn-sm"
                        onClick={() => setPickupSendOpen(true)}
                      >
                        <Package size={13} aria-hidden="true" /> 发取件码
                      </button>
                      <button
                        type="button"
                        className="btn btn-glass btn-sm"
                        onClick={() => setPickupLookupOpen(true)}
                      >
                        <KeyRound size={13} aria-hidden="true" /> 输码取件
                      </button>
                    </div>
                  </div>

                  <div className="p2p-center-body geek-scroll">
                    <form onSubmit={handleUnifiedSubmit} className="lobby-hero-bar" style={{ border: '1px solid var(--separator)' }}>
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
                            placeholder="留空直接新建直连房间，或输入 849205 / bay-8888"
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
                                    : '创建直连房间'}
                            </span>
                            <ArrowRight size={17} aria-hidden="true" />
                          </button>
                          {hasInputCode && (
                            <button
                              type="button"
                              className="btn btn-soft btn-xl"
                              disabled={submitting || randomJoining}
                              onClick={() => void handleDirectCreateNewRoom()}
                              title="忽略当前输入框内容直接创建新房间"
                            >
                              <Plus size={16} aria-hidden="true" />
                              <span>新建房间</span>
                            </button>
                          )}
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
                          {showAdvanced ? <ChevronUp size={14} aria-hidden="true" /> : <ChevronDown size={14} aria-hidden="true" />}
                        </button>
                      </div>

                      {showAdvanced && (
                        <div className="lobby-advanced-panel">
                          <div className="lobby-advanced-grid">
                            <div className="lobby-field-group">
                              <span className="lobby-field-label">房间形态</span>
                              <div className="segmented full">
                                <button
                                  type="button"
                                  className={`seg-item ${mode === 'ephemeral' ? 'active' : ''}`}
                                  onClick={() => handleModeChange('ephemeral')}
                                >
                                  <Flame size={13} aria-hidden="true" /> 临时房间
                                </button>
                                <button
                                  type="button"
                                  className={`seg-item ${mode === 'fixed' ? 'active' : ''}`}
                                  onClick={() => handleModeChange('fixed')}
                                >
                                  <Pin size={13} aria-hidden="true" /> 常驻基地
                                </button>
                              </div>
                            </div>

                            <div className="lobby-field-group">
                              <span className="lobby-field-label">访问密码（可选，至少 5 位数字）</span>
                              <div className="input-group">
                                <span className="input-prefix-icon">
                                  <Lock size={14} aria-hidden="true" />
                                </span>
                                <input
                                  className="field-input with-prefix"
                                  type="password"
                                  inputMode="numeric"
                                  value={roomPassword}
                                  onChange={(e) => {
                                    setRoomPassword(e.target.value.replace(/\D/g, '').slice(0, 20));
                                    setErrorText('');
                                  }}
                                  placeholder="至少 5 位数字，留空表示免密进入"
                                />
                              </div>
                            </div>
                          </div>

                          <div className="lobby-advanced-grid">
                            <div className="lobby-field-group">
                              <div className="lobby-field-label-row">
                                <span className="lobby-field-label">服务器缓存保鲜期</span>
                                <span className="lobby-field-hint">
                                  {retentionDays === 0 ? '零缓存，退出即焚' : `最后活跃起保留 ${retentionDays} 天`}
                                </span>
                              </div>
                              <div className="lobby-chip-group">
                                {RETENTION_STEPS.map((st) => (
                                  <button
                                    key={st.days}
                                    type="button"
                                    className={`btn btn-sm lobby-touch-pill ${retentionDays === st.days ? 'btn-jelly' : 'btn-glass'}`}
                                    onClick={() => setRetentionDays(st.days)}
                                  >
                                    {st.label}
                                  </button>
                                ))}
                              </div>
                            </div>

                            <div className="lobby-field-group">
                              <div className="lobby-field-label-row">
                                <span className="lobby-field-label">单文件传输上限</span>
                                <span className="lobby-field-hint">
                                  {maxFileSizeMB === 0 ? '全房不限体积' : `≤ ${maxFileSizeMB}MB`}
                                </span>
                              </div>
                              <div className="lobby-chip-group">
                                {FILE_LIMIT_PRESETS.filter((p) => {
                                  const cap = serverConfig?.maxAttachmentMB || 0;
                                  if (cap <= 0) return true;
                                  return p.mb > 0 && p.mb <= cap;
                                }).map((p) => (
                                  <button
                                    key={p.mb}
                                    type="button"
                                    className={`btn btn-sm lobby-touch-pill ${maxFileSizeMB === p.mb ? 'btn-jelly' : 'btn-glass'}`}
                                    onClick={() => setMaxFileSizeMB(p.mb)}
                                  >
                                    {p.label}
                                  </button>
                                ))}
                              </div>
                            </div>
                          </div>

                          <div className="lobby-advanced-switches">
                            <Switch
                              checked={privatePairOnly}
                              onChange={setPrivatePairOnly}
                              label="双人私密传输（上限 2 人）"
                              description="仅允许两人点对点直连"
                            />
                            {mode === 'fixed' && (
                              <Switch
                                checked={saveToFavorites}
                                onChange={setSaveToFavorites}
                                label="创建后固定到常驻收藏"
                                description="方便在各端列表中一键进入"
                              />
                            )}
                          </div>
                        </div>
                      )}
                    </form>

                    {/* 最近传输任务流水卡片流（1:1 对标 P2PLink 传输卡片样式） */}
                    {transferRecords.length > 0 ? (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                        <div className="sidebar-section-head">
                          <span>最近直传记录 ({transferRecords.length})</span>
                          <button
                            type="button"
                            className="btn btn-ghost btn-sm"
                            onClick={() => setActiveTab('transfer')}
                          >
                            全部记录
                          </button>
                        </div>
                        {transferRecords.slice(0, 4).map((rec) => (
                          <div key={rec.id} className="p2p-transfer-item">
                            <div className="p2p-transfer-head">
                              <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
                                <div className="p2p-transfer-icon">
                                  <HardDrive size={17} aria-hidden="true" />
                                </div>
                                <div style={{ minWidth: 0 }}>
                                  <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                    {rec.fileName}
                                  </div>
                                  <div className="mono" style={{ fontSize: 11, color: 'var(--text-muted)', display: 'flex', gap: 6, marginTop: 2 }}>
                                    <span>{formatBytes(rec.fileSize)}</span>
                                    <span>·</span>
                                    <span style={{ color: 'var(--accent)' }}>
                                      {rec.direction === 'send' ? '发出直传' : '接收直传'}
                                    </span>
                                    <span>·</span>
                                    <span>#{rec.roomCode}</span>
                                  </div>
                                </div>
                              </div>
                              <span className="pill pill-emerald" style={{ fontSize: 11, flexShrink: 0 }}>
                                <CheckCircle2 size={12} aria-hidden="true" /> 已完成
                              </span>
                            </div>
                            <div className="p2p-progress-track">
                              <div className="p2p-progress-fill" style={{ width: '100%' }} />
                            </div>
                            <div className="mono" style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: 'var(--text-muted)' }}>
                              <span>100% · {formatRelativeTime(rec.createdAt)}</span>
                              <span style={{ color: 'var(--success)' }}>
                                {rec.verified ? '✓ SHA-256 校验通过' : '✓ 分片写入完成'}
                              </span>
                            </div>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <div className="p2p-transfer-item" style={{ borderStyle: 'dashed' }}>
                        <div className="p2p-transfer-head">
                          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                            <div className="p2p-transfer-icon" style={{ background: 'var(--accent-soft)', color: 'var(--accent)' }}>
                              <Wifi size={17} aria-hidden="true" />
                            </div>
                            <div>
                              <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)' }}>
                                跨端直传引擎就绪 · 创建或进入房间即可互传任意文件
                              </div>
                              <div style={{ fontSize: 11.5, color: 'var(--text-muted)', marginTop: 2 }}>
                                进房后支持微信式语音转文字、AI 纠错、多端剪贴板同步与整个文件夹 1:1 打包直传
                              </div>
                            </div>
                          </div>
                        </div>
                      </div>
                    )}
                  </div>

                  {/* 底部快捷控制栏 + 显眼青绿虚线直传入口 */}
                  <div className="p2p-center-footer">
                    <div className="p2p-quick-strip">
                      <Switch
                        checked={prefs.autoAcceptSmallFiles}
                        onChange={(v) => onUpdatePrefs({ ...prefs, autoAcceptSmallFiles: v })}
                        label={`自动接收 ≤${prefs.autoAcceptMaxMB}MB 文件`}
                      />
                      <button
                        type="button"
                        className="btn btn-soft btn-sm"
                        disabled={submitting}
                        onClick={() => void handleDirectCreateNewRoom()}
                      >
                        <Plus size={13} aria-hidden="true" /> 一键建房开聊
                      </button>
                    </div>

                    <div className="p2p-dropzone-box">
                      <button
                        type="button"
                        onClick={() => void handleDirectCreateNewRoom()}
                        style={{
                          border: 'none',
                          background: 'transparent',
                          padding: 0,
                          cursor: 'pointer',
                          textAlign: 'left',
                          flex: 1,
                          minWidth: 0,
                        }}
                        className="p2p-dropzone-left"
                      >
                        <div className="p2p-dropzone-icon">
                          <FolderUp size={17} aria-hidden="true" />
                        </div>
                        <div style={{ minWidth: 0 }}>
                          <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--accent)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            P2P 极速直传（点击立即建房，进房后可直接拖入文件或整个文件夹）
                          </div>
                          <div style={{ fontSize: 10.5, color: 'var(--text-muted)', marginTop: 2 }}>
                            支持目录树 1:1 还原 · 多流 4MB 切片断点续传 · 零压缩原画直发
                          </div>
                        </div>
                      </button>

                      <button
                        type="button"
                        className="btn btn-glass btn-sm"
                        onClick={() => setPickupSendOpen(true)}
                      >
                        <Package size={13} aria-hidden="true" /> 6 位取件码
                      </button>
                    </div>
                  </div>
                </section>

                {/* ---------- 右栏：24 分片矩阵 + 局域网发现开关 + 实时吞吐波形 ---------- */}
                <aside className="p2p-col-panel p2p-col-right">
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                    {/* 模块 1：断点续传 & 4MB 分片矩阵 */}
                    <div className="p2p-card">
                      <div className="p2p-card-head">
                        <span className="p2p-card-title">断点续传 &amp; 4MB 分片</span>
                        <span className="p2p-badge mono">24 Chunks</span>
                      </div>
                      <div className="p2p-file-name mono" style={{ fontSize: 10.5, color: 'var(--text-muted)' }}>
                        {transferRecords[0] ? `最近: ${transferRecords[0].fileName}` : '等待进房后开启分片监控'}
                      </div>
                      <div className="p2p-chunk-grid">
                        {Array.from({ length: 24 }, (_, idx) => (
                          <div
                            key={idx}
                            className={`p2p-chunk-cell ${transferRecords.length > 0 ? 'done' : 'pending'}`}
                          >
                            {idx + 1}
                          </div>
                        ))}
                      </div>
                      <button
                        type="button"
                        className="btn btn-glass btn-sm"
                        style={{ width: '100%' }}
                        onClick={() => setActiveTab('transfer')}
                      >
                        <History size={12} aria-hidden="true" /> 查看全部传输与断点队列
                      </button>
                    </div>

                    {/* 模块 2：真实局域网发现开关 */}
                    <div className="p2p-card">
                      <Switch
                        checked={prefs.nearbyDiscoverable}
                        onChange={(v) => onUpdatePrefs({ ...prefs, nearbyDiscoverable: v })}
                        label="局域网发现 (同网雷达)"
                        description="开启后同 Wi-Fi 下可直接点击对方邀请直传"
                      />
                      <div
                        className="mono"
                        style={{
                          fontSize: 10.5,
                          padding: '5px 8px',
                          borderRadius: 8,
                          background: 'var(--surface)',
                          color: prefs.nearbyDiscoverable ? 'var(--accent)' : 'var(--text-muted)',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}
                      >
                        {prefs.nearbyDiscoverable
                          ? `同网雷达广播活跃 · 已发现 ${nearbyDevices.length} 台设备`
                          : '同网雷达已静默 (仅手动口令直连)'}
                      </div>
                    </div>
                  </div>

                  {/* 模块 3：实时吞吐波形面板 */}
                  <div className="p2p-card">
                    <div className="p2p-card-head">
                      <span className="p2p-card-title" style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                        <Activity size={13} style={{ color: 'var(--accent)' }} aria-hidden="true" />
                        实时吞吐波形
                      </span>
                      <span className="p2p-speed-now mono">待命就绪</span>
                    </div>
                    <div className="p2p-wave-box">
                      <svg viewBox="0 0 240 68" className="p2p-wave-svg" aria-hidden="true">
                        <defs>
                          <linearGradient id="bayLobbyWaveGrad" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="0%" stopColor="var(--accent)" stopOpacity="0.4" />
                            <stop offset="100%" stopColor="var(--accent)" stopOpacity="0.0" />
                          </linearGradient>
                        </defs>
                        <line x1="0" y1="18" x2="240" y2="18" stroke="var(--separator)" strokeOpacity="0.4" strokeDasharray="3 3" />
                        <line x1="0" y1="42" x2="240" y2="42" stroke="var(--separator)" strokeOpacity="0.4" strokeDasharray="3 3" />
                        <path d="M 0,58 L 240,58 L 240,68 L 0,68 Z" fill="url(#bayLobbyWaveGrad)" />
                        <path d="M 0,58 L 240,58" fill="none" stroke="var(--accent)" strokeWidth="2" strokeLinecap="round" />
                      </svg>
                    </div>
                    <div className="p2p-wave-foot mono">
                      <span>协议: WebRTC SCTP</span>
                      <span>累计传输: {transferRecords.length} 次</span>
                    </div>
                  </div>
                </aside>
              </div>
            )}

            {/* ================== TAB 2: 发现广场（公开房 + 纸飞机） ================== */}
            {activeTab === 'discover' && (
              <div className="workspace-tab-stack">
                {/* 公开房间广场 */}
                <section className="glass-bento lobby-card">
                  <div className="lobby-card-head lobby-discover-head">
                    <div className="lobby-card-title-col">
                      <h2 className="lobby-card-title">
                        <Globe size={17} className="lobby-card-title-icon" aria-hidden="true" />
                        <span>公开房间广场</span>
                        <span className="pill pill-accent mono">{filteredPublicRooms.length}</span>
                      </h2>
                      <p className="lobby-card-sub">
                        以下房间已开启免密随机进入，可直接加入共享文件或参与实时讨论
                      </p>
                    </div>
                    <div className="lobby-discover-actions">
                      <div className="input-group lobby-inline-search">
                        <span className="input-prefix-icon">
                          <Search size={14} aria-hidden="true" />
                        </span>
                        <input
                          className="field-input with-prefix lobby-inline-search-input"
                          value={publicSearch}
                          onChange={(e) => setPublicSearch(e.target.value)}
                          placeholder="搜索房号或主题..."
                          aria-label="搜索公开房间"
                        />
                      </div>
                      <button
                        type="button"
                        className="btn btn-jelly btn-sm lobby-touch-pill"
                        onClick={() => setCreatePublicOpen(true)}
                      >
                        <Plus size={14} aria-hidden="true" /> 新建公开房
                      </button>
                      <button
                        type="button"
                        className="btn btn-soft btn-sm lobby-touch-pill"
                        disabled={randomJoining}
                        onClick={() => void handleRandomJoin()}
                      >
                        <Dices size={14} aria-hidden="true" /> 随机进入
                      </button>
                      <button
                        type="button"
                        className="btn btn-ghost btn-icon lobby-touch-icon"
                        disabled={publicRoomsLoading}
                        aria-label="刷新公开房间"
                        onClick={() => void loadPublicRooms()}
                      >
                        <RefreshCw size={15} className={publicRoomsLoading ? 'bay-spin' : ''} aria-hidden="true" />
                      </button>
                    </div>
                  </div>

                  {publicRoomsError ? (
                    <div className="alert-strip">{publicRoomsError}</div>
                  ) : publicRoomsLoading && publicRooms.length === 0 ? (
                    <div className="lobby-empty-card centered">
                      <div className="lobby-empty-desc">正在加载当前活跃的公开房间...</div>
                    </div>
                  ) : filteredPublicRooms.length === 0 ? (
                    <div className="lobby-empty-card centered">
                      <div className="lobby-empty-title">
                        <Globe size={16} className="lobby-card-title-icon" aria-hidden="true" />
                        <span>{publicSearch ? '未匹配到相关公开房间' : '暂无公开开放的房间'}</span>
                      </div>
                      <div className="lobby-empty-desc">
                        点击右上角「新建公开房」即可发起一间大家都能进入的文件交流室。
                      </div>
                    </div>
                  ) : (
                    <div className="lobby-planes-grid">
                      {filteredPublicRooms.map((r) => (
                        <div key={r.code} className="lobby-plane-card">
                          <div className="lobby-plane-head">
                            <div className="mono lobby-room-code">
                              <span>#{r.code}</span>
                              <span className="pill pill-emerald">公开可进</span>
                            </div>
                            <span className="pill pill-neutral mono">
                              <Users size={11} aria-hidden="true" /> {r.peerCount}/{r.maxPeers}
                            </span>
                          </div>
                          <div className="lobby-plane-msg">
                            {r.topic?.trim() || '公开文件共享与即时传输房间'}
                          </div>
                          <div className="lobby-plane-foot">
                            <span className="mono lobby-plane-time">
                              {r.retentionDays === 0 ? '阅后即焚' : `保留 ${r.retentionDays} 天`} · {r.maxFileSizeMB === 0 ? '不限大小' : `≤${r.maxFileSizeMB}MB`}
                            </span>
                            <button
                              type="button"
                              className="btn btn-soft btn-sm lobby-touch-pill"
                              onClick={() => onEnterRoom(r.code, '', r.mode, false, findStoredOwnerToken(r.code))}
                            >
                              加入房间 <ArrowRight size={13} aria-hidden="true" />
                            </button>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </section>

                {/* 纸飞机公开分享清单 */}
                <section className="glass-bento lobby-card">
                  <div className="lobby-card-head lobby-discover-head">
                    <div className="lobby-card-title-col">
                      <h2 className="lobby-card-title">
                        <Send size={16} className="lobby-card-title-icon" aria-hidden="true" />
                        <span>纸飞机文件清单</span>
                        <span className="pill pill-neutral mono">{filteredPlanes.length}</span>
                      </h2>
                      <p className="lobby-card-sub">
                        房间成员主动放飞的文件清单快照，若已勾选授权且房主未改密可直接免密进房下载
                      </p>
                    </div>
                    <div className="lobby-discover-actions">
                      <div className="input-group lobby-inline-search">
                        <span className="input-prefix-icon">
                          <Search size={14} aria-hidden="true" />
                        </span>
                        <input
                          className="field-input with-prefix lobby-inline-search-input"
                          value={planeSearch}
                          onChange={(e) => setPlaneSearch(e.target.value)}
                          placeholder="搜索文件名、房号或发送人..."
                          aria-label="搜索纸飞机"
                        />
                      </div>
                      <button
                        type="button"
                        className="btn btn-ghost btn-icon lobby-touch-icon"
                        disabled={planesLoading}
                        aria-label="刷新纸飞机广场"
                        onClick={() => void loadPlanes()}
                      >
                        <RefreshCw size={15} className={planesLoading ? 'bay-spin' : ''} aria-hidden="true" />
                      </button>
                    </div>
                  </div>

                  {planesError ? (
                    <div className="alert-strip">{planesError}</div>
                  ) : planesLoading && planes.length === 0 ? (
                    <div className="lobby-empty-card centered">
                      <div className="lobby-empty-desc">正在加载纸飞机清单...</div>
                    </div>
                  ) : filteredPlanes.length === 0 ? (
                    <div className="lobby-empty-card centered">
                      <div className="lobby-empty-title">
                        <Send size={16} className="lobby-card-title-icon" aria-hidden="true" />
                        <span>{planeSearch ? '未匹配到相关纸飞机' : '暂无公开纸飞机'}</span>
                      </div>
                      <div className="lobby-empty-desc">
                        进入任意房间并收发文件后，在房间右上角菜单「放飞纸飞机」即可分享至此。
                      </div>
                    </div>
                  ) : (
                    <div className="lobby-planes-grid">
                      {filteredPlanes.map((p) => {
                        const totalSize = p.files.reduce((sum, f) => sum + (f.size || 0), 0);
                        return (
                          <button
                            key={p.id}
                            type="button"
                            className="lobby-plane-card"
                            onClick={() => setViewingPlane(p)}
                          >
                            <div className="lobby-plane-head">
                              <div className="lobby-plane-sender">
                                <Avatar name={p.senderName} avatar={p.senderAvatar} size={32} />
                                <div className="lobby-plane-sender-meta">
                                  <div className="lobby-plane-sender-name">{p.senderName}</div>
                                  <div className="lobby-plane-room mono">#{p.roomCode}</div>
                                </div>
                              </div>
                              {p.allowEntry ? (
                                <span className="pill pill-emerald">
                                  <Unlock size={10} aria-hidden="true" /> 允许直进
                                </span>
                              ) : (
                                <span className="pill pill-neutral">
                                  <Lock size={10} aria-hidden="true" /> {p.hasPassword ? '需密码' : '需授权'}
                                </span>
                              )}
                            </div>

                            {p.message && <div className="lobby-plane-msg">{p.message}</div>}

                            <div className="lobby-plane-files">
                              {p.files.slice(0, 3).map((f, idx) => (
                                <div key={`${p.id}_f_${idx}`} className="lobby-plane-file-row">
                                  {renderPlaneKindIcon(f.kind)}
                                  <span className="lobby-plane-file-name">{f.name}</span>
                                  {f.size > 0 && (
                                    <span className="mono lobby-plane-file-size">{formatBytes(f.size)}</span>
                                  )}
                                </div>
                              ))}
                              {p.files.length > 3 && (
                                <div className="lobby-plane-more">
                                  另有 {p.files.length - 3} 个文件，点击预览详情
                                </div>
                              )}
                            </div>

                            <div className="lobby-plane-foot">
                              <span className="mono lobby-plane-time">
                                {p.files.length} 个文件{totalSize > 0 ? ` · ${formatBytes(totalSize)}` : ''} · {formatRelativeTime(p.createdAt)}
                              </span>
                              <span className="lobby-plane-cta">查看详情</span>
                            </div>
                          </button>
                        );
                      })}
                    </div>
                  )}
                </section>
              </div>
            )}

            {/* ================== TAB 3: 传输与取件码中心 ================== */}
            {activeTab === 'transfer' && (
              <div className="workspace-tab-stack">
                <section className="lobby-kpi-strip" aria-label="传输概览">
                  <div className="glass-bento lobby-kpi-card">
                    <span className="lobby-kpi-label">生效取件码</span>
                    <strong className="lobby-kpi-value mono">{myPickups.length} 枚</strong>
                  </div>
                  <div className="glass-bento lobby-kpi-card">
                    <span className="lobby-kpi-label">历史传输记录</span>
                    <strong className="lobby-kpi-value mono">{transferRecords.length} 条</strong>
                  </div>
                  <div className="glass-bento lobby-kpi-card">
                    <span className="lobby-kpi-label">累计传输体积</span>
                    <strong className="lobby-kpi-value mono">{formatBytes(totalTransferBytes)}</strong>
                  </div>
                </section>

                <div className="lobby-split-grid">
                  <section className="glass-bento lobby-card">
                    <div className="lobby-card-head lobby-discover-head">
                      <div className="lobby-card-title-col">
                        <h2 className="lobby-card-title">
                          <Package size={17} className="lobby-card-title-icon" aria-hidden="true" />
                          <span>我的取件码（服务器暂存）</span>
                          <span className="pill pill-accent mono">{myPickups.length}</span>
                        </h2>
                        <p className="lobby-card-sub">
                          随时复制取件码或直链发给好友，支持直接预览取件页与撤销物理清理
                        </p>
                      </div>
                      <div className="lobby-discover-actions">
                        <button
                          type="button"
                          className="btn btn-jelly btn-sm lobby-touch-pill"
                          onClick={() => setPickupSendOpen(true)}
                        >
                          <Package size={14} aria-hidden="true" /> 发取件码
                        </button>
                        <button
                          type="button"
                          className="btn btn-soft btn-sm lobby-touch-pill"
                          onClick={() => setPickupLookupOpen(true)}
                        >
                          <KeyRound size={14} aria-hidden="true" /> 输码取件
                        </button>
                      </div>
                    </div>

                    {myPickups.length === 0 ? (
                      <div className="lobby-empty-card centered">
                        <div className="lobby-empty-title">
                          <Package size={16} className="lobby-card-title-icon" aria-hidden="true" />
                          <span>当前没有生效中的取件码</span>
                        </div>
                        <div className="lobby-empty-desc">
                          点击右上角「发取件码」上传文件后，系统会在此列表集中展示并跟踪有效期。
                        </div>
                      </div>
                    ) : (
                      <div className="lobby-room-list">
                        {myPickups.map((item) => (
                          <div key={item.code} className="lobby-room-item">
                            <div className="lobby-room-meta">
                              <div className="mono lobby-room-code">
                                <span>#{item.code}</span>
                                {item.burnAfterRead && (
                                  <span className="pill pill-warm">
                                    <Flame size={10} aria-hidden="true" /> 即焚
                                  </span>
                                )}
                                {item.hasPassword && <Lock size={12} className="lobby-room-lock" aria-hidden="true" />}
                              </div>
                              <div className="lobby-room-sub mono">
                                {item.fileCount} 个文件 · {formatBytes(item.totalSize)} · {formatExpiryTime(item.expiresAt)}
                              </div>
                            </div>
                            <div className="lobby-card-actions">
                              <button
                                type="button"
                                className="btn btn-soft btn-sm lobby-touch-pill"
                                onClick={() => onOpenPickupPage(item.code)}
                              >
                                <ExternalLink size={13} aria-hidden="true" /> 提取页
                              </button>
                              <button
                                type="button"
                                className="btn btn-ghost btn-icon lobby-touch-icon"
                                title="复制取件链接"
                                aria-label={`复制取件码 ${item.code} 的链接`}
                                onClick={async () => {
                                  const url = `${window.location.origin}/p/${item.code}`;
                                  if (await copyTextToClipboard(url)) {
                                    onToast(`已复制 #${item.code} 提取链接`);
                                  }
                                }}
                              >
                                <Copy size={14} aria-hidden="true" />
                              </button>
                              <button
                                type="button"
                                className="btn btn-danger-soft btn-icon lobby-touch-icon"
                                title="撤销并销毁"
                                aria-label={`撤销取件码 ${item.code}`}
                                onClick={() => onRevokePickup(item)}
                              >
                                <Trash2 size={14} aria-hidden="true" />
                              </button>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </section>

                  {/* 历史文件传输记录 */}
                  <section className="glass-bento lobby-card">
                    <div className="lobby-card-head lobby-discover-head">
                      <div className="lobby-card-title-col">
                        <h2 className="lobby-card-title">
                          <History size={17} className="lobby-card-title-icon" aria-hidden="true" />
                          <span>本机 P2P 传输历史</span>
                          <span className="pill pill-neutral mono">{filteredTransfers.length}</span>
                        </h2>
                      </div>
                      <div className="lobby-discover-actions">
                        <div className="segmented">
                          {(
                            [
                              { id: 'all', label: '全部' },
                              { id: 'send', label: '发出' },
                              { id: 'receive', label: '接收' },
                            ] as const
                          ).map((f) => (
                            <button
                              key={f.id}
                              type="button"
                              className={`seg-item ${transferFilter === f.id ? 'active' : ''}`}
                              onClick={() => setTransferFilter(f.id)}
                            >
                              {f.label}
                            </button>
                          ))}
                        </div>
                        {transferRecords.length > 0 && (
                          <button
                            type="button"
                            className="btn btn-ghost btn-sm lobby-clear-recent-btn"
                            onClick={onClearTransferRecords}
                          >
                            清空历史
                          </button>
                        )}
                      </div>
                    </div>

                    {filteredTransfers.length === 0 ? (
                      <div className="lobby-empty-card centered">
                        <div className="lobby-empty-title">
                          <FileIcon size={16} className="lobby-card-title-icon" aria-hidden="true" />
                          <span>暂无 P2P 文件传输记录</span>
                        </div>
                        <div className="lobby-empty-desc">
                          在房间内完成任意文件收发后，都会自动记录文件名、体积、SHA-256 校验结果与来源房间。
                        </div>
                      </div>
                    ) : (
                      <div className="lobby-room-list">
                        {filteredTransfers.map((tr) => (
                          <div key={tr.id} className="lobby-room-item">
                            <div className="lobby-room-meta">
                              <div className="lobby-room-code">
                                <span className={`pill ${tr.direction === 'send' ? 'pill-accent' : 'pill-emerald'}`}>
                                  {tr.direction === 'send' ? '发出' : '接收'}
                                </span>
                                <span className="workspace-file-title">{tr.fileName}</span>
                                {tr.verified && (
                                  <span className="pill pill-emerald" title="SHA-256 完整校验通过">
                                    <ShieldCheck size={10} aria-hidden="true" /> 已校验
                                  </span>
                                )}
                              </div>
                              <div className="lobby-room-sub mono">
                                {formatBytes(tr.fileSize)} · 房间 #{tr.roomCode} · {tr.peerName || '对端伙伴'} · {formatRelativeTime(tr.createdAt)}
                              </div>
                            </div>
                            <button
                              type="button"
                              className="btn btn-ghost btn-icon lobby-touch-icon"
                              title="删除该条传输记录"
                              aria-label={`删除 ${tr.fileName}`}
                              onClick={() => onRemoveTransferRecord(tr.id)}
                            >
                              <Trash2 size={14} aria-hidden="true" />
                            </button>
                          </div>
                        ))}
                      </div>
                    )}
                  </section>
                </div>
              </div>
            )}

            {/* ================== TAB 4: 我的（资料/通知/外观/收藏/屏蔽/存储） ================== */}
            {activeTab === 'mine' && (
              <div className="workspace-tab-stack">
                <section className="glass-bento lobby-card">
                  <div className="lobby-mine-profile-row">
                    <div className="lobby-mine-profile-left">
                      <Avatar name={profile.name} avatar={profile.avatar} size={56} />
                      <div className="lobby-profile-info">
                        <div className="lobby-profile-name">{profile.name}</div>
                        <div className="lobby-profile-sub mono">ID: {profile.clientId}</div>
                      </div>
                    </div>
                    <div className="lobby-discover-actions">
                      <button
                        type="button"
                        className="btn btn-glass btn-sm lobby-touch-pill"
                        onClick={() =>
                          onQuickRandomProfile({
                            ...profile,
                            name: generateRandomName(),
                            avatar: pickRandomPresetAvatar(),
                          })
                        }
                      >
                        <Dices size={14} aria-hidden="true" /> 随机换身份
                      </button>
                      <button
                        type="button"
                        className="btn btn-soft btn-sm lobby-touch-pill"
                        onClick={onEditProfile}
                      >
                        <Edit3 size={14} aria-hidden="true" /> 编辑名片
                      </button>
                    </div>
                  </div>
                </section>

                <section className="lobby-main-grid">
                  {/* 消息通知与提醒 */}
                  <div className="glass-bento lobby-card">
                    <div className="lobby-card-head">
                      <h2 className="lobby-card-title">
                        <Bell size={16} className="lobby-card-title-icon" aria-hidden="true" />
                        <span>消息与桌面通知</span>
                      </h2>
                      <span
                        className={`pill ${
                          notifPermission === 'granted'
                            ? 'pill-emerald'
                            : notifPermission === 'denied'
                              ? 'pill-warm'
                              : 'pill-neutral'
                        }`}
                      >
                        {notifPermission === 'granted'
                          ? '已开启'
                          : notifPermission === 'denied'
                            ? '被浏览器拦截'
                            : notifPermission === 'unsupported'
                              ? '不支持桌面通知'
                              : '未授权'}
                      </span>
                    </div>

                    <div className="lobby-mine-setting-stack">
                      {isNotificationSupported() && notifPermission !== 'granted' && (
                        <button
                          type="button"
                          className="btn btn-soft lobby-touch-pill"
                          onClick={() => void handleRequestNotifications()}
                        >
                          <Bell size={14} aria-hidden="true" /> 授权并开启浏览器通知
                        </button>
                      )}
                      <Switch
                        checked={prefs.desktopNotifications}
                        onChange={(v) => {
                          if (v && notifPermission === 'default') {
                            void handleRequestNotifications();
                          } else {
                            onUpdatePrefs({ ...prefs, desktopNotifications: v });
                          }
                        }}
                        label="后台收到新消息时弹出系统通知"
                        description="切出当前标签页或挂起房间回大厅时触发通知与标题闪烁提醒"
                      />
                      <Switch
                        checked={prefs.soundEnabled}
                        onChange={(v) => onUpdatePrefs({ ...prefs, soundEnabled: v })}
                        label="清脆提示音效"
                        description="收到文字、@提及与文件传输完成时播放提示音"
                      />
                    </div>
                  </div>

                  {/* 外观与传输默认设置 */}
                  <div className="glass-bento lobby-card">
                    <div className="lobby-card-head">
                      <h2 className="lobby-card-title">
                        <SlidersHorizontal size={16} className="lobby-card-title-icon" aria-hidden="true" />
                        <span>外观与接收偏好</span>
                      </h2>
                    </div>

                    <div className="lobby-mine-setting-stack">
                      <div className="sidebar-appearance-block">
                        <span className="sidebar-setting-label">外观模式</span>
                        <AppearancePicker appearance={appearance} onSelect={onSelectAppearance} />
                      </div>
                      <div className="sidebar-theme-row">
                        <span className="sidebar-setting-label">主题色</span>
                        <PalettePicker theme={theme} onSelect={onSelectTheme} />
                      </div>

                      <div className="popover-divider" />

                      <Switch
                        checked={prefs.autoAcceptSmallFiles}
                        onChange={(v) => onUpdatePrefs({ ...prefs, autoAcceptSmallFiles: v })}
                        label={`自动免确认接收 ≤${prefs.autoAcceptMaxMB}MB 文件`}
                      />
                      <div className={`lobby-chip-group ${!prefs.autoAcceptSmallFiles ? 'disabled-group' : ''}`}>
                        {AUTO_ACCEPT_THRESHOLDS.map((mb) => (
                          <button
                            key={mb}
                            type="button"
                            disabled={!prefs.autoAcceptSmallFiles}
                            className={`btn btn-sm ${prefs.autoAcceptMaxMB === mb && prefs.autoAcceptSmallFiles ? 'btn-jelly' : 'btn-glass'}`}
                            onClick={() => onUpdatePrefs({ ...prefs, autoAcceptMaxMB: mb })}
                          >
                            {mb}MB
                          </button>
                        ))}
                      </div>

                      <div className="lobby-field-group">
                        <span className="lobby-field-label">默认图片发送画质</span>
                        <div className="segmented full">
                          <button
                            type="button"
                            className={`seg-item ${prefs.imageSendMode === 'original' ? 'active' : ''}`}
                            onClick={() => onUpdatePrefs({ ...prefs, imageSendMode: 'original' })}
                          >
                            原图无损
                          </button>
                          <button
                            type="button"
                            className={`seg-item ${prefs.imageSendMode === 'compressed' ? 'active' : ''}`}
                            onClick={() => onUpdatePrefs({ ...prefs, imageSendMode: 'compressed' })}
                          >
                            智能压缩省流
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>
                </section>

                {/* 我的收藏 + 屏蔽/本地存储双栏 */}
                <section className="lobby-split-grid">
                  {/* 我的收藏 */}
                  <div className="glass-bento lobby-card">
                    <div className="lobby-card-head lobby-discover-head">
                      <div className="lobby-card-title-col">
                        <h2 className="lobby-card-title">
                          <Bookmark size={16} className="lobby-card-title-icon" aria-hidden="true" />
                          <span>我的收藏</span>
                          <span className="pill pill-neutral mono">{favorites.length}</span>
                        </h2>
                      </div>
                      <div className="lobby-discover-actions">
                        {favorites.length > 0 && (
                          <div className="input-group lobby-inline-search">
                            <span className="input-prefix-icon">
                              <Search size={14} aria-hidden="true" />
                            </span>
                            <input
                              className="field-input with-prefix lobby-inline-search-input"
                              value={favSearch}
                              onChange={(e) => setFavSearch(e.target.value)}
                              placeholder="搜索收藏内容..."
                              aria-label="搜索收藏"
                            />
                          </div>
                        )}
                        {favorites.length > 0 && (
                          <button
                            type="button"
                            className="btn btn-ghost btn-sm lobby-clear-recent-btn"
                            onClick={onClearFavorites}
                          >
                            清空收藏
                          </button>
                        )}
                      </div>
                    </div>

                    {filteredFavorites.length === 0 ? (
                      <div className="lobby-empty-card centered">
                        <div className="lobby-empty-title">
                          <Bookmark size={16} className="lobby-card-title-icon" aria-hidden="true" />
                          <span>{favSearch ? '未找到匹配收藏项' : '暂无收藏内容'}</span>
                        </div>
                        <div className="lobby-empty-desc">
                          在聊天消息栏点击书签图标，即可把关键链接、文件记录与笔记永久保存于此。
                        </div>
                      </div>
                    ) : (
                      <div className="lobby-room-list">
                        {filteredFavorites.map((fav) => (
                          <div key={fav.id} className="lobby-room-item">
                            <div className="lobby-room-meta">
                              <div className="lobby-room-code">
                                <span className="pill pill-neutral">#{fav.roomCode}</span>
                                <span className="workspace-file-title">{fav.fileName || fav.text?.slice(0, 64) || '媒体收藏'}</span>
                              </div>
                              <div className="lobby-room-sub">
                                来自 {fav.senderName} · {formatRelativeTime(fav.createdAt)}
                                {fav.fileSize ? ` · ${formatBytes(fav.fileSize)}` : ''}
                              </div>
                            </div>
                            <div className="lobby-card-actions">
                              {(fav.text || fav.fileName) && (
                                <button
                                  type="button"
                                  className="btn btn-ghost btn-icon lobby-touch-icon"
                                  title="复制内容"
                                  aria-label="复制收藏内容"
                                  onClick={async () => {
                                    if (await copyTextToClipboard(fav.text || fav.fileName || '')) {
                                      onToast('已复制到剪贴板');
                                    }
                                  }}
                                >
                                  <Copy size={14} aria-hidden="true" />
                                </button>
                              )}
                              <button
                                type="button"
                                className="btn btn-ghost btn-icon lobby-touch-icon"
                                title="取消收藏"
                                aria-label="删除该条收藏"
                                onClick={() => onRemoveFavorite(fav.id)}
                              >
                                <Trash2 size={14} aria-hidden="true" />
                              </button>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>

                  {/* 已屏蔽成员 + 本地数据空间 */}
                  <div className="lobby-right-stack">
                    <div className="glass-bento lobby-card">
                      <div className="lobby-card-head">
                        <h2 className="lobby-card-title">
                          <Ban size={16} className="lobby-card-title-icon" aria-hidden="true" />
                          <span>全局屏蔽名单</span>
                          <span className="pill pill-neutral mono">{blockedPeers.length}</span>
                        </h2>
                      </div>
                      {blockedPeers.length === 0 ? (
                        <div className="lobby-empty-card centered">
                          <div className="lobby-empty-desc">暂无被永久屏蔽的对端成员。</div>
                        </div>
                      ) : (
                        <div className="lobby-room-list">
                          {blockedPeers.map((b) => (
                            <div key={b.clientId} className="lobby-room-item">
                              <div className="lobby-room-meta">
                                <div className="lobby-room-code">{b.name}</div>
                                <div className="lobby-room-sub mono">{formatRelativeTime(b.blockedAt)} 屏蔽</div>
                              </div>
                              <button
                                type="button"
                                className="btn btn-soft btn-sm lobby-touch-pill"
                                onClick={() => onUnblockPeer(b.clientId)}
                              >
                                解除屏蔽
                              </button>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>

                    <div className="glass-bento lobby-card">
                      <div className="lobby-card-head">
                        <h2 className="lobby-card-title">
                          <Database size={16} className="lobby-card-title-icon" aria-hidden="true" />
                          <span>本地缓存与数据管理</span>
                        </h2>
                        <span className="pill pill-neutral mono">{formatBytes(storageBytes)}</span>
                      </div>
                      <p className="lobby-empty-desc">
                        浏览器 localStorage 中保存了最近房间、取件码凭证、历史传输记录与收藏。清理操作不会重置你的本机身份卡片。
                      </p>
                      <button
                        type="button"
                        className="btn btn-danger-soft lobby-touch-pill"
                        onClick={() => setConfirmClearLocalOpen(true)}
                      >
                        <Trash2 size={14} aria-hidden="true" /> 一键清理本地历史与缓存
                      </button>
                    </div>
                  </div>
                </section>
              </div>
            )}
          </div>
        </main>

        {/* 移动端 <= 880px 底部 4-Tab 导航栏 */}
        <nav className="workspace-bottom-nav" aria-label="移动端底部导航">
          {navItems.map((item) => (
            <button
              key={item.id}
              type="button"
              className={`workspace-bottom-item ${activeTab === item.id ? 'active' : ''}`}
              onClick={() => setActiveTab(item.id)}
              aria-current={activeTab === item.id ? 'page' : undefined}
            >
              <span className="workspace-bottom-icon">
                {item.icon}
                {item.badge !== undefined && <span className="workspace-bottom-dot" />}
              </span>
              <span className="workspace-bottom-label">{item.label}</span>
            </button>
          ))}
        </nav>
      </div>

      {/* 不存在常驻房时的创建确认弹窗 */}
      {confirmCreateCode && (
        <div className="modal-overlay" onClick={() => setConfirmCreateCode(null)}>
          <div
            className="modal-sheet modal-sheet-sm"
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-label="创建常驻房间"
          >
            <div className="modal-head">
              <h3>创建新常驻存储房间</h3>
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

      {/* 新建公开房间弹窗 */}
      {createPublicOpen && (
        <div className="modal-overlay" onClick={() => !creatingPublic && setCreatePublicOpen(false)}>
          <div
            className="modal-sheet modal-sheet-sm"
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-label="新建公开存储房间"
          >
            <div className="modal-head">
              <h3>新建公开房间</h3>
              <button
                type="button"
                className="btn btn-ghost btn-icon modal-close-btn"
                aria-label="关闭弹窗"
                disabled={creatingPublic}
                onClick={() => setCreatePublicOpen(false)}
              >
                <X size={16} aria-hidden="true" />
              </button>
            </div>
            <form onSubmit={(e) => void handleCreatePublicRoom(e)} className="modal-stack">
              <div className="lobby-field-group">
                <span className="lobby-field-label">房间主题标签（可选，最多 24 字）</span>
                <input
                  className="field-input"
                  value={publicTopic}
                  maxLength={24}
                  onChange={(e) => setPublicTopic(e.target.value)}
                  placeholder="例如：设计素材共享、开源安装包中转"
                  autoFocus
                />
              </div>
              <div className="lobby-field-group">
                <span className="lobby-field-label">服务器保鲜期</span>
                <div className="lobby-chip-group">
                  {RETENTION_STEPS.map((st) => (
                    <button
                      key={st.days}
                      type="button"
                      className={`btn btn-sm ${publicRetentionDays === st.days ? 'btn-jelly' : 'btn-glass'}`}
                      onClick={() => setPublicRetentionDays(st.days)}
                    >
                      {st.label}
                    </button>
                  ))}
                </div>
              </div>
              <div className="modal-actions-end">
                <button
                  type="button"
                  className="btn btn-glass lobby-touch-pill"
                  disabled={creatingPublic}
                  onClick={() => setCreatePublicOpen(false)}
                >
                  取消
                </button>
                <button
                  type="submit"
                  className="btn btn-jelly lobby-touch-pill"
                  disabled={creatingPublic}
                >
                  {creatingPublic ? '创建中...' : '创建并公布至广场'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* 清空本地缓存二次确认弹窗 */}
      {confirmClearLocalOpen && (
        <div className="modal-overlay" onClick={() => setConfirmClearLocalOpen(false)}>
          <div
            className="modal-sheet modal-sheet-sm"
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-label="清理本地缓存"
          >
            <div className="modal-head">
              <h3>确认清理本地历史缓存？</h3>
              <button
                type="button"
                className="btn btn-ghost btn-icon modal-close-btn"
                aria-label="关闭弹窗"
                onClick={() => setConfirmClearLocalOpen(false)}
              >
                <X size={16} aria-hidden="true" />
              </button>
            </div>
            <p className="modal-body-text">
              将清除本机保存的最近房间、传输历史、本地收藏及取件码记录（当前用户身份卡片和常驻收藏房间将保留）。
            </p>
            <div className="modal-actions-end">
              <button
                type="button"
                className="btn btn-glass lobby-touch-pill"
                onClick={() => setConfirmClearLocalOpen(false)}
              >
                取消
              </button>
              <button
                type="button"
                className="btn btn-danger lobby-touch-pill"
                onClick={() => {
                  onClearLocalData();
                  setConfirmClearLocalOpen(false);
                }}
              >
                确认清理
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
          onPickupsChange={onPickupsChange}
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
