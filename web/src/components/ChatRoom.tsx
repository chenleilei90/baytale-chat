import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Copy,
  QrCode,
  Flame,
  Pin,
  LogOut,
  Trash2,
  Paperclip,
  Image as ImageIcon,
  Smile,
  Send,
  PanelLeft,
  Clock,
  UploadCloud,
  X,
  Lock,
  Palette,
  Mic,
  Square,
  Search,
  Download,
  RotateCw,
  SlidersHorizontal,
  Reply,
  FileDown,
  Headphones,
  MicOff,
  MonitorUp,
  PhoneOff,
  Minimize2,
  Flag,
  Ban,
  Settings2,
  ArrowRight,
  Users,
  MoreHorizontal,
  Bell,
  BellOff,
  CheckSquare,
  Clipboard,
  Forward,
  Globe,
  Activity,
  ShieldCheck,
  FolderUp,
} from 'lucide-react';
import QRCode from 'qrcode';
import type {
  AppearanceMode,
  BlockedPeer,
  CachedMessage,
  ChatItem,
  FileTransferInfo,
  HistoryMeta,
  MinimizedTransferState,
  PeerLinkStatus,
  PeerMediaState,
  PeerProfile,
  PendingNearbyInvite,
  PlaneFileMeta,
  PomodoroMode,
  PomodoroState,
  RecentRoom,
  ReplyQuote,
  RoomMode,
  RoomStats,
  RoomSummary,
  SavedFixedRoom,
  ServerConfig,
  ThemeMode,
  TransferRecord,
  UserProfile,
  WhiteboardBgStyle,
  WhiteboardEvent,
  WhiteboardNote,
  WhiteboardStroke,
} from '../types';
import {
  ApiError,
  buildCachedFileUrl,
  buildCachedMediaUrl,
  createRoom,
  fetchNearbyInviteStatus,
  fetchRoomInfo,
  SignalingClient,
  submitReport,
  updateRoomProfile,
  updateRoomSettingsApi,
  uploadCachedFile,
  uploadCachedMedia,
} from '../lib/signaling';
import { WebRTCMeshManager } from '../lib/webrtc';
import { compressChatImage } from '../lib/avatar';
import {
  copyTextToClipboard,
  formatBytes,
  formatExpiryTime,
  formatRelativeTime,
  getCachedResumeOffset,
  openDiskResumeStream,
  openDiskWriteStream,
  playNoticeTone,
  supportsScreenCapture,
  triggerBlobDownload,
} from '../lib/platform';
import { decodeSecretText, launchScreenEffect } from '../lib/effects';
import { showWebNotification, startTitleFlash, stopTitleFlash } from '../lib/notifications';
import { EMOJI_LIST } from '../lib/emoji';
import { classifyPlaneFileKind } from '../lib/planeKind';
import { ambientEngine, playPomodoroChime, type AmbientSoundType } from '../lib/ambient';
import {
  buildZipArchive,
  computeEntriesTotalBytes,
  extractDroppedItems,
} from '../lib/zip';
import {
  addBlockedPeer,
  getTabPeerId,
  loadBlockedPeers,
  removeBlockedPeer,
  type UserPrefs,
} from '../lib/storage';
import { Avatar } from './Avatar';
import { Switch } from './Controls';
import { SettingsPopover } from './SettingsPopover';
import { PlaneComposerModal } from './PlaneComposerModal';
import { MessageList } from './MessageList';
import { InviteModal } from './InviteModal';
import { WhiteboardModal, type RemoteCursorState } from './WhiteboardModal';
import { StudyPomodoroCard } from './StudyPomodoroCard';
import { RemoteAudioPlayer, ScreenShareStage, type ScreenFeedItem } from './MediaCallBar';
import { Lightbox } from './Lightbox';
import { P2PMonitorPanel } from './P2PMonitorPanel';

interface ChatRoomProps {
  roomCode: string;
  password: string;
  ownerToken?: string;
  pendingNearbyInvite?: PendingNearbyInvite | null;
  profile: UserProfile;
  prefs: UserPrefs;
  theme: ThemeMode;
  appearance: AppearanceMode;
  serverConfig: ServerConfig;
  savedFixedRooms: SavedFixedRoom[];
  recentRooms: RecentRoom[];
  isMinimized?: boolean;
  isMutedRoom?: boolean;
  onSelectTheme: (theme: ThemeMode) => void;
  onSelectAppearance: (mode: AppearanceMode) => void;
  onUpdatePrefs: (next: UserPrefs) => void;
  onEditProfile: () => void;
  onSwitchRoom: (newCode: string, password: string, mode?: RoomMode, ownerToken?: string) => void;
  onPasswordUpdated?: (code: string, nextPwd: string, mode: RoomMode, ownerToken?: string) => void;
  onClearPendingNearbyInvite?: () => void;
  onRemoveRecentRoom: (code: string) => void;
  onMinimizeRoom: () => void;
  onRestoreFromNotification?: () => void;
  onStatusSummary?: (summary: string) => void;
  onTransferStateChange?: (state: MinimizedTransferState) => void;
  onUnreadChange?: (count: number) => void;
  onToggleMuteRoom?: (code: string) => void;
  onRecordTransfer?: (record: TransferRecord) => void;
  onFavoriteMessage?: (item: ChatItem) => void;
  onBlockedPeersChanged?: () => void;
  onExitToLobby: (notice?: string) => void;
  onToast: (msg: string) => void;
}

type MessageFilterTab = 'all' | 'image' | 'file' | 'audio' | 'link';

const DEFAULT_DOC_TITLE = '湾聊 BayChat · 房间聊天';
const RECALL_WINDOW_MS = 120_000;
const ROOM_ATTACHMENT_PRESETS = [
  { mb: 50, label: '50MB' },
  { mb: 200, label: '200MB' },
  { mb: 500, label: '500MB' },
  { mb: 1024, label: '1GB' },
  { mb: 2048, label: '2GB' },
  { mb: 0, label: '不限制' },
];
const AUTO_ACCEPT_PRESETS = [10, 50, 100, 200, 500];
const FATAL_WS_ERRORS = new Set([
  'ROOM_NOT_FOUND',
  'ROOM_EXPIRED',
  'WRONG_PASSWORD',
  'ROOM_FULL',
  'INVALID_JOIN',
]);

function formatLimitMB(mb: number): string {
  if (mb <= 0) return '不限制';
  if (mb >= 1024) {
    const gb = mb / 1024;
    return `${Number.isInteger(gb) ? gb : gb.toFixed(1)}GB`;
  }
  return `${mb}MB`;
}
const RETENTION_STEPS = [
  { days: 0, label: '阅后即焚' },
  { days: 1, label: '1天' },
  { days: 2, label: '2天' },
  { days: 3, label: '3天' },
  { days: 5, label: '5天' },
  { days: 7, label: '7天' },
];
const POMODORO_DURATIONS: Record<PomodoroMode, number> = {
  focus25: 25 * 60,
  deep45: 45 * 60,
  break5: 5 * 60,
};
const DEFAULT_ROOM_STATS: RoomStats = {
  totalMessages: 0,
  totalFiles: 0,
  totalBytes: 0,
  totalPomodoros: 0,
  streakDays: 1,
};

interface PendingPasteImage {
  file: File | Blob;
  previewUrl: string;
  fileName: string;
  compress: boolean;
}

// 精简高效互动聊天室：支持 P2P 文件直传、语音连麦、实时投屏、协作白板、自习番茄钟与消息检索筛选
export const ChatRoom: React.FC<ChatRoomProps> = ({
  roomCode,
  password,
  ownerToken,
  pendingNearbyInvite,
  profile,
  prefs,
  theme,
  appearance,
  serverConfig,
  savedFixedRooms,
  recentRooms,
  onSelectTheme,
  onSelectAppearance,
  onUpdatePrefs,
  onEditProfile,
  isMinimized = false,
  isMutedRoom = false,
  onSwitchRoom,
  onPasswordUpdated,
  onClearPendingNearbyInvite,
  onRemoveRecentRoom,
  onMinimizeRoom,
  onRestoreFromNotification,
  onStatusSummary,
  onTransferStateChange,
  onUnreadChange,
  onToggleMuteRoom,
  onRecordTransfer,
  onFavoriteMessage,
  onBlockedPeersChanged,
  onExitToLobby,
  onToast,
}) => {
  // 按浏览器标签页生成独立 peerId（存在 sessionStorage），彻底避免同电脑双开标签页互踢重连循环
  const [selfPeerId] = useState<string>(() => getTabPeerId());

  const [roomInfo, setRoomInfo] = useState<RoomSummary | null>(null);
  const [isOwner, setIsOwner] = useState<boolean>(Boolean(ownerToken));
  const [blockedPeers, setBlockedPeers] = useState<BlockedPeer[]>(() => loadBlockedPeers());
  const [nearbyInviteStatus, setNearbyInviteStatus] = useState<
    'pending' | 'accepted' | 'declined' | 'expired' | null
  >(pendingNearbyInvite ? 'pending' : null);

  // 举报与房间资料修改弹窗状态
  const [reportTarget, setReportTarget] = useState<{
    peerId: string;
    clientId: string;
    name: string;
    messageId?: string;
  } | null>(null);
  const [reportReason, setReportReason] = useState('发送垃圾广告或恶意文件');
  const [submittingReport, setSubmittingReport] = useState(false);

  const [roomProfileOpen, setRoomProfileOpen] = useState(false);
  const [editTopicInput, setEditTopicInput] = useState('');
  const [editPasswordInput, setEditPasswordInput] = useState('');
  const [clearPasswordChecked, setClearPasswordChecked] = useState(false);
  const [savingRoomProfile, setSavingRoomProfile] = useState(false);
  const [historyMeta, setHistoryMeta] = useState<HistoryMeta>({
    retentionDays: 3,
    messageCount: 0,
  });
  const [wsConnected, setWsConnected] = useState(false);
  const [peers, setPeers] = useState<Record<string, PeerProfile>>({});
  const [linkStatuses, setLinkStatuses] = useState<Record<string, PeerLinkStatus>>({});
  const [typingPeers, setTypingPeers] = useState<Record<string, boolean>>({});
  const [messages, setMessages] = useState<ChatItem[]>([]);
  const [recalledTexts, setRecalledTexts] = useState<Record<string, string>>({});

  // 搜索与分类筛选状态
  const [showSearchBar, setShowSearchBar] = useState(false);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [filterTab, setFilterTab] = useState<MessageFilterTab>('all');

  // 语音录制状态
  const [recordingVoice, setRecordingVoice] = useState(false);
  const [recordSeconds, setRecordSeconds] = useState(0);

  const [inputText, setInputText] = useState('');
  const [replyingTo, setReplyingTo] = useState<ReplyQuote | null>(null);

  const [pendingPaste, setPendingPaste] = useState<PendingPasteImage | null>(null);
  const [showEmoji, setShowEmoji] = useState(false);
  const [nudgeShake, setNudgeShake] = useState<{ peerId: string; nonce: number } | null>(null);
  const lastNudgeAtRef = useRef<number>(0);
  const seenNudgeIdsRef = useRef<Set<string>>(new Set());
  const [cachePopoverOpen, setCachePopoverOpen] = useState(false);
  const [attachmentPopoverOpen, setAttachmentPopoverOpen] = useState(false);
  const [customLimitMBInput, setCustomLimitMBInput] = useState('');
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [monitorOpen, setMonitorOpen] = useState(true);
  const [showInvite, setShowInvite] = useState(false);
  const [showWhiteboard, setShowWhiteboard] = useState(false);
  const [moreMenuOpen, setMoreMenuOpen] = useState(false);
  const [settingsPopoverOpen, setSettingsPopoverOpen] = useState(false);
  const [planeComposerOpen, setPlaneComposerOpen] = useState(false);
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedMsgIds, setSelectedMsgIds] = useState<Set<string>>(() => new Set());
  const [forwardModalOpen, setForwardModalOpen] = useState(false);
  const [unreadMentionIds, setUnreadMentionIds] = useState<string[]>([]);
  const [updatingAllowRandom, setUpdatingAllowRandom] = useState(false);

  // 秘密基地成长统计与连续火花天数
  const [roomStats, setRoomStats] = useState<RoomStats>(DEFAULT_ROOM_STATS);

  // 一键语音连麦 & 实时屏幕共享状态
  const [micActive, setMicActive] = useState(false);
  const [micMuted, setMicMuted] = useState(false);
  const [localScreenStream, setLocalScreenStream] = useState<MediaStream | null>(null);
  const [remoteAudioStreams, setRemoteAudioStreams] = useState<Record<string, MediaStream>>({});
  const [remoteScreenStreams, setRemoteScreenStreams] = useState<Record<string, MediaStream>>({});
  const [peerMediaStates, setPeerMediaStates] = useState<Record<string, PeerMediaState>>({});
  const [speakingPeers, setSpeakingPeers] = useState<Record<string, boolean>>({});

  // 多人实时同屏协作白板状态（关闭弹窗后依然保持，全房同步）
  const [wbBgStyle, setWbBgStyle] = useState<WhiteboardBgStyle>('night');
  const [wbStrokes, setWbStrokes] = useState<WhiteboardStroke[]>([]);
  const [wbNotes, setWbNotes] = useState<WhiteboardNote[]>([]);
  const [wbCursors, setWbCursors] = useState<Record<string, RemoteCursorState>>({});
  const [wbInviteBanner, setWbInviteBanner] = useState<string | null>(null);

  // 陪伴自习室：同步番茄钟 + 合成白噪音
  const [pomodoro, setPomodoro] = useState<PomodoroState>({
    running: false,
    mode: 'focus25',
    remainingSec: POMODORO_DURATIONS.focus25,
    endAt: 0,
    updatedAt: 0,
  });
  const [pomodoroDisplaySec, setPomodoroDisplaySec] = useState<number>(POMODORO_DURATIONS.focus25);
  const [ambientSound, setAmbientSound] = useState<AmbientSoundType>('off');
  const [ambientVolume, setAmbientVolume] = useState<number>(0.32);
  const [lightboxData, setLightboxData] = useState<{
    src: string;
    fileName?: string;
    caption?: string;
  } | null>(null);
  const [confirmClearOpen, setConfirmClearOpen] = useState(false);
  const [confirmDestroyOpen, setConfirmDestroyOpen] = useState(false);
  const [rotatingCode, setRotatingCode] = useState(false);
  const [draggingOver, setDraggingOver] = useState(false);
  const [radarQrUrl, setRadarQrUrl] = useState('');
  const [, setTick] = useState(0);

  const signalingRef = useRef<SignalingClient | null>(null);
  const meshRef = useRef<WebRTCMeshManager | null>(null);
  const peersRef = useRef<Record<string, PeerProfile>>({});
  const profileRef = useRef(profile);
  const prefsRef = useRef(prefs);
  const serverConfigRef = useRef(serverConfig);
  const historyMetaRef = useRef<HistoryMeta>(historyMeta);
  const createdUrlsRef = useRef<Set<string>>(new Set());
  const mountedRef = useRef(true);
  const unreadCountRef = useRef(0);
  const voiceCancelledRef = useRef(false);
  const messageEndRef = useRef<HTMLDivElement>(null);
  const composerInputRef = useRef<HTMLTextAreaElement>(null);
  const messagesRef = useRef<ChatItem[]>(messages);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const typingTimerRef = useRef<number | null>(null);
  const remoteTypingTimersRef = useRef<Record<string, number>>({});
  const blockedPeersRef = useRef<BlockedPeer[]>(blockedPeers);
  const ownerTokenRef = useRef<string | undefined>(ownerToken);
  const passwordRef = useRef(password);
  passwordRef.current = password;
  const isMinimizedRef = useRef(isMinimized);
  isMinimizedRef.current = isMinimized;
  const isMutedRoomRef = useRef(isMutedRoom);
  isMutedRoomRef.current = isMutedRoom;
  const onSwitchRoomRef = useRef(onSwitchRoom);
  const onPasswordUpdatedRef = useRef(onPasswordUpdated);
  onPasswordUpdatedRef.current = onPasswordUpdated;
  const onUnreadChangeRef = useRef(onUnreadChange);
  onUnreadChangeRef.current = onUnreadChange;
  const onRestoreFromNotificationRef = useRef(onRestoreFromNotification);
  onRestoreFromNotificationRef.current = onRestoreFromNotification;
  const onRecordTransferRef = useRef(onRecordTransfer);
  onRecordTransferRef.current = onRecordTransfer;
  const recordedCompletedTransferIdsRef = useRef<Set<string>>(new Set());
  const dragDepthRef = useRef(0);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const recordChunksRef = useRef<Blob[]>([]);
  const recordTimerRef = useRef<number | null>(null);
  const recordStartRef = useRef<number>(0);
  const localMicStreamRef = useRef<MediaStream | null>(null);
  const localScreenStreamRef = useRef<MediaStream | null>(null);
  const pomodoroRef = useRef<PomodoroState>(pomodoro);
  const pomodoroDoneStampRef = useRef<number>(0);
  const recordedTransferIdsRef = useRef<Set<string>>(new Set());
  const wbStateRef = useRef<{
    bgStyle: WhiteboardBgStyle;
    strokes: WhiteboardStroke[];
    notes: WhiteboardNote[];
  }>({ bgStyle: 'night', strokes: [], notes: [] });
  const showWhiteboardRef = useRef<boolean>(showWhiteboard);

  profileRef.current = profile;
  prefsRef.current = prefs;
  peersRef.current = peers;
  messagesRef.current = messages;
  blockedPeersRef.current = blockedPeers;
  ownerTokenRef.current = ownerToken;
  onSwitchRoomRef.current = onSwitchRoom;
  serverConfigRef.current = serverConfig;
  historyMetaRef.current = historyMeta;
  pomodoroRef.current = pomodoro;
  wbStateRef.current = { bgStyle: wbBgStyle, strokes: wbStrokes, notes: wbNotes };
  showWhiteboardRef.current = showWhiteboard;

  // 彻底停止本地麦克风与投屏采集轨道，避免退出后浏览器红点残留
  const stopAllLocalMedia = useCallback(() => {
    if (localMicStreamRef.current) {
      localMicStreamRef.current.getTracks().forEach((t) => t.stop());
      localMicStreamRef.current = null;
    }
    if (localScreenStreamRef.current) {
      localScreenStreamRef.current.getTracks().forEach((t) => t.stop());
      localScreenStreamRef.current = null;
    }
    setMicActive(false);
    setMicMuted(false);
    setLocalScreenStream(null);
    meshRef.current?.setLocalMicStream(null);
    meshRef.current?.setLocalScreenStream(null);
  }, []);

  // 应用本地或远端同步过来的协作白板事件
  const applyWhiteboardEvent = useCallback(
    (peerId: string | null, ev: WhiteboardEvent) => {
      switch (ev.type) {
        case 'opened':
          if (peerId && !showWhiteboardRef.current) {
            setWbInviteBanner(ev.actorName || peersRef.current[peerId]?.name || '伙伴');
          }
          break;
        case 'cursor':
          if (peerId) {
            setWbCursors((prev) => ({
              ...prev,
              [peerId]: {
                peerId,
                name: ev.name,
                color: ev.color,
                nx: ev.nx,
                ny: ev.ny,
                updatedAt: Date.now(),
              },
            }));
          }
          break;
        case 'stroke-start':
          setWbStrokes((prev) => [...prev.slice(-260), ev.stroke]);
          if (peerId && !showWhiteboardRef.current) {
            setWbInviteBanner(peersRef.current[peerId]?.name || '伙伴');
          }
          break;
        case 'stroke-append':
          setWbStrokes((prev) =>
            prev.map((s) =>
              s.id === ev.strokeId ? { ...s, points: [...s.points, ...ev.points] } : s
            )
          );
          break;
        case 'stroke-undo':
          setWbStrokes((prev) => prev.filter((s) => s.id !== ev.strokeId));
          break;
        case 'note-upsert':
          setWbNotes((prev) => {
            const exists = prev.some((n) => n.id === ev.note.id);
            if (exists) {
              return prev.map((n) => (n.id === ev.note.id ? ev.note : n));
            }
            return [...prev.slice(-24), ev.note];
          });
          break;
        case 'note-delete':
          setWbNotes((prev) => prev.filter((n) => n.id !== ev.noteId));
          break;
        case 'bg-change':
          setWbBgStyle(ev.bgStyle);
          break;
        case 'clear':
          setWbStrokes([]);
          setWbNotes([]);
          break;
        case 'sync-req':
          if (peerId) {
            const snap = wbStateRef.current;
            if (snap.strokes.length > 0 || snap.notes.length > 0 || snap.bgStyle !== 'night') {
              meshRef.current?.sendWhiteboardToPeer(peerId, {
                type: 'sync-res',
                bgStyle: snap.bgStyle,
                strokes: snap.strokes,
                notes: snap.notes,
              });
            }
          }
          break;
        case 'sync-res':
          setWbBgStyle(ev.bgStyle);
          setWbStrokes((prev) => {
            const map = new Map<string, WhiteboardStroke>();
            prev.forEach((s) => map.set(s.id, s));
            ev.strokes.forEach((s) => map.set(s.id, s));
            return Array.from(map.values()).slice(-260);
          });
          setWbNotes((prev) => {
            const map = new Map<string, WhiteboardNote>();
            prev.forEach((n) => map.set(n.id, n));
            ev.notes.forEach((n) => {
              const cur = map.get(n.id);
              if (!cur || n.updatedAt >= cur.updatedAt) map.set(n.id, n);
            });
            return Array.from(map.values()).slice(-24);
          });
          break;
      }
    },
    []
  );

  const handleSpeakingChange = useCallback((peerId: string, speaking: boolean) => {
    setSpeakingPeers((prev) => {
      if (Boolean(prev[peerId]) === speaking) return prev;
      return { ...prev, [peerId]: speaking };
    });
  }, []);

  // 计算当前房间生效的单附件上限（MB），0 表示不限制；综合房间配置与服务端硬天花板
  const effectiveMaxFileMB = useMemo(() => {
    const roomMB = roomInfo?.maxFileSizeMB ?? serverConfig.defaultRoomMaxFileMB ?? 500;
    const capMB = serverConfig.maxAttachmentMB || 0;
    if (capMB > 0) {
      if (roomMB <= 0 || roomMB > capMB) return capMB;
    }
    return roomMB;
  }, [roomInfo?.maxFileSizeMB, serverConfig.defaultRoomMaxFileMB, serverConfig.maxAttachmentMB]);

  const effectiveMaxFileMBRef = useRef(effectiveMaxFileMB);
  effectiveMaxFileMBRef.current = effectiveMaxFileMB;

  const checkAttachmentAllowed = useCallback(
    (sizeBytes: number, fileName: string): boolean => {
      const limitMB = effectiveMaxFileMBRef.current;
      if (limitMB <= 0) return true;
      const limitBytes = limitMB * 1024 * 1024;
      if (sizeBytes > limitBytes) {
        onToast(
          `「${fileName}」（${formatBytes(sizeBytes)}）超出当前附件上限 ${formatLimitMB(limitMB)}，可点顶部「附件上限」随时调大`
        );
        return false;
      }
      return true;
    },
    [onToast]
  );

  // 键盘快捷键：Ctrl+F 打开搜索栏，Esc 关闭弹出层/确认弹窗
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f') {
        e.preventDefault();
        setShowSearchBar(true);
        window.setTimeout(() => searchInputRef.current?.focus(), 30);
        return;
      }
      if (e.key === 'Escape') {
        setShowEmoji(false);
        setCachePopoverOpen(false);
        setAttachmentPopoverOpen(false);
        setConfirmClearOpen(false);
        setConfirmDestroyOpen(false);
        setReplyingTo(null);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  // 异步获取到最新 TURN/STUN 凭证后平滑更新，绝不中断当前房间连接
  useEffect(() => {
    meshRef.current?.updateIceServers(serverConfig.iceServers);
  }, [serverConfig.iceServers]);

  // 统一登记创建的 Blob ObjectURL，在清屏或退出房间时彻底释放内存
  const trackObjectUrl = useCallback((url?: string) => {
    if (url && url.startsWith('blob:')) {
      createdUrlsRef.current.add(url);
    }
    return url;
  }, []);

  const revokeAllTrackedUrls = useCallback(() => {
    createdUrlsRef.current.forEach((u) => {
      try {
        URL.revokeObjectURL(u);
      } catch {
        // 忽略释放异常
      }
    });
    createdUrlsRef.current.clear();
  }, []);

  const checkMentionMe = useCallback((text?: string): boolean => {
    if (!text) return false;
    const myName = profileRef.current.name.trim();
    if (!myName) return false;
    return text.toLowerCase().includes(`@${myName.toLowerCase()}`);
  }, []);

  // 后台标签页与挂起态统一未读提醒、标签页标题闪烁与浏览器桌面通知
  const notifyIncomingArrival = useCallback(
    (params: {
      itemId: string;
      senderName: string;
      previewText: string;
      mentionedMe?: boolean;
      isOffer?: boolean;
    }) => {
      const hiddenOrMinimized =
        (typeof document !== 'undefined' && document.hidden) || isMinimizedRef.current;
      if (params.mentionedMe) {
        setUnreadMentionIds((prev) =>
          prev.includes(params.itemId) ? prev : [...prev, params.itemId]
        );
      }
      if (hiddenOrMinimized) {
        unreadCountRef.current += 1;
        onUnreadChangeRef.current?.(unreadCountRef.current);
      }
      const allowNotify = !isMutedRoomRef.current || Boolean(params.mentionedMe) || Boolean(params.isOffer);
      if (!hiddenOrMinimized || !allowNotify) return;

      if (typeof document !== 'undefined' && document.hidden) {
        startTitleFlash(
          DEFAULT_DOC_TITLE,
          `(${unreadCountRef.current}) ${params.mentionedMe ? '[@我] ' : ''}${params.senderName}: ${params.previewText.slice(0, 18)}`
        );
      }
      if (prefsRef.current.desktopNotifications) {
        showWebNotification({
          title: params.mentionedMe
            ? `${params.senderName} 在 #${roomCode} @了你`
            : `#${roomCode} · ${params.senderName}`,
          body: params.previewText,
          tag: `baychat-${roomCode}`,
          onClick: () => {
            onRestoreFromNotificationRef.current?.();
          },
        });
      }
    },
    [roomCode]
  );

  useEffect(() => {
    const clearUnreadAndFlash = () => {
      if (typeof document !== 'undefined' && !document.hidden && !isMinimized) {
        unreadCountRef.current = 0;
        onUnreadChangeRef.current?.(0);
        stopTitleFlash(DEFAULT_DOC_TITLE);
      }
    };
    clearUnreadAndFlash();
    document.addEventListener('visibilitychange', clearUnreadAndFlash);
    return () => {
      document.removeEventListener('visibilitychange', clearUnreadAndFlash);
      stopTitleFlash(DEFAULT_DOC_TITLE);
    };
  }, [isMinimized]);

  useEffect(() => {
    const id = window.setInterval(() => setTick((t) => t + 1), 30000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    const inviteUrl = `${window.location.origin}/?room=${encodeURIComponent(roomCode)}`;
    let alive = true;
    QRCode.toDataURL(inviteUrl, {
      width: 180,
      margin: 1,
      color: { dark: '#1f1629', light: '#ffffff' },
    })
      .then((url) => {
        if (alive) setRadarQrUrl(url);
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [roomCode]);

  useEffect(() => {
    if (!searchKeyword && filterTab === 'all') {
      messageEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages.length, searchKeyword, filterTab]);

  const handleNudgePeer = useCallback(
    (targetPeerId: string, targetName: string) => {
      const now = Date.now();
      if (now - lastNudgeAtRef.current < 3000) {
        onToast('操作太频繁');
        return;
      }
      lastNudgeAtRef.current = now;
      const isSelfTarget = targetPeerId === selfPeerId || targetPeerId === 'self';
      const resolvedId = isSelfTarget ? selfPeerId : targetPeerId;
      const resolvedName = isSelfTarget ? profileRef.current.name : targetName;
      const id = `nudge_${now}_${Math.random().toString(36).slice(2, 7)}`;
      seenNudgeIdsRef.current.add(id);
      const text = isSelfTarget ? '我拍了拍自己' : `我拍了拍"${resolvedName}"`;
      setMessages((prev) => [
        ...prev,
        {
          id,
          kind: 'system',
          senderId: 'system',
          senderName: '系统',
          isSelf: false,
          timestamp: now,
          text,
        },
      ]);
      setNudgeShake((prev) => ({ peerId: resolvedId, nonce: (prev?.nonce ?? 0) + 1 }));
      meshRef.current?.broadcastNudge(id, resolvedId, resolvedName, profileRef.current.name, now);
    },
    [onToast, selfPeerId]
  );

  const handleInsertEmoji = useCallback((em: string) => {
    const el = composerInputRef.current;
    const start = el?.selectionStart ?? null;
    const end = el?.selectionEnd ?? null;
    setInputText((prev) => {
      const s = start !== null ? Math.min(start, prev.length) : prev.length;
      const e = end !== null ? Math.min(Math.max(s, end), prev.length) : s;
      const next = prev.slice(0, s) + em + prev.slice(e);
      const caret = s + em.length;
      window.setTimeout(() => {
        const cur = composerInputRef.current;
        if (cur) {
          cur.focus();
          cur.setSelectionRange(caret, caret);
        }
      }, 0);
      return next;
    });
  }, []);

  const appendSystemMessage = useCallback((text: string) => {
    setMessages((prev) => [
      ...prev,
      {
        id: `sys_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
        kind: 'system',
        senderId: 'system',
        senderName: '系统',
        isSelf: false,
        timestamp: Date.now(),
        text,
      },
    ]);
  }, []);

  // 同步番茄钟每秒滴答与完成庆贺（仅由发起者/单人房上报基地 +1🍅，杜绝多端重复计数）
  useEffect(() => {
    const syncRemaining = () => {
      if (!pomodoro.running) {
        setPomodoroDisplaySec(pomodoro.remainingSec);
        return;
      }
      const left = Math.max(0, Math.ceil((pomodoro.endAt - Date.now()) / 1000));
      setPomodoroDisplaySec(left);

      if (left === 0 && pomodoroDoneStampRef.current !== pomodoro.endAt) {
        pomodoroDoneStampRef.current = pomodoro.endAt;
        const finishedMode = pomodoro.mode;
        const starter = pomodoro.startedBy;
        setPomodoro((prev) => ({
          ...prev,
          running: false,
          remainingSec: POMODORO_DURATIONS[prev.mode],
          endAt: 0,
          updatedAt: Date.now(),
        }));
        launchScreenEffect('confetti');
        playPomodoroChime();
        const isFocus = finishedMode !== 'break5';
        appendSystemMessage(
          isFocus
            ? '全房已完成本轮番茄专注，基地成长计数 +1'
            : '小憩时间结束，准备进入下一轮专注吧'
        );
        if (isFocus && (!starter || starter === profileRef.current.name || Object.keys(peersRef.current).length === 0)) {
          signalingRef.current?.recordActivity('pomodoro', 0);
        }
      }
    };

    syncRemaining();
    if (!pomodoro.running) return;
    const id = window.setInterval(syncRemaining, 500);
    return () => clearInterval(id);
  }, [pomodoro, appendSystemMessage]);

  const toggleMessageReaction = useCallback((msgId: string, emoji: string, peerId: string) => {
    setMessages((prev) =>
      prev.map((m) => {
        if (m.id !== msgId) return m;
        const current = { ...(m.reactions || {}) };
        const list = current[emoji] || [];
        if (list.includes(peerId)) {
          current[emoji] = list.filter((id) => id !== peerId);
        } else {
          current[emoji] = [...list, peerId];
        }
        return { ...m, reactions: current };
      })
    );
  }, []);

  const mergeCachedMessage = useCallback(
    (cm: CachedMessage, markFromCache: boolean) => {
      setMessages((prev) => {
        if (prev.some((m) => m.id === cm.id)) return prev;
        const isSelf = cm.senderId === selfPeerId;
        if (cm.kind === 'image' && cm.mediaId) {
          const url = buildCachedMediaUrl(roomCode, cm.mediaId, passwordRef.current);
          return [
            ...prev,
            {
              id: cm.id,
              kind: 'image',
              senderId: cm.senderId,
              senderName: cm.senderName || '伙伴',
              senderAvatar: cm.senderAvatar,
              isSelf,
              timestamp: cm.timestamp,
              caption: cm.caption,
              imageUrl: url,
              mediaId: cm.mediaId,
              isScreenshot: cm.isScreenshot,
              fromCache: markFromCache,
            },
          ];
        }
        if (cm.kind === 'audio' && cm.mediaId) {
          const url = buildCachedMediaUrl(roomCode, cm.mediaId, passwordRef.current);
          return [
            ...prev,
            {
              id: cm.id,
              kind: 'audio',
              senderId: cm.senderId,
              senderName: cm.senderName || '伙伴',
              senderAvatar: cm.senderAvatar,
              isSelf,
              timestamp: cm.timestamp,
              caption: cm.caption,
              audioUrl: url,
              mediaId: cm.mediaId,
              fromCache: markFromCache,
            },
          ];
        }
        if (cm.kind === 'file_notice') {
          const cachedUrl = cm.mediaId ? buildCachedFileUrl(roomCode, cm.mediaId, passwordRef.current) : undefined;
          const basePrefix = cm.id.replace(/^fnotice_/, '');
          // 若当前会话已存在同批次实时 P2P 文件卡，则原地补充云端缓存直链，绝不重复渲染两张卡片
          const existingIdx = prev.findIndex(
            (m) =>
              m.kind === 'file' &&
              m.transfer &&
              (m.transfer.transferId === basePrefix || m.transfer.transferId.startsWith(`${basePrefix}_`))
          );
          if (existingIdx >= 0) {
            if (!cachedUrl) return prev;
            return prev.map((m, idx) =>
              idx === existingIdx && m.transfer
                ? {
                    ...m,
                    transfer: {
                      ...m.transfer,
                      cachedDownloadUrl: cachedUrl,
                    },
                  }
                : m
            );
          }

          return [
            ...prev,
            {
              id: cm.id,
              kind: 'file',
              senderId: cm.senderId,
              senderName: cm.senderName || '伙伴',
              senderAvatar: cm.senderAvatar,
              isSelf,
              timestamp: cm.timestamp,
              mediaId: cm.mediaId,
              fromCache: markFromCache,
              transfer: {
                transferId: cm.id,
                streamId: 0,
                fileName: cm.fileName || '未命名附件',
                fileSize: cm.fileSize || 0,
                mimeType: cm.mediaMime || 'application/octet-stream',
                isImage: false,
                direction: isSelf ? 'send' : 'receive',
                status: 'completed',
                transferredBytes: cm.fileSize || 0,
                speedBps: 0,
                etaSeconds: 0,
                cachedDownloadUrl: cachedUrl,
                isHistoryRecord: true,
              },
            },
          ];
        }

        const decoded = decodeSecretText(cm.text);
        const replyTo =
          cm.replyToId && cm.replyToPreview
            ? {
                id: cm.replyToId,
                senderName: cm.replyToSender || '伙伴',
                preview: cm.replyToPreview,
              }
            : undefined;
        return [
          ...prev,
          {
            id: cm.id,
            kind: 'text',
            senderId: cm.senderId,
            senderName: cm.senderName || '伙伴',
            senderAvatar: cm.senderAvatar,
            isSelf,
            timestamp: cm.timestamp,
            text: decoded.text,
            isSecret: decoded.isSecret,
            replyTo,
            fromCache: markFromCache,
          },
        ];
      });
    },
    [roomCode, selfPeerId]
  );

  // 本地移除被撤回的消息并插入系统提示（校验发送者归属与 180s 容忍时钟偏差窗口，同步刷新引用回复预览）
  const applyMessageRecalled = useCallback(
    (msgId: string, actorPeerId?: string, actorName?: string) => {
      const targetSnapshot = messagesRef.current.find((m) => m.id === msgId);
      if (targetSnapshot) {
        if (actorPeerId && targetSnapshot.senderId !== actorPeerId) return;
        if (Date.now() - targetSnapshot.timestamp > RECALL_WINDOW_MS + 60_000) return;
      }

      const recallEntryId = `recalled_${msgId}`;
      let removedAny = false;
      let recalledTextForReedit: string | undefined;

      setMessages((prev) => {
        const target = prev.find((m) => m.id === msgId);
        if (!target) return prev;
        if (actorPeerId && target.senderId !== actorPeerId) return prev;
        if (Date.now() - target.timestamp > RECALL_WINDOW_MS + 60_000) return prev;

        removedAny = true;
        const isSelfRecall = target.isSelf || actorPeerId === selfPeerId;
        if (isSelfRecall && target.kind === 'text' && target.text) {
          recalledTextForReedit = target.text;
        }
        const sysText = isSelfRecall
          ? '你撤回了一条消息'
          : `"${actorName || target.senderName || '成员'}" 撤回了一条消息`;

        const updated = prev
          .filter((m) => m.id !== msgId)
          .map((m) =>
            m.replyTo?.id === msgId
              ? { ...m, replyTo: { ...m.replyTo, preview: '该引用内容已撤回' } }
              : m
          );

        return [
          ...updated,
          {
            id: recallEntryId,
            kind: 'system',
            senderId: 'system',
            senderName: '系统',
            isSelf: false,
            timestamp: Date.now(),
            text: sysText,
          },
        ];
      });

      if (removedAny) {
        if (recalledTextForReedit) {
          const textToSave = recalledTextForReedit;
          setRecalledTexts((prev) => ({ ...prev, [recallEntryId]: textToSave }));
        }
        setReplyingTo((cur) => (cur?.id === msgId ? null : cur));
      }
    },
    [selfPeerId]
  );

  // 初始化 WebSocket 信令与 WebRTC Mesh 管理器（严禁把 serverConfig 放入依赖，避免配置加载后断线清屏）
  useEffect(() => {
    mountedRef.current = true;
    setMessages([]);
    setRecalledTexts({});
    setPeers({});
    setLinkStatuses({});
    setTypingPeers({});
    setReplyingTo(null);
    setRemoteAudioStreams({});
    setRemoteScreenStreams({});
    setPeerMediaStates({});
    setSpeakingPeers({});
    setWbStrokes([]);
    setWbNotes([]);
    setWbCursors({});
    setWbBgStyle('night');
    setWbInviteBanner(null);
    setRoomStats(DEFAULT_ROOM_STATS);
    setPomodoro({
      running: false,
      mode: 'focus25',
      remainingSec: POMODORO_DURATIONS.focus25,
      endAt: 0,
      updatedAt: 0,
    });
    voiceCancelledRef.current = false;
    revokeAllTrackedUrls();

    const isBlockedProfile = (p?: PeerProfile | null): boolean => {
      if (!p) return false;
      const cid = p.clientId || p.peerId;
      return blockedPeersRef.current.some((b) => b.clientId === cid || b.clientId === p.peerId);
    };
    const isBlockedPeerId = (peerId: string): boolean => {
      return isBlockedProfile(peersRef.current[peerId]) ||
        blockedPeersRef.current.some((b) => b.clientId === peerId);
    };

    const selfPeerProfile: PeerProfile = {
      peerId: selfPeerId,
      clientId: profileRef.current.clientId,
      name: profileRef.current.name,
      avatar: profileRef.current.avatar,
      joinedAt: Date.now(),
    };

    const mesh = new WebRTCMeshManager(selfPeerId, serverConfigRef.current.iceServers, {
      sendSignal: (env) => signalingRef.current?.send(env),
      onLinkStatusChange: (status) => {
        setLinkStatuses((prev) => ({ ...prev, [status.peerId]: status }));
      },
      onTextMessage: (peerId, msg) => {
        if (isBlockedPeerId(peerId)) return;
        const sender = peersRef.current[peerId];
        const decoded = decodeSecretText(msg.text);
        setMessages((prev) => {
          if (prev.some((m) => m.id === msg.id)) return prev;
          return [
            ...prev,
            {
              id: msg.id,
              kind: 'text',
              senderId: peerId,
              senderName: sender?.name || '对方',
              senderAvatar: sender?.avatar,
              isSelf: false,
              timestamp: msg.timestamp,
              text: decoded.text,
              isSecret: decoded.isSecret,
              replyTo: msg.replyTo,
            },
          ];
        });
        const mentioned = checkMentionMe(decoded.text);
        notifyIncomingArrival({
          itemId: msg.id,
          senderName: sender?.name || '伙伴',
          previewText: decoded.isSecret ? '[防窥密信]' : decoded.text,
          mentionedMe: mentioned,
        });
        if (prefsRef.current.soundEnabled && (!isMutedRoomRef.current || mentioned)) playNoticeTone();
      },
      onRecallMessage: (peerId, msgId) => {
        const sender = peersRef.current[peerId];
        applyMessageRecalled(msgId, peerId, sender?.name);
      },
      onTyping: (peerId, isTyping) => {
        if (isBlockedPeerId(peerId)) return;
        if (remoteTypingTimersRef.current[peerId]) {
          clearTimeout(remoteTypingTimersRef.current[peerId]);
          delete remoteTypingTimersRef.current[peerId];
        }
        setTypingPeers((prev) => ({ ...prev, [peerId]: isTyping }));
        // 接收端 6 秒超时自动清除「正在输入」状态
        if (isTyping) {
          remoteTypingTimersRef.current[peerId] = window.setTimeout(() => {
            delete remoteTypingTimersRef.current[peerId];
            setTypingPeers((prev) => ({ ...prev, [peerId]: false }));
          }, 6000);
        }
      },
      onReaction: (peerId, msgId, emoji) => {
        if (isBlockedPeerId(peerId)) return;
        toggleMessageReaction(msgId, emoji, peerId);
      },
      onNudge: (peerId, id, targetPeerId, targetName, fromName, timestamp) => {
        if (isBlockedPeerId(peerId)) return;
        if (seenNudgeIdsRef.current.has(id)) return;
        seenNudgeIdsRef.current.add(id);
        if (seenNudgeIdsRef.current.size > 500) {
          seenNudgeIdsRef.current.clear();
          seenNudgeIdsRef.current.add(id);
        }
        const isSelfTarget = targetPeerId === selfPeerId;
        const isSenderSelfTarget = targetPeerId === peerId;
        const senderLabel = peersRef.current[peerId]?.name || fromName || '伙伴';
        const targetLabel = isSelfTarget
          ? profileRef.current.name
          : peersRef.current[targetPeerId]?.name || targetName || '伙伴';
        const nudgeText = isSelfTarget
          ? `"${senderLabel}" 拍了拍我`
          : isSenderSelfTarget
          ? `"${senderLabel}" 拍了拍自己`
          : `"${senderLabel}" 拍了拍 "${targetLabel}"`;
        setMessages((prev) => {
          if (prev.some((m) => m.id === id)) return prev;
          return [
            ...prev,
            {
              id,
              kind: 'system',
              senderId: 'system',
              senderName: '系统',
              isSelf: false,
              timestamp: timestamp || Date.now(),
              text: nudgeText,
            },
          ];
        });
        const shakeTarget = isSelfTarget ? selfPeerId : targetPeerId;
        setNudgeShake((prev) => ({ peerId: shakeTarget, nonce: (prev?.nonce ?? 0) + 1 }));
        if (isSelfTarget) {
          notifyIncomingArrival({
            itemId: id,
            senderName: senderLabel,
            previewText: nudgeText,
          });
          if (prefsRef.current.soundEnabled && !isMutedRoomRef.current) {
            playNoticeTone();
          }
        }
      },
      onClipboardSync: (peerId, text, updatedAt) => {
        if (isBlockedPeerId(peerId) || !text) return;
        const sender = peersRef.current[peerId];
        const id = `clip_${updatedAt || Date.now()}_${peerId}`;
        setMessages((prev) => {
          if (prev.some((m) => m.id === id)) return prev;
          return [
            ...prev,
            {
              id,
              kind: 'text',
              senderId: peerId,
              senderName: sender?.name || '对方',
              senderAvatar: sender?.avatar,
              isSelf: false,
              timestamp: updatedAt || Date.now(),
              text,
              isClipSync: true,
            },
          ];
        });
        notifyIncomingArrival({
          itemId: id,
          senderName: sender?.name || '伙伴',
          previewText: `[剪贴板] ${text}`,
        });
        if (prefsRef.current.soundEnabled && !isMutedRoomRef.current) playNoticeTone();
        onToast(`收到 ${sender?.name || '伙伴'} 共享的剪贴板内容`);
      },
      onRoomMoved: (_peerId, nextCode, nextPassword) => {
        onToast('房间已更换口令');
        sig.leaveRoom();
        mesh.destroyAll();
        revokeAllTrackedUrls();
        onSwitchRoomRef.current(nextCode, nextPassword ?? '', 'ephemeral');
      },
      onResumeUnavailable: (_peerId, _resumeKey, fileName) => {
        onToast(`对方已无法重发「${fileName}」`);
      },
      onOutgoingResend: (peerId, info) => {
        const peerName = peersRef.current[peerId]?.name;
        setMessages((prev) => [
          ...prev,
          {
            id: `tr_${info.transferId}`,
            kind: 'file',
            senderId: selfPeerId,
            senderName: profileRef.current.name,
            senderAvatar: profileRef.current.avatar,
            isSelf: true,
            timestamp: Date.now(),
            transfer: { ...info, targetPeerName: peerName },
          },
        ]);
        onToast(`正在响应续传重发「${info.fileName}」`);
      },
      onRemoteAudioStream: (peerId, stream) => {
        setRemoteAudioStreams((prev) => {
          const next = { ...prev };
          if (stream) next[peerId] = stream;
          else delete next[peerId];
          return next;
        });
      },
      onRemoteScreenStream: (peerId, stream) => {
        setRemoteScreenStreams((prev) => {
          const next = { ...prev };
          if (stream) next[peerId] = stream;
          else delete next[peerId];
          return next;
        });
        if (stream) {
          const sender = peersRef.current[peerId];
          onToast(`${sender?.name || '伙伴'} 开启了实时屏幕共享`);
        }
      },
      onPeerMediaState: (peerId, state) => {
        setPeerMediaStates((prev) => ({ ...prev, [peerId]: state }));
      },
      onPomodoroSync: (_peerId, state) => {
        setPomodoro((cur) => (state.updatedAt >= cur.updatedAt ? state : cur));
      },
      onWhiteboardEvent: (peerId, event) => {
        applyWhiteboardEvent(peerId, event);
      },
      onPeerDataReady: (peerId) => {
        if (pomodoroRef.current.running || pomodoroRef.current.updatedAt > 0) {
          mesh.sendPomodoroToPeer(peerId, pomodoroRef.current);
        }
        const snap = wbStateRef.current;
        if (snap.strokes.length > 0 || snap.notes.length > 0) {
          mesh.sendWhiteboardToPeer(peerId, {
            type: 'sync-res',
            bgStyle: snap.bgStyle,
            strokes: snap.strokes,
            notes: snap.notes,
          });
        }
      },
      onFileOffer: (peerId, info, msgId, caption, timestamp) => {
        if (isBlockedPeerId(peerId)) {
          mesh.rejectIncomingTransfer(peerId, info.transferId);
          return;
        }
        const sender = peersRef.current[peerId];
        const kind = info.isImage ? 'image' : info.isAudio ? 'audio' : 'file';
        const itemId = info.isImage || info.isAudio ? msgId : `tr_${info.transferId}`;
        const limitMB = effectiveMaxFileMBRef.current;
        if (limitMB > 0 && info.fileSize > limitMB * 1024 * 1024) {
          onToast(
            `收到较大附件「${info.fileName}」（${formatBytes(info.fileSize)}），超过本房上限 ${formatLimitMB(limitMB)}，请手动确认接收或婉拒`
          );
        }
        setMessages((prev) => {
          if (prev.some((m) => m.id === itemId)) return prev;
          return [
            ...prev,
            {
              id: itemId,
              kind,
              senderId: peerId,
              senderName: sender?.name || '对方',
              senderAvatar: sender?.avatar,
              isSelf: false,
              timestamp: timestamp || Date.now(),
              caption,
              isScreenshot: info.isScreenshot,
              transfer: info,
            },
          ];
        });
        notifyIncomingArrival({
          itemId,
          senderName: sender?.name || '伙伴',
          previewText: info.isImage ? `[图片] ${info.fileName}` : info.isAudio ? '[语音消息]' : `[文件] ${info.fileName} (${formatBytes(info.fileSize)})`,
          isOffer: !info.isImage && !info.isAudio,
        });
        if (prefsRef.current.soundEnabled && (!isMutedRoomRef.current || (!info.isImage && !info.isAudio))) {
          playNoticeTone();
        }
      },
      onTransferUpdate: (updated) => {
        if (updated.objectUrl) trackObjectUrl(updated.objectUrl);
        setMessages((prev) =>
          prev.map((m) => {
            if (m.transfer?.transferId !== updated.transferId) return m;
            return {
              ...m,
              imageUrl: updated.isImage && updated.objectUrl ? updated.objectUrl : m.imageUrl,
              audioUrl: updated.isAudio && updated.objectUrl ? updated.objectUrl : m.audioUrl,
              transfer: {
                ...updated,
                targetPeerName: m.transfer?.targetPeerName || updated.targetPeerName,
              },
            };
          })
        );
        if (updated.status === 'completed' && !updated.isImage && !updated.isAudio) {
          const recKey = `${updated.transferId}_${updated.direction}`;
          if (!recordedCompletedTransferIdsRef.current.has(recKey)) {
            recordedCompletedTransferIdsRef.current.add(recKey);
            onRecordTransferRef.current?.({
              id: recKey,
              fileName: updated.fileName,
              fileSize: updated.fileSize,
              mimeType: updated.mimeType,
              direction: updated.direction,
              roomCode,
              peerName: updated.targetPeerName || peersRef.current[updated.targetPeerId || '']?.name || '伙伴',
              verified: updated.verified,
              createdAt: Date.now(),
            });
          }
          if (updated.direction === 'send') {
            const baseId = updated.transferId.split('_').slice(0, 3).join('_');
            if (!recordedTransferIdsRef.current.has(baseId)) {
              recordedTransferIdsRef.current.add(baseId);
              signalingRef.current?.recordActivity('file', updated.fileSize);
            }
          }
          onToast(
            updated.verified
              ? `${updated.fileName} 传输完成且 SHA-256 校验通过`
              : `${updated.fileName} 传输完成`
          );
        }
      },
      shouldAutoAccept: (info) => {
        if (info.targetPeerId && isBlockedPeerId(info.targetPeerId)) {
          return false;
        }
        const limitMB = effectiveMaxFileMBRef.current;
        if (limitMB > 0 && info.fileSize > limitMB * 1024 * 1024) {
          return false;
        }
        if (info.isImage || info.isAudio) return true;
        const maxAutoBytes = Math.max(1, prefsRef.current.autoAcceptMaxMB || 50) * 1024 * 1024;
        return prefsRef.current.autoAcceptSmallFiles && info.fileSize <= maxAutoBytes;
      },
    });
    meshRef.current = mesh;

    const sig = new SignalingClient(
      roomCode,
      passwordRef.current,
      selfPeerProfile,
      {
        onConnectionState: (connected) => setWsConnected(connected),
        onJoined: (env) => {
          if (typeof env.isOwner === 'boolean') {
            setIsOwner(env.isOwner);
          }
          if (env.room) {
            setRoomInfo(env.room);
            if (env.room.stats) setRoomStats(env.room.stats);
          }
          if (env.history) setHistoryMeta(env.history);

          const peerMap: Record<string, PeerProfile> = {};
          const activeIds: string[] = [];
          (env.peers || []).forEach((p) => {
            if (isBlockedProfile(p)) return;
            peerMap[p.peerId] = p;
            activeIds.push(p.peerId);
          });
          setPeers(peerMap);
        // 信令重连拿到全量在线列表时，同步裁掉断线期间已离房成员的残留状态
        const pruneByActive = <T,>(prev: Record<string, T>): Record<string, T> => {
          const next: Record<string, T> = {};
          for (const k of Object.keys(prev)) {
            if (peerMap[k]) next[k] = prev[k];
          }
          return next;
        };
        setLinkStatuses(pruneByActive);
        setTypingPeers(pruneByActive);
        setPeerMediaStates(pruneByActive);
        setSpeakingPeers(pruneByActive);
        setWbCursors(pruneByActive);
        setRemoteAudioStreams(pruneByActive);
        setRemoteScreenStreams(pruneByActive);

        if (env.messages && env.messages.length > 0) {
          env.messages.forEach((cm) => mergeCachedMessage(cm, true));
        }
        // 同步清理重连期间已离开的僵尸会话，避免内存与定时器残留
        mesh.syncPeers(activeIds);
      },
        onPeerJoined: (p) => {
          if (isBlockedProfile(p)) return;
          setPeers((prev) => ({ ...prev, [p.peerId]: p }));
          // 成员新加入或重连时强制重建干净的 PeerConnection，防止僵尸连接卡死
          mesh.ensurePeer(p.peerId, true);
          appendSystemMessage(`${p.name} 加入了房间`);
        },
        onPeerUpdated: (p) => {
          if (isBlockedProfile(p)) return;
          setPeers((prev) => ({ ...prev, [p.peerId]: p }));
        setMessages((prev) =>
          prev.map((m) =>
            m.senderId === p.peerId
              ? { ...m, senderName: p.name, senderAvatar: p.avatar }
              : m
          )
        );
      },
      onPeerLeft: (peerId) => {
        const leftPeer = peersRef.current[peerId];
        setPeers((prev) => {
          const next = { ...prev };
          delete next[peerId];
          return next;
        });
        setLinkStatuses((prev) => {
          const next = { ...prev };
          delete next[peerId];
          return next;
        });
        if (remoteTypingTimersRef.current[peerId]) {
          clearTimeout(remoteTypingTimersRef.current[peerId]);
          delete remoteTypingTimersRef.current[peerId];
        }
        setTypingPeers((prev) => {
          const next = { ...prev };
          delete next[peerId];
          return next;
        });
        setPeerMediaStates((prev) => {
          const next = { ...prev };
          delete next[peerId];
          return next;
        });
        setSpeakingPeers((prev) => {
          const next = { ...prev };
          delete next[peerId];
          return next;
        });
        setWbCursors((prev) => {
          const next = { ...prev };
          delete next[peerId];
          return next;
        });
        mesh.removePeer(peerId);
        if (leftPeer) appendSystemMessage(`${leftPeer.name} 离开了房间`);
      },
      onSignal: (env) => void mesh.handleSignal(env),
      onMessageCached: (env) => {
        if (env.history) setHistoryMeta(env.history);
        if (env.message && env.message.senderId !== selfPeerId) {
          mergeCachedMessage(env.message, false);
          const cm = env.message;
          const previewText =
            cm.kind === 'image'
              ? '[图片]'
              : cm.kind === 'audio'
                ? '[语音消息]'
                : cm.kind === 'file_notice'
                  ? `[文件] ${cm.fileName || '附件'}`
                  : cm.text || '[新消息]';
          const mentioned = checkMentionMe(cm.text);
          notifyIncomingArrival({
            itemId: cm.id,
            senderName: cm.senderName || '伙伴',
            previewText,
            mentionedMe: mentioned,
          });
          if (prefsRef.current.soundEnabled && (!isMutedRoomRef.current || mentioned)) playNoticeTone();
        }
      },
      onMessageRecalled: (env) => {
        if (env.history) setHistoryMeta(env.history);
        if (env.messageId) {
          applyMessageRecalled(env.messageId, env.fromPeerId, env.actorName);
        }
      },
      onRetentionUpdated: (env) => {
        if (env.history) setHistoryMeta(env.history);
        if (typeof env.retentionDays === 'number') {
          const days = env.retentionDays;
          setRoomInfo((prev) => (prev ? { ...prev, retentionDays: days } : prev));
          const desc = days === 0 ? '阅后即焚（零缓存）' : `最后聊天后保留 ${days} 天`;
          appendSystemMessage(`${env.actorName || '伙伴'} 将保鲜期设为：${desc}`);
        }
      },
      onRoomSettingsUpdated: (env) => {
        setRoomInfo((prev) => {
          if (!prev) return prev;
          return {
            ...prev,
            maxFileSizeMB: typeof env.maxFileSizeMB === 'number' ? env.maxFileSizeMB : prev.maxFileSizeMB,
            allowRandom: typeof env.allowRandom === 'boolean' ? env.allowRandom : prev.allowRandom,
            hasPassword: typeof env.hasPassword === 'boolean' ? env.hasPassword : prev.hasPassword,
            topic: typeof env.topic === 'string' ? env.topic : prev.topic,
          };
        });
        if (typeof env.maxFileSizeMB === 'number') {
          appendSystemMessage(
            `${env.actorName || '伙伴'} 将单附件大小上限调整为：${formatLimitMB(env.maxFileSizeMB)}`
          );
        }
        if (typeof env.hasPassword === 'boolean' || typeof env.topic === 'string') {
          appendSystemMessage(`${env.actorName || '房主'} 更新了房间设置`);
        }
      },
      onRoomStatsUpdated: (env) => {
        if (env.stats) {
          setRoomStats(env.stats);
        }
      },
      onHistoryCleared: (env) => {
        if (env.history) setHistoryMeta(env.history);
        revokeAllTrackedUrls();
        setReplyingTo(null);
        setRecalledTexts({});
        setMessages([
          {
            id: `cleared_${Date.now()}`,
            kind: 'system',
            senderId: 'system',
            senderName: '系统',
            isSelf: false,
            timestamp: Date.now(),
            text: `${env.actorName || '伙伴'} 已清空服务器缓存记录`,
          },
        ]);
        onToast('房间服务器聊天缓存已清空');
      },
      onRoomDestroyed: (reason) => {
        mesh.destroyAll();
        revokeAllTrackedUrls();
        onExitToLobby(reason);
      },
      onError: (code, msg) => {
        if (code === 'RECALL_EXPIRED') {
          onToast('已超过 2 分钟，无法撤回');
          return;
        }
        if (FATAL_WS_ERRORS.has(code)) {
          mesh.destroyAll();
          revokeAllTrackedUrls();
          onExitToLobby(msg);
        } else {
          onToast(msg);
        }
      },
    }, ownerTokenRef.current);
    signalingRef.current = sig;
    sig.connect();

    return () => {
      mountedRef.current = false;
      voiceCancelledRef.current = true;
      Object.values(remoteTypingTimersRef.current).forEach((tid) => clearTimeout(tid));
      remoteTypingTimersRef.current = {};
      ambientEngine.stop();
      stopAllLocalMedia();
      if (recordTimerRef.current !== null) {
        clearInterval(recordTimerRef.current);
        recordTimerRef.current = null;
      }
      if (recorderRef.current && recorderRef.current.state !== 'inactive') {
        try {
          recorderRef.current.stop();
        } catch {
          // 忽略停止异常
        }
      }
      sig.disconnect();
      mesh.destroyAll();
      revokeAllTrackedUrls();
    };
  }, [
    roomCode,
    selfPeerId,
    appendSystemMessage,
    applyMessageRecalled,
    applyWhiteboardEvent,
    checkMentionMe,
    mergeCachedMessage,
    notifyIncomingArrival,
    onExitToLobby,
    onToast,
    revokeAllTrackedUrls,
    stopAllLocalMedia,
    toggleMessageReaction,
    trackObjectUrl,
  ]);

  useEffect(() => {
    signalingRef.current?.updateProfile({
      peerId: selfPeerId,
      clientId: profile.clientId,
      name: profile.name,
      avatar: profile.avatar,
      joinedAt: Date.now(),
    });
    setMessages((prev) =>
      prev.map((m) =>
        m.isSelf ? { ...m, senderName: profile.name, senderAvatar: profile.avatar } : m
      )
    );
  }, [profile, selfPeerId]);

  // 附近设备邀请发起方进入房间后每 3 秒轮询被邀请方响应状态
  useEffect(() => {
    if (!pendingNearbyInvite?.inviteId) {
      setNearbyInviteStatus(null);
      return;
    }
    setNearbyInviteStatus('pending');
    let stopped = false;
    let timer: number | null = null;

    const poll = async () => {
      if (stopped) return;
      try {
        const res = await fetchNearbyInviteStatus(pendingNearbyInvite.inviteId);
        if (stopped) return;
        setNearbyInviteStatus(res.status);
        if (res.status !== 'pending') {
          if (timer !== null) {
            clearInterval(timer);
            timer = null;
          }
          if (res.status === 'accepted') {
            window.setTimeout(() => {
              onClearPendingNearbyInvite?.();
            }, 3000);
          }
        }
      } catch {
        // 忽略瞬态轮询错误
      }
    };

    void poll();
    timer = window.setInterval(() => {
      void poll();
    }, 3000);

    return () => {
      stopped = true;
      if (timer !== null) clearInterval(timer);
    };
  }, [pendingNearbyInvite?.inviteId, onClearPendingNearbyInvite]);

  const linkStatusPill = useMemo(() => {
    const peerList = Object.values(peers);
    if (peerList.length === 0) {
      return { cls: 'pill-neutral', dot: 'pulse', text: '声波雷达等待加入' };
    }
    const readyList = peerList
      .map((p) => linkStatuses[p.peerId])
      .filter((s): s is PeerLinkStatus => Boolean(s?.dataReady));

    if (readyList.length === 0) {
      return { cls: 'pill-amber', dot: 'amber', text: '正在打洞直连...' };
    }
    const anyRelay = readyList.some((s) => s.transport === 'relay-turn');
    const allLan = readyList.every((s) => s.transport === 'p2p-lan');
    const rtt = readyList.find((s) => typeof s.rttMs === 'number')?.rttMs;
    const rttLabel = typeof rtt === 'number' ? ` · ${rtt}ms` : '';

    if (anyRelay) return { cls: 'pill-amber', dot: 'amber', text: `服务器中转${rttLabel}` };
    if (allLan) return { cls: 'pill-emerald', dot: 'emerald', text: `局域网直连${rttLabel}` };
    return { cls: 'pill-emerald', dot: 'emerald', text: `跨公网 P2P 直连${rttLabel}` };
  }, [peers, linkStatuses]);

  const roomTransfers = useMemo(
    () =>
      messages
        .map((m) => m.transfer)
        .filter((t): t is FileTransferInfo => Boolean(t && !t.isAudio)),
    [messages]
  );

  const directPeerCount = useMemo(
    () => Object.values(peers).filter((p) => Boolean(linkStatuses[p.peerId]?.dataReady)).length,
    [peers, linkStatuses]
  );

  // 计算待接收的批量文件列表（>= 2 个时显示「一键接收全部」快捷条）
  const pendingIncomingFiles = useMemo(() => {
    return messages.filter(
      (m) =>
        m.kind === 'file' &&
        m.transfer &&
        m.transfer.direction === 'receive' &&
        m.transfer.status === 'offering' &&
        m.transfer.targetPeerId
    );
  }, [messages]);

  // 搜索与类型过滤后的消息列表
  const visibleMessages = useMemo(() => {
    const kw = searchKeyword.trim().toLowerCase();
    return messages.filter((m) => {
      if (filterTab === 'image' && m.kind !== 'image') return false;
      if (filterTab === 'file' && m.kind !== 'file') return false;
      if (filterTab === 'audio' && m.kind !== 'audio') return false;
      if (filterTab === 'link' && !/(https?:\/\/[^\s<>"']+)/i.test(m.text || '')) return false;
      if (!kw) return true;
      if (m.isSecret) return false; // 搜索不暴露未揭晓密信明文
      const haystack = [
        m.text,
        m.caption,
        m.senderName,
        m.transfer?.fileName,
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      return haystack.includes(kw);
    });
  }, [messages, searchKeyword, filterTab]);

  // 一键语音连麦：动态挂载 WebRTC 音频轨道并通知全房
  const handleToggleVoiceCall = async () => {
    if (micActive) {
      if (localMicStreamRef.current) {
        localMicStreamRef.current.getTracks().forEach((t) => t.stop());
        localMicStreamRef.current = null;
      }
      setMicActive(false);
      setMicMuted(false);
      meshRef.current?.setLocalMicStream(null);
      appendSystemMessage('你已退出语音连麦');
      return;
    }

    if (!navigator.mediaDevices || typeof navigator.mediaDevices.getUserMedia !== 'function') {
      onToast('语音连麦需在 HTTPS 或 localhost 环境下开启麦克风权限（公网 HTTPS 部署后自动生效）');
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
      if (!mountedRef.current) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      localMicStreamRef.current = stream;
      setMicActive(true);
      setMicMuted(false);
      meshRef.current?.setLocalMicStream(stream, false);
      appendSystemMessage('你开启了实时语音连麦，房间伙伴可直接听到你说话');
    } catch {
      onToast('无法开启麦克风，请检查浏览器权限或 HTTPS 环境');
    }
  };

  const handleToggleMicMute = () => {
    const next = !micMuted;
    setMicMuted(next);
    meshRef.current?.setLocalMicMuted(next);
    onToast(next ? '麦克风已静音' : '已恢复开麦');
  };

  // 一键实时屏幕共享（WebRTC 动态挂载视频轨，监听原生「停止共享」按钮）
  const handleToggleScreenShare = async () => {
    if (localScreenStreamRef.current) {
      localScreenStreamRef.current.getTracks().forEach((t) => t.stop());
      localScreenStreamRef.current = null;
      setLocalScreenStream(null);
      meshRef.current?.setLocalScreenStream(null);
      appendSystemMessage('你已停止实时屏幕共享');
      return;
    }

    if (!supportsScreenCapture()) {
      onToast('实时屏幕共享需在 HTTPS 或 localhost 环境下使用桌面浏览器（公网部署后自动生效）');
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getDisplayMedia({
        video: { frameRate: { ideal: 24, max: 30 } },
        audio: false,
      });
      if (!mountedRef.current) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      const vTrack = stream.getVideoTracks()[0];
      if (vTrack) {
        vTrack.onended = () => {
          if (localScreenStreamRef.current === stream) {
            localScreenStreamRef.current = null;
            setLocalScreenStream(null);
            meshRef.current?.setLocalScreenStream(null);
            appendSystemMessage('实时屏幕共享已结束');
          }
        };
      }
      localScreenStreamRef.current = stream;
      setLocalScreenStream(stream);
      meshRef.current?.setLocalScreenStream(stream);
      appendSystemMessage('你开启了实时屏幕共享，房间伙伴正在顶部舞台同步观看');
    } catch (err) {
      if ((err as DOMException)?.name !== 'NotAllowedError') {
        onToast('屏幕共享开启失败，请确认系统录屏权限');
      }
    }
  };

  // 打开多人同屏协作白板并向已连线伙伴请求最新画布同步
  const handleOpenWhiteboard = () => {
    setShowWhiteboard(true);
    setWbInviteBanner(null);
    meshRef.current?.broadcastWhiteboard({ type: 'opened', actorName: profile.name });
    meshRef.current?.broadcastWhiteboard({ type: 'sync-req' });
  };

  const handleEmitWhiteboard = (ev: WhiteboardEvent) => {
    applyWhiteboardEvent(null, ev);
    meshRef.current?.broadcastWhiteboard(ev);
  };

  // 全房同步番茄钟控制
  const broadcastNextPomodoro = (next: PomodoroState) => {
    setPomodoro(next);
    meshRef.current?.broadcastPomodoro(next);
  };

  const handlePomodoroToggle = () => {
    const now = Date.now();
    if (pomodoro.running) {
      const left = Math.max(1, Math.ceil((pomodoro.endAt - now) / 1000));
      broadcastNextPomodoro({
        ...pomodoro,
        running: false,
        remainingSec: left,
        endAt: 0,
        updatedAt: now,
      });
    } else {
      const secs = pomodoro.remainingSec > 0 ? pomodoro.remainingSec : POMODORO_DURATIONS[pomodoro.mode];
      broadcastNextPomodoro({
        ...pomodoro,
        running: true,
        remainingSec: secs,
        endAt: now + secs * 1000,
        startedBy: profile.name,
        updatedAt: now,
      });
      appendSystemMessage(`${profile.name} 启动了全房同步番茄钟（${Math.ceil(secs / 60)} 分钟）`);
    }
  };

  const handlePomodoroSwitchMode = (mode: PomodoroMode) => {
    const secs = POMODORO_DURATIONS[mode];
    broadcastNextPomodoro({
      running: false,
      mode,
      remainingSec: secs,
      endAt: 0,
      startedBy: profile.name,
      updatedAt: Date.now(),
    });
  };

  const handlePomodoroReset = () => {
    const secs = POMODORO_DURATIONS[pomodoro.mode];
    broadcastNextPomodoro({
      running: false,
      mode: pomodoro.mode,
      remainingSec: secs,
      endAt: 0,
      startedBy: profile.name,
      updatedAt: Date.now(),
    });
  };

  const handleSelectAmbient = (sound: AmbientSoundType) => {
    setAmbientSound(sound);
    ambientEngine.start(sound, ambientVolume);
  };

  const handleChangeAmbientVolume = (vol: number) => {
    setAmbientVolume(vol);
    ambientEngine.setVolume(vol);
  };

  const handleReactMessage = (msgId: string, emoji: string) => {
    toggleMessageReaction(msgId, emoji, selfPeerId);
    meshRef.current?.broadcastReaction(msgId, emoji);
  };

  const handleReplyMessage = (item: ChatItem) => {
    const preview =
      item.kind === 'text'
        ? item.isSecret
          ? '[防窥密信]'
          : (item.text || '').slice(0, 60)
        : item.kind === 'image'
          ? `[图片] ${item.caption || item.transfer?.fileName || ''}`
          : item.kind === 'audio'
            ? `[语音 ${item.caption || ''}]`
            : `[文件] ${item.transfer?.fileName || ''}`;
    setReplyingTo({
      id: item.id,
      senderName: item.senderName,
      preview: preview.trim(),
    });
  };

  const handleRecallMessage = (msgId: string) => {
    const target = messagesRef.current.find((m) => m.id === msgId);
    if (!target || !target.isSelf || target.kind === 'file' || target.kind === 'system') {
      return;
    }
    if (Date.now() - target.timestamp > RECALL_WINDOW_MS) {
      onToast('已超过 2 分钟，无法撤回');
      return;
    }
    applyMessageRecalled(msgId, selfPeerId, profile.name);
    meshRef.current?.broadcastRecall(msgId);
    signalingRef.current?.recallMessage(msgId);
    onToast('已撤回消息');
  };

  const handleApplyRoomMaxFileMB = (nextMB: number) => {
    const cap = serverConfig.maxAttachmentMB || 0;
    if (!Number.isFinite(nextMB) || nextMB < 0 || nextMB > 102400) {
      onToast('请输入 0（不限制）到 102400 之间的数字（单位 MB）');
      return;
    }
    const cleanMB = Math.round(nextMB);
    if (cap > 0 && (cleanMB === 0 || cleanMB > cap)) {
      onToast(`服务端已限制单附件最高不超过 ${formatLimitMB(cap)}`);
      return;
    }
    signalingRef.current?.updateMaxFileSize(cleanMB);
    setCustomLimitMBInput('');
  };

  const handleExportChatLog = () => {
    const lines = messages.map((m) => {
      const ts = new Date(m.timestamp).toLocaleString();
      if (m.kind === 'system') return `[${ts}] [系统] ${m.text || ''}`;
      if (m.kind === 'text') {
        const body = m.isSecret ? '[防窥密信已脱敏隐藏]' : m.text || '';
        const quote = m.replyTo ? `（引用 @${m.replyTo.senderName}：${m.replyTo.preview}）` : '';
        return `[${ts}] ${m.senderName}${quote}: ${body}`;
      }
      if (m.kind === 'image') return `[${ts}] ${m.senderName}: [图片] ${m.caption || m.transfer?.fileName || ''}`;
      if (m.kind === 'audio') return `[${ts}] ${m.senderName}: [语音] ${m.caption || ''}`;
      return `[${ts}] ${m.senderName}: [文件] ${m.transfer?.fileName || ''} (${formatBytes(m.transfer?.fileSize || 0)})`;
    });
    const content = `# BayChat 房间 #${roomCode} 聊天记录\n导出时间：${new Date().toLocaleString()}\n共 ${lines.length} 条记录\n\n${lines.join('\n')}\n`;
    const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
    triggerBlobDownload(blob, `baychat-${roomCode}-${Date.now()}.txt`);
    onToast('已导出聊天记录到本地');
  };

  const handleSendText = () => {
    const clean = inputText.trim();
    if (!clean) return;

    const id = `msg_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
    const timestamp = Date.now();
    const quote = replyingTo || undefined;
    const p2pSent = meshRef.current?.broadcastText(id, clean, timestamp, quote) || 0;

    setMessages((prev) => [
      ...prev,
      {
        id,
        kind: 'text',
        senderId: selfPeerId,
        senderName: profile.name,
        senderAvatar: profile.avatar,
        isSelf: true,
        timestamp,
        text: clean,
        replyTo: quote,
      },
    ]);

    if (historyMetaRef.current.retentionDays > 0) {
      signalingRef.current?.cacheMessage({
        id,
        kind: 'text',
        senderId: selfPeerId,
        senderName: profile.name,
        senderAvatar: profile.avatar,
        text: clean,
        replyToId: quote?.id,
        replyToSender: quote?.senderName,
        replyToPreview: quote?.preview,
        timestamp,
      });
    } else if (p2pSent === 0 && Object.keys(peersRef.current).length === 0) {
      onToast('当前为阅后即焚模式且暂无他人在线，消息仅在本地展示');
    }

    setInputText('');
    setReplyingTo(null);
    meshRef.current?.broadcastTyping(false);
    signalingRef.current?.recordActivity('message', 0);
  };

  const sendImageBlob = async (
    rawBlob: File | Blob,
    options: { fileName: string; isScreenshot?: boolean; caption?: string; compress?: boolean }
  ) => {
    const shouldCompress = options.compress ?? prefsRef.current.imageSendMode === 'compressed';
    const blob = shouldCompress ? await compressChatImage(rawBlob) : rawBlob;
    if (!checkAttachmentAllowed(blob.size, options.fileName)) {
      return;
    }

    const timestamp = Date.now();
    const idPrefix = `img_${timestamp}_${Math.random().toString(36).slice(2, 7)}`;
    const localUrl = trackObjectUrl(URL.createObjectURL(blob));

    const transfers =
      (await meshRef.current?.sendFileToAll(blob, {
        transferIdPrefix: idPrefix,
        fileName: options.fileName,
        mimeType: blob.type || 'image/png',
        isImage: true,
        isScreenshot: options.isScreenshot,
        caption: options.caption,
        timestamp,
      })) || [];

    const primaryTransfer = transfers[0];
    setMessages((prev) => [
      ...prev,
      {
        id: idPrefix,
        kind: 'image',
        senderId: selfPeerId,
        senderName: profile.name,
        senderAvatar: profile.avatar,
        isSelf: true,
        timestamp,
        caption: options.caption,
        imageUrl: localUrl,
        isScreenshot: options.isScreenshot,
        transfer: primaryTransfer,
      },
    ]);

    if (historyMetaRef.current.retentionDays > 0) {
      try {
        const cacheBlob =
          blob.size > serverConfig.historyMaxMediaBytes
            ? await compressChatImage(blob, 1600, 0.78)
            : blob;
        if (cacheBlob.size <= serverConfig.historyMaxMediaBytes) {
          const uploaded = await uploadCachedMedia(roomCode, password, cacheBlob);
          signalingRef.current?.cacheMessage({
            id: idPrefix,
            kind: 'image',
            senderId: selfPeerId,
            senderName: profile.name,
            senderAvatar: profile.avatar,
            caption: options.caption,
            mediaId: uploaded.mediaId,
            mediaMime: uploaded.mime,
            fileName: options.fileName,
            fileSize: uploaded.size,
            isScreenshot: options.isScreenshot,
            timestamp,
          });
        }
      } catch {
        // 缓存失败不影响 P2P 实时发送
      }
    } else if (transfers.length === 0 && Object.keys(peersRef.current).length === 0) {
      onToast('当前为阅后即焚模式且暂无他人在线，图片仅在本地展示');
    }
    signalingRef.current?.recordActivity('message', 0);
  };

  // 发送语音对讲泡泡
  const sendVoiceBlob = async (blob: Blob, durationSec: number) => {
    const ext = blob.type.includes('mp4') ? 'm4a' : blob.type.includes('ogg') ? 'ogg' : 'webm';
    const fileName = `voice-${Date.now()}.${ext}`;
    if (!checkAttachmentAllowed(blob.size, fileName)) {
      return;
    }

    const timestamp = Date.now();
    const idPrefix = `aud_${timestamp}_${Math.random().toString(36).slice(2, 7)}`;
    const localUrl = trackObjectUrl(URL.createObjectURL(blob));
    const caption = `${Math.max(1, durationSec)}″`;

    const transfers =
      (await meshRef.current?.sendFileToAll(blob, {
        transferIdPrefix: idPrefix,
        fileName,
        mimeType: blob.type || 'audio/webm',
        isImage: false,
        isAudio: true,
        caption,
        timestamp,
      })) || [];

    setMessages((prev) => [
      ...prev,
      {
        id: idPrefix,
        kind: 'audio',
        senderId: selfPeerId,
        senderName: profile.name,
        senderAvatar: profile.avatar,
        isSelf: true,
        timestamp,
        caption,
        audioUrl: localUrl,
        transfer: transfers[0],
      },
    ]);

    if (historyMetaRef.current.retentionDays > 0 && blob.size <= serverConfig.historyMaxMediaBytes) {
      try {
        const uploaded = await uploadCachedMedia(roomCode, password, blob);
        signalingRef.current?.cacheMessage({
          id: idPrefix,
          kind: 'audio',
          senderId: selfPeerId,
          senderName: profile.name,
          senderAvatar: profile.avatar,
          caption,
          mediaId: uploaded.mediaId,
          mediaMime: uploaded.mime,
          fileName,
          fileSize: uploaded.size,
          timestamp,
        });
      } catch {
        // 语音缓存失败不阻塞 P2P
      }
    } else if (transfers.length === 0 && Object.keys(peersRef.current).length === 0) {
      onToast('当前为阅后即焚模式且暂无他人在线，语音仅在本地展示');
    }
    signalingRef.current?.recordActivity('message', 0);
  };

  // 切换开始/停止语音录制
  const handleToggleVoiceRecord = async () => {
    if (recordingVoice && recorderRef.current) {
      if (recordTimerRef.current !== null) {
        clearInterval(recordTimerRef.current);
        recordTimerRef.current = null;
      }
      setRecordingVoice(false);
      if (recorderRef.current.state !== 'inactive') {
        recorderRef.current.stop();
      }
      return;
    }

    if (!navigator.mediaDevices || typeof navigator.mediaDevices.getUserMedia !== 'function' || typeof MediaRecorder === 'undefined') {
      onToast('语音对讲需在 HTTPS 或 localhost 环境下开启麦克风权限');
      return;
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (!mountedRef.current) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
        ? 'audio/webm;codecs=opus'
        : MediaRecorder.isTypeSupported('audio/webm')
          ? 'audio/webm'
          : 'audio/mp4';

      const rec = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
      recordChunksRef.current = [];
      recordStartRef.current = Date.now();
      voiceCancelledRef.current = false;
      setRecordSeconds(0);

      rec.ondataavailable = (ev) => {
        if (ev.data && ev.data.size > 0) recordChunksRef.current.push(ev.data);
      };
      rec.onstop = () => {
        stream.getTracks().forEach((t) => t.stop());
        if (recordTimerRef.current !== null) {
          clearInterval(recordTimerRef.current);
          recordTimerRef.current = null;
        }
        if (voiceCancelledRef.current) return;
        const duration = Math.round((Date.now() - recordStartRef.current) / 1000);
        const blob = new Blob(recordChunksRef.current, { type: rec.mimeType || 'audio/webm' });
        if (blob.size > 200) {
          void sendVoiceBlob(blob, duration);
        }
      };

      recorderRef.current = rec;
      rec.start();
      setRecordingVoice(true);
      recordTimerRef.current = window.setInterval(() => {
        const elapsed = Math.floor((Date.now() - recordStartRef.current) / 1000);
        setRecordSeconds(elapsed);
        if (elapsed >= 60) {
          if (recordTimerRef.current !== null) {
            clearInterval(recordTimerRef.current);
            recordTimerRef.current = null;
          }
          setRecordingVoice(false);
          if (recorderRef.current?.state === 'recording') {
            recorderRef.current.stop();
          }
        }
      }, 500);
    } catch {
      onToast('无法访问麦克风，请检查浏览器权限或 HTTPS 环境');
    }
  };

  const sendFiles = async (fileList: FileList | File[]) => {
    const files = Array.from(fileList);
    if (files.length === 0) return;

    const connectedPeers = meshRef.current?.getConnectedPeerCount() || 0;
    const maxCacheFileBytes = serverConfigRef.current.historyMaxFileBytes || 100 * 1024 * 1024;

    for (const file of files) {
      if (!checkAttachmentAllowed(file.size, file.name)) {
        continue;
      }
      if (file.type.startsWith('image/') && file.size <= 25 * 1024 * 1024) {
        await sendImageBlob(file, { fileName: file.name });
        continue;
      }

      const retention = historyMetaRef.current.retentionDays;
      const canCloudCache = retention > 0 && file.size > 0 && file.size <= maxCacheFileBytes;

      if (connectedPeers === 0 && !canCloudCache) {
        onToast(
          retention <= 0
            ? '当前为阅后即焚模式，大文件直传需等小伙伴进房后发送'
            : `超过 ${formatBytes(maxCacheFileBytes)} 的超大文件仅走 P2P 满速直传，需等小伙伴进房后发送`
        );
        continue;
      }

      const timestamp = Date.now();
      const idPrefix = `file_${timestamp}_${Math.random().toString(36).slice(2, 7)}`;

      if (connectedPeers > 0) {
        const transfers =
          (await meshRef.current?.sendFileToAll(file, {
            transferIdPrefix: idPrefix,
            fileName: file.name,
            mimeType: file.type || 'application/octet-stream',
            isImage: false,
            timestamp,
          })) || [];

        transfers.forEach((tr) => {
          const peerName = tr.targetPeerId ? peersRef.current[tr.targetPeerId]?.name : undefined;
          setMessages((prev) => [
            ...prev,
            {
              id: `tr_${tr.transferId}`,
              kind: 'file',
              senderId: selfPeerId,
              senderName: profile.name,
              senderAvatar: profile.avatar,
              isSelf: true,
              timestamp,
              transfer: { ...tr, targetPeerName: peerName },
            },
          ]);
        });

        if (canCloudCache) {
          // 同步上传一份至房间限时云端缓存（≤100MB），刷新页面或稍后进房也能直接下载附件
          void uploadCachedFile(roomCode, password, file, file.name)
            .then((uploaded) => {
              const dlUrl = buildCachedFileUrl(roomCode, uploaded.fileId, password);
              setMessages((prev) =>
                prev.map((m) =>
                  m.kind === 'file' && m.transfer && m.transfer.transferId.startsWith(`${idPrefix}_`)
                    ? { ...m, transfer: { ...m.transfer, cachedDownloadUrl: dlUrl } }
                    : m
                )
              );
              signalingRef.current?.cacheMessage({
                id: `fnotice_${idPrefix}`,
                kind: 'file_notice',
                senderId: selfPeerId,
                senderName: profile.name,
                senderAvatar: profile.avatar,
                mediaId: uploaded.fileId,
                mediaMime: uploaded.mime,
                fileName: file.name,
                fileSize: file.size,
                timestamp,
              });
            })
            .catch(() => {
              // 缓存上传失败不影响 P2P，降级保存历史文件记录卡
              signalingRef.current?.cacheMessage({
                id: `fnotice_${idPrefix}`,
                kind: 'file_notice',
                senderId: selfPeerId,
                senderName: profile.name,
                senderAvatar: profile.avatar,
                fileName: file.name,
                fileSize: file.size,
                timestamp,
              });
            });
        } else if (retention > 0) {
          // 超大文件（>100MB）走纯 P2P，服务端仅记录文件卡片摘要，刷新后依然可在历史/文件筛选中清晰展示
          signalingRef.current?.cacheMessage({
            id: `fnotice_${idPrefix}`,
            kind: 'file_notice',
            senderId: selfPeerId,
            senderName: profile.name,
            senderAvatar: profile.avatar,
            fileName: file.name,
            fileSize: file.size,
            timestamp,
          });
        }
      } else if (canCloudCache) {
        // 无人在线时（如固定秘密基地跨端留存），≤100MB 附件直接上传到房间限时云端缓存
        const noticeId = `fnotice_${idPrefix}`;
        setMessages((prev) => [
          ...prev,
          {
            id: noticeId,
            kind: 'file',
            senderId: selfPeerId,
            senderName: profile.name,
            senderAvatar: profile.avatar,
            isSelf: true,
            timestamp,
            transfer: {
              transferId: noticeId,
              streamId: 0,
              fileName: file.name,
              fileSize: file.size,
              mimeType: file.type || 'application/octet-stream',
              isImage: false,
              direction: 'send',
              status: 'transferring',
              transferredBytes: 0,
              speedBps: 0,
              etaSeconds: 0,
            },
          },
        ]);
        try {
          const uploaded = await uploadCachedFile(roomCode, password, file, file.name);
          const dlUrl = buildCachedFileUrl(roomCode, uploaded.fileId, password);
          setMessages((prev) =>
            prev.map((m) =>
              m.id === noticeId && m.transfer
                ? {
                    ...m,
                    mediaId: uploaded.fileId,
                    transfer: {
                      ...m.transfer,
                      status: 'completed',
                      transferredBytes: file.size,
                      cachedDownloadUrl: dlUrl,
                    },
                  }
                : m
            )
          );
          signalingRef.current?.cacheMessage({
            id: noticeId,
            kind: 'file_notice',
            senderId: selfPeerId,
            senderName: profile.name,
            senderAvatar: profile.avatar,
            mediaId: uploaded.fileId,
            mediaMime: uploaded.mime,
            fileName: file.name,
            fileSize: file.size,
            timestamp,
          });
          signalingRef.current?.recordActivity('file', file.size);
          onToast(`「${file.name}」已缓存至房间，刷新或换设备随时可下载`);
        } catch (err) {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === noticeId && m.transfer
                ? {
                    ...m,
                    transfer: {
                      ...m.transfer,
                      status: 'error',
                      errorMessage: err instanceof Error ? err.message : '附件缓存上传失败',
                    },
                  }
                : m
            )
          );
        }
      }
    }
  };

  const handleSendPendingPaste = async () => {
    if (!pendingPaste) return;
    const item = pendingPaste;
    const caption = inputText.trim();
    setPendingPaste(null);
    setInputText('');
    await sendImageBlob(item.file, {
      fileName: item.fileName,
      isScreenshot: true,
      caption,
      compress: item.compress,
    });
    URL.revokeObjectURL(item.previewUrl);
  };

  // 统一处理剪贴板图片预览与文件粘贴（支持输入框内及全局 Ctrl+V）
  const processClipboardPaste = (
    clipboardData: DataTransfer | null,
    preventDefault: () => void
  ): boolean => {
    const items = clipboardData?.items;
    if (!items) return false;
    const extraFiles: File[] = [];
    for (let i = 0; i < items.length; i++) {
      const it = items[i];
      if (it.kind !== 'file') continue;
      const file = it.getAsFile();
      if (!file) continue;
      if (it.type.startsWith('image/')) {
        preventDefault();
        if (pendingPaste) URL.revokeObjectURL(pendingPaste.previewUrl);
        setPendingPaste({
          file,
          previewUrl: URL.createObjectURL(file),
          fileName: file.name || `screenshot-${Date.now()}.png`,
          compress: prefsRef.current.imageSendMode === 'compressed',
        });
        return true;
      }
      extraFiles.push(file);
    }
    if (extraFiles.length > 0) {
      preventDefault();
      void sendFiles(extraFiles);
      return true;
    }
    return false;
  };

  const windowPasteHandlerRef = useRef<(e: ClipboardEvent) => void>(() => {});
  windowPasteHandlerRef.current = (e: ClipboardEvent) => {
    if (e.defaultPrevented) return;
    // 若当前打开了白板/设置/举报等独立弹窗且焦点在输入框中，避免抢占弹窗粘贴
    const hasBlockingModal =
      showWhiteboard ||
      roomProfileOpen ||
      Boolean(reportTarget) ||
      showInvite ||
      confirmClearOpen ||
      confirmDestroyOpen ||
      planeComposerOpen ||
      forwardModalOpen ||
      Boolean(lightboxData);
    const activeEl = document.activeElement as HTMLElement | null;
    const isEditableTarget = Boolean(
      activeEl &&
        activeEl !== composerInputRef.current &&
        (activeEl.tagName === 'INPUT' ||
          activeEl.tagName === 'TEXTAREA' ||
          activeEl.isContentEditable)
    );
    if (hasBlockingModal && isEditableTarget) return;
    processClipboardPaste(e.clipboardData, () => e.preventDefault());
  };

  useEffect(() => {
    const onWindowPaste = (e: ClipboardEvent) => windowPasteHandlerRef.current(e);
    window.addEventListener('paste', onWindowPaste);
    return () => window.removeEventListener('paste', onWindowPaste);
  }, []);

  const handleComposerPaste = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    processClipboardPaste(e.clipboardData, () => e.preventDefault());
  };

  const handleAcceptFile = async (
    peerId: string,
    transferId: string,
    mode: 'memory' | 'disk' | 'resume',
    fileName: string,
    fileSize: number,
    resumeKey?: string
  ) => {
    try {
      let diskStream = null;
      if (mode === 'disk') {
        diskStream = await openDiskWriteStream(fileName, fileSize, resumeKey);
        if (!diskStream) return;
      } else if (mode === 'resume') {
        diskStream = await openDiskResumeStream(fileName, fileSize, resumeKey);
        if (!diskStream) return;
      }
      const started = meshRef.current?.acceptIncomingTransfer(peerId, transferId, diskStream);
      if (!started) {
        if (diskStream) void diskStream.close().catch(() => {});
        onToast('发送方已离线或该传输已失效，请让对方重新发送文件');
      } else if (diskStream && diskStream.initialOffset > 0) {
        onToast(`正在从 ${formatBytes(diskStream.initialOffset)} 断点续传「${fileName}」`);
      }
    } catch (err) {
      onToast(err instanceof Error ? err.message : '无法打开保存位置');
    }
  };

  // 向原发送方主动发起断点续传重发请求（优先复用已缓存断点文件）
  const handleRequestResume = async (peerId: string, transfer: FileTransferInfo) => {
    if (!transfer.resumeKey) return;
    try {
      const cachedOffset = await getCachedResumeOffset(
        transfer.fileName,
        transfer.fileSize,
        transfer.resumeKey
      );
      let diskStream = null;
      let offset = cachedOffset;
      if (cachedOffset > 0) {
        diskStream = await openDiskResumeStream(
          transfer.fileName,
          transfer.fileSize,
          transfer.resumeKey
        );
        offset = diskStream?.initialOffset || 0;
      }
      const ok = meshRef.current?.sendResumeRequest(
        peerId,
        transfer.resumeKey,
        transfer.fileName,
        transfer.fileSize,
        offset,
        diskStream
      );
      if (!ok) {
        if (diskStream) void diskStream.close().catch(() => {});
        onToast('对方不在线或 P2P 通道未就绪，无法请求续传');
      } else {
        onToast(`已向对方请求续传「${transfer.fileName}」...`);
      }
    } catch (err) {
      onToast(err instanceof Error ? err.message : '无法发起续传请求');
    }
  };

  const handleAcceptAllPending = () => {
    pendingIncomingFiles.forEach((m) => {
      if (m.transfer?.targetPeerId) {
        meshRef.current?.acceptIncomingTransfer(m.transfer.targetPeerId, m.transfer.transferId, null);
      }
    });
    onToast(`已开始批量接收 ${pendingIncomingFiles.length} 个文件`);
  };

  // 切换常用固定房间或最近房间前先探测是否仍存在及是否需要密码
  const handleSwitchSavedFixed = async (r: {
    code: string;
    password?: string;
    ownerToken?: string;
    mode?: RoomMode;
  }) => {
    setSidebarOpen(false);
    try {
      const info = await fetchRoomInfo(r.code);
      if (info.hasPassword && !r.password) {
        signalingRef.current?.leaveRoom();
        onExitToLobby(`房间 #${r.code} 设有密码，请在大厅输入密码进入`);
        return;
      }
      onSwitchRoom(r.code, r.password || '', info.mode, r.ownerToken);
    } catch (err) {
      if (
        err instanceof ApiError &&
        (err.errorCode === 'ROOM_NOT_FOUND' || err.errorCode === 'ROOM_EXPIRED')
      ) {
        onRemoveRecentRoom(r.code);
        onToast(`房间 #${r.code} 已销毁或失效，已从最近记录移除`);
        return;
      }
      onToast(err instanceof Error ? err.message : '无法切换到该房间');
    }
  };

  // 「更换口令」流程：新建继承设置的临时房 -> 广播 room-moved -> 800ms 后销毁旧房 -> 发起方切入新房
  const handleRotateEphemeralCode = async () => {
    if (rotatingCode) return;
    setRotatingCode(true);
    try {
      const nextRoom = await createRoom({
        mode: 'ephemeral',
        password: password || undefined,
        allowRandom: password ? false : roomInfo?.allowRandom ?? false,
        maxPeers: roomInfo?.maxPeers || serverConfig.maxRoomPeers,
        retentionDays: historyMeta.retentionDays,
        maxFileSizeMB: effectiveMaxFileMB,
      });
      meshRef.current?.broadcastRoomMoved(nextRoom.code, password || undefined);
      window.setTimeout(() => {
        if (!mountedRef.current) return;
        signalingRef.current?.destroyRoom();
        onRemoveRecentRoom(roomCode);
        setShowInvite(false);
        setRotatingCode(false);
        onSwitchRoom(nextRoom.code, password, 'ephemeral', nextRoom.ownerToken);
        onToast(`已换上全新口令：${nextRoom.code}`);
      }, 800);
    } catch (err) {
      setRotatingCode(false);
      onToast(err instanceof Error ? err.message : '换码失败');
    }
  };

  const handleBlockPeer = (p: PeerProfile) => {
    const cid = p.clientId || p.peerId;
    const next = addBlockedPeer({
      clientId: cid,
      name: p.name,
      blockedAt: Date.now(),
    });
    setBlockedPeers(next);
    onBlockedPeersChanged?.();
    meshRef.current?.removePeer(p.peerId);
    setPeers((prev) => {
      const copy = { ...prev };
      delete copy[p.peerId];
      return copy;
    });
    onToast(`已屏蔽 ${p.name}，后续将自动拒收其消息与文件`);
  };

  const handleUnblockPeer = (clientId: string) => {
    setBlockedPeers(removeBlockedPeer(clientId));
    onBlockedPeersChanged?.();
    onToast('已解除屏蔽，对方重新加入后可正常通信');
  };

  const handleSubmitReport = async () => {
    if (!reportTarget || submittingReport) return;
    const cleanReason = reportReason.trim();
    if (!cleanReason) {
      onToast('请填写举报原因');
      return;
    }
    setSubmittingReport(true);
    try {
      await submitReport({
        roomCode,
        reportedName: reportTarget.name,
        reportedClientId: reportTarget.clientId,
        reason: cleanReason,
        messageId: reportTarget.messageId || '',
        reporterClientId: profile.clientId,
      });
      setReportTarget(null);
      onToast('已提交举报，感谢共同维护安全的传输环境');
    } catch (err) {
      onToast(err instanceof Error ? err.message : '举报提交失败');
    } finally {
      setSubmittingReport(false);
    }
  };

  const handleOpenRoomProfile = () => {
    setEditTopicInput(roomInfo?.topic || '');
    setEditPasswordInput('');
    setClearPasswordChecked(false);
    setRoomProfileOpen(true);
  };

  const handleSaveRoomProfile = async (e: React.FormEvent) => {
    e.preventDefault();
    if (savingRoomProfile) return;
    setSavingRoomProfile(true);
    try {
      const nextPwd = clearPasswordChecked
        ? ''
        : editPasswordInput.trim() !== ''
          ? editPasswordInput.trim()
          : undefined;
      const updated = await updateRoomProfile(roomCode, {
        ownerToken,
        roomPassword: password,
        password: nextPwd,
        topic: editTopicInput.trim(),
        actorName: profile.name,
      });
      setRoomInfo(updated);
      setRoomProfileOpen(false);
      onToast('房间资料已更新');
      if (nextPwd !== undefined) {
        passwordRef.current = nextPwd;
        signalingRef.current?.updatePassword(nextPwd);
        onPasswordUpdatedRef.current?.(roomCode, nextPwd, updated.mode, ownerToken);
      }
    } catch (err) {
      onToast(err instanceof Error ? err.message : '修改房间设置失败');
    } finally {
      setSavingRoomProfile(false);
    }
  };

  const handleLeave = () => {
    ambientEngine.stop();
    stopAllLocalMedia();
    signalingRef.current?.leaveRoom();
    onExitToLobby();
  };

  const handleConfirmDestroy = () => {
    setConfirmDestroyOpen(false);
    ambientEngine.stop();
    stopAllLocalMedia();
    signalingRef.current?.destroyRoom();
    onRemoveRecentRoom(roomCode);
    onExitToLobby('房间与服务器聊天缓存已彻底销毁');
  };

  const handleDragEnter = (e: React.DragEvent) => {
    e.preventDefault();
    dragDepthRef.current++;
    if (e.dataTransfer.types.includes('Files')) setDraggingOver(true);
  };
  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault();
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
    if (dragDepthRef.current === 0) setDraggingOver(false);
  };
  const handleDrop = async (e: React.DragEvent) => {
    e.preventDefault();
    dragDepthRef.current = 0;
    setDraggingOver(false);

    const { looseFiles, folders } = await extractDroppedItems(e.dataTransfer);
    if (looseFiles.length > 0) {
      void sendFiles(looseFiles);
    }
    if (folders.length > 0) {
      if ((meshRef.current?.getConnectedPeerCount() || 0) === 0) {
        onToast('拖拽文件夹直传需先等对方进入房间建立 P2P 连接');
        return;
      }
      try {
        for (const folder of folders) {
          const estBytes = computeEntriesTotalBytes(folder.entries);
          if (!checkAttachmentAllowed(estBytes, `${folder.name}.zip`)) {
            continue;
          }
          onToast(`正在将文件夹「${folder.name}」打包为 ZIP...`);
          const zipFile = await buildZipArchive(folder.entries, folder.name);
          await sendFiles([zipFile]);
        }
      } catch (err) {
        onToast(err instanceof Error ? err.message : '文件夹打包失败');
      }
    }
  };

  const peerList = Object.values(peers);
  const typingNames = Object.entries(typingPeers)
    .filter(([, v]) => v)
    .map(([id]) => peers[id]?.name)
    .filter(Boolean);

  const cacheShortLabel =
    historyMeta.retentionDays === 0 ? '阅后即焚' : `保鲜 ${historyMeta.retentionDays}天`;

  const cacheDetailText =
    historyMeta.retentionDays === 0
      ? '服务器零留存，退出即无痕'
      : historyMeta.expiresAt
        ? `最后聊天起算 · ${formatExpiryTime(historyMeta.expiresAt)}`
        : `最后聊天起保留 ${historyMeta.retentionDays} 天（最长 7 天）`;

  const inviteUrl = `${window.location.origin}/?room=${encodeURIComponent(roomCode)}`;
  const canManageRoom = !roomInfo?.hasOwner || isOwner;

  // 向外同步挂起胶囊条展示的实时状态与单条动效状态线（正在下载 / 暂停 / 失败 / 已完成）
  useEffect(() => {
    const activeTr = [...messages]
      .reverse()
      .find((m) => m.transfer?.status === 'transferring')?.transfer;
    const pausedTr = !activeTr
      ? [...messages]
          .reverse()
          .find((m) => m.transfer?.status === 'waiting_accept' || m.transfer?.status === 'offering')?.transfer
      : undefined;
    const errorTr = !activeTr && !pausedTr
      ? [...messages].reverse().find((m) => m.transfer?.status === 'error')?.transfer
      : undefined;

    if (activeTr && activeTr.fileSize > 0) {
      const pct = Math.min(100, Math.max(0, Math.round((activeTr.transferredBytes / activeTr.fileSize) * 100)));
      onStatusSummary?.(`正在下载 ${pct}%`);
      onTransferStateChange?.({ status: 'downloading', percent: pct });
    } else if (pausedTr) {
      const pct =
        pausedTr.fileSize > 0
          ? Math.min(100, Math.max(0, Math.round((pausedTr.transferredBytes / pausedTr.fileSize) * 100)))
          : 0;
      onStatusSummary?.(`下载暂停 ${pct}%`);
      onTransferStateChange?.({ status: 'paused', percent: pct });
    } else if (errorTr) {
      const pct =
        errorTr.fileSize > 0
          ? Math.min(100, Math.max(0, Math.round((errorTr.transferredBytes / errorTr.fileSize) * 100)))
          : 0;
      onStatusSummary?.(`下载失败`);
      onTransferStateChange?.({ status: 'error', percent: pct });
    } else {
      const count = Object.keys(peers).length + 1;
      onStatusSummary?.(`${linkStatusPill.text} · ${count}人`);
      onTransferStateChange?.({ status: 'idle', percent: 0 });
    }
  }, [messages, peers, linkStatusPill.text, onStatusSummary, onTransferStateChange]);

  // 组装当前所有活跃的屏幕共享画面（自己 + 远端伙伴）
  const activeScreenFeeds: ScreenFeedItem[] = useMemo(() => {
    const list: ScreenFeedItem[] = [];
    if (localScreenStream) {
      list.push({ id: selfPeerId, label: `${profile.name}（我）`, isSelf: true, stream: localScreenStream });
    }
    Object.entries(remoteScreenStreams).forEach(([pid, stream]) => {
      list.push({ id: pid, label: peers[pid]?.name || '伙伴', isSelf: false, stream });
    });
    return list;
  }, [localScreenStream, remoteScreenStreams, peers, profile.name, selfPeerId]);

  // 提取房间内最近出现的文件列表，供「放飞纸飞机」默认勾选前 5 个元数据
  const planeCandidateFiles = useMemo<PlaneFileMeta[]>(() => {
    const out: PlaneFileMeta[] = [];
    const seen = new Set<string>();
    for (let i = messages.length - 1; i >= 0; i--) {
      const m = messages[i];
      if (m.kind !== 'file' && m.kind !== 'image' && m.kind !== 'audio') continue;
      const name =
        m.transfer?.fileName ||
        m.caption ||
        (m.kind === 'image' ? '图片附件.png' : m.kind === 'audio' ? '语音消息.webm' : '');
      if (!name || seen.has(name)) continue;
      seen.add(name);
      const mime =
        m.transfer?.mimeType ||
        (m.kind === 'image' ? 'image/png' : m.kind === 'audio' ? 'audio/webm' : '');
      out.push({
        name,
        size: m.transfer?.fileSize || 0,
        kind: classifyPlaneFileKind(name, mime),
      });
      if (out.length >= 10) break;
    }
    return out;
  }, [messages]);

  useEffect(() => {
    const hasOpenOverlay =
      moreMenuOpen ||
      settingsPopoverOpen ||
      cachePopoverOpen ||
      attachmentPopoverOpen ||
      roomProfileOpen ||
      confirmClearOpen ||
      confirmDestroyOpen ||
      forwardModalOpen ||
      Boolean(reportTarget);
    if (!hasOpenOverlay) return;
    const onDocClick = () => {
      setMoreMenuOpen(false);
      setSettingsPopoverOpen(false);
      setCachePopoverOpen(false);
      setAttachmentPopoverOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setMoreMenuOpen(false);
        setSettingsPopoverOpen(false);
        setCachePopoverOpen(false);
        setAttachmentPopoverOpen(false);
        setRoomProfileOpen(false);
        setConfirmClearOpen(false);
        setConfirmDestroyOpen(false);
        setForwardModalOpen(false);
        setReportTarget(null);
      }
    };
    window.addEventListener('click', onDocClick);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('click', onDocClick);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [
    moreMenuOpen,
    settingsPopoverOpen,
    cachePopoverOpen,
    attachmentPopoverOpen,
    roomProfileOpen,
    confirmClearOpen,
    confirmDestroyOpen,
    forwardModalOpen,
    reportTarget,
  ]);

  // 跨设备共享闪贴板文本发送
  const handleSendClipboardSync = async () => {
    let text = inputText.trim();
    if (!text) {
      try {
        text = (await navigator.clipboard.readText()).trim();
      } catch {
        text = '';
      }
    }
    if (!text) {
      onToast('剪贴板或输入框为空，请先复制文本或输入内容');
      return;
    }
    const now = Date.now();
    const sent = meshRef.current?.broadcastClipboard(text, now) || 0;
    const id = `clip_self_${now}_${Math.random().toString(36).slice(2, 6)}`;
    setMessages((prev) => [
      ...prev,
      {
        id,
        kind: 'text',
        senderId: selfPeerId,
        senderName: profile.name,
        senderAvatar: profile.avatar,
        isSelf: true,
        timestamp: now,
        text,
        isClipSync: true,
      },
    ]);
    if (inputText.trim() === text) setInputText('');
    onToast(sent > 0 ? `已将剪贴板内容同步给 ${sent} 位成员` : '当前暂无在线成员，已在本机记录同步卡片');
  };

  // 切换公开随机进入开关
  const handleToggleAllowRandom = async () => {
    if (updatingAllowRandom || !roomInfo) return;
    const next = !roomInfo.allowRandom;
    setUpdatingAllowRandom(true);
    try {
      const res = await updateRoomSettingsApi(roomCode, {
        ownerToken,
        password: passwordRef.current,
        allowRandom: next,
        actorName: profile.name,
      });
      setRoomInfo((prev) =>
        prev ? { ...prev, allowRandom: typeof res.allowRandom === 'boolean' ? res.allowRandom : next } : prev
      );
      onToast(next ? '已开放免密随机加入，其他人可在广场看到此房' : '已关闭公开随机加入');
    } catch (err) {
      onToast(err instanceof Error ? err.message : '更新随机加入开关失败');
    } finally {
      setUpdatingAllowRandom(false);
    }
  };

  // @提及自动补全候选成员列表
  const mentionCandidates = useMemo(() => {
    const match = inputText.match(/(?:^|\s)@([^\s@]*)$/);
    if (!match) return [];
    const kw = match[1].toLowerCase();
    return peerList.filter((p) => p.name.toLowerCase().includes(kw)).slice(0, 6);
  }, [inputText, peerList]);

  const insertMentionName = (name: string) => {
    const next = inputText.replace(/(^|\s)@([^\s@]*)$/, `$1@${name} `);
    setInputText(next);
    window.setTimeout(() => composerInputRef.current?.focus(), 0);
  };

  const toggleSelectMessage = (msgId: string) => {
    setSelectedMsgIds((prev) => {
      const next = new Set(prev);
      if (next.has(msgId)) next.delete(msgId);
      else next.add(msgId);
      return next;
    });
  };

  const handleDeleteSelectedMessages = () => {
    if (selectedMsgIds.size === 0) return;
    const count = selectedMsgIds.size;
    setMessages((prev) => prev.filter((m) => !selectedMsgIds.has(m.id)));
    setSelectedMsgIds(new Set());
    setSelectionMode(false);
    onToast(`已从当前会话视图移除 ${count} 条本地消息记录`);
  };

  const handleForwardToRoom = (targetCode: string, targetPwd: string, targetMode: RoomMode) => {
    const pickedTexts = messages
      .filter((m) => selectedMsgIds.has(m.id) && (m.text || m.caption || m.transfer?.fileName))
      .map((m) => `${m.senderName}: ${m.text || m.caption || `[文件] ${m.transfer?.fileName}`}`);
    setForwardModalOpen(false);
    setSelectedMsgIds(new Set());
    setSelectionMode(false);
    if (pickedTexts.length === 0) {
      onToast('所选消息无可转发的文本摘要');
      return;
    }
    const merged = pickedTexts.join('\n').slice(0, 2000);
    void copyTextToClipboard(merged);
    onSwitchRoom(targetCode, targetPwd, targetMode, undefined);
    onToast(`已复制 ${pickedTexts.length} 条消息摘要并切换至 #${targetCode}，按 Ctrl+V 即可发送`);
  };

  return (
    <div
      className="chat-shell"
      onDragEnter={handleDragEnter}
      onDragOver={(e) => e.preventDefault()}
      onDragLeave={handleDragLeave}
      onDrop={(e) => void handleDrop(e)}
    >
      <div
        className={`mobile-backdrop ${sidebarOpen ? 'open' : ''}`}
        onClick={() => setSidebarOpen(false)}
      />

      {/* 顶部导航胶囊栏 */}
      <header className="top-glass-nav chat-top-nav">
        <div className="nav-left-group">
          <button
            type="button"
            className="btn btn-glass btn-icon mobile-only nav-menu-btn"
            onClick={() => setSidebarOpen((v) => !v)}
            title="打开房间面板与更多设置"
            aria-label="打开房间面板与更多设置"
          >
            <PanelLeft size={16} aria-hidden="true" />
            {peerList.length > 0 && <span className="nav-peer-badge">{peerList.length + 1}</span>}
          </button>

          <button
            type="button"
            className="btn btn-glass btn-sm mono nav-room-btn"
            onClick={async () => {
              if (await copyTextToClipboard(roomCode)) {
                onToast(`已复制房间口令 ${roomCode}`);
              }
            }}
            title="点击复制房间口令"
          >
            {roomInfo?.mode === 'fixed' ? (
              <Pin size={13} style={{ color: 'var(--accent)' }} aria-hidden="true" />
            ) : (
              <Flame size={13} style={{ color: 'var(--accent)' }} aria-hidden="true" />
            )}
            <span>{roomCode}</span>
            <span className={`status-dot ${linkStatusPill.dot} mobile-only`} />
            <Copy size={12} className="hide-mobile" style={{ color: 'var(--text-muted)' }} aria-hidden="true" />
          </button>

          <span className={`pill ${linkStatusPill.cls} hide-mobile`}>
            <span className={`status-dot ${linkStatusPill.dot}`} />
            {linkStatusPill.text}
          </span>
        </div>

        <div className="nav-right-group">
          {/* 仅当连麦或投屏处于活跃状态时保留实时快捷控制，常态收纳进「更多」 */}
          {micActive && (
            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
              <button
                type="button"
                className={`btn btn-sm ${micMuted ? 'btn-glass' : 'mic-live-btn'}`}
                onClick={handleToggleMicMute}
                title={micMuted ? '点击取消静音' : '连麦中，点击静音'}
                aria-label={micMuted ? '取消麦克风静音' : '静音麦克风'}
              >
                {micMuted ? <MicOff size={13} aria-hidden="true" /> : <Headphones size={13} aria-hidden="true" />}
                <span className="hide-mobile">{micMuted ? '已静音' : '连麦中'}</span>
              </button>
              <button
                type="button"
                className="btn btn-danger-soft btn-icon"
                style={{ width: 31, height: 31, borderRadius: 10 }}
                onClick={() => void handleToggleVoiceCall()}
                title="挂断语音连麦"
                aria-label="挂断语音连麦"
              >
                <PhoneOff size={13} aria-hidden="true" />
              </button>
            </div>
          )}

          {localScreenStream && (
            <button
              type="button"
              className="btn btn-sm mic-live-btn hide-mobile"
              onClick={() => void handleToggleScreenShare()}
              title="点击停止实时屏幕共享"
            >
              <MonitorUp size={13} style={{ color: 'var(--on-accent)' }} aria-hidden="true" />
              <span className="hide-mobile">投屏中</span>
            </button>
          )}

          <button
            type="button"
            className={`btn btn-sm ${showSearchBar ? 'btn-jelly' : 'btn-glass'}`}
            onClick={() => setShowSearchBar((v) => !v)}
            title="搜索或按图片/文件/链接筛选记录 (Ctrl+F)"
            aria-label="查找记录"
          >
            <Search size={13} aria-hidden="true" />
            <span className="hide-mobile">查找</span>
          </button>

          <button
            type="button"
            className="btn btn-glass btn-sm"
            onClick={() => setShowInvite(true)}
            title="邀请二维码与链接"
            aria-label="邀请成员"
          >
            <QrCode size={14} aria-hidden="true" />
            <span className="hide-mobile">邀请</span>
          </button>

          <button
            type="button"
            className={`btn btn-sm p2p-monitor-toggle ${monitorOpen ? 'btn-jelly' : 'btn-glass'}`}
            onClick={() => setMonitorOpen((v) => !v)}
            title={monitorOpen ? '收起右侧 P2P 监控面板' : '展开右侧 P2P 监控面板'}
            aria-label="切换 P2P 监控面板"
          >
            <Activity size={13} aria-hidden="true" />
            <span>监控</span>
          </button>

          <button
            type="button"
            className="btn btn-glass btn-sm"
            onClick={onMinimizeRoom}
            title="最小化挂起房间回大厅（保持 P2P 直连与文件传输不断线）"
            aria-label="最小化挂起房间"
          >
            <Minimize2 size={13} aria-hidden="true" />
            <span className="hide-mobile">挂起</span>
          </button>

          <button
            type="button"
            className="btn btn-glass btn-sm"
            onClick={handleLeave}
            title="离开并断开房间"
            aria-label="离开房间"
          >
            <LogOut size={14} aria-hidden="true" />
            <span className="hide-mobile">离开</span>
          </button>

          {/* 「更多」下拉菜单：收纳放飞纸飞机、连麦、投屏、白板、附件上限、保鲜期、销毁房间 */}
          <div className="popover-anchor" onClick={(e) => e.stopPropagation()}>
            <button
              type="button"
              className={`btn btn-sm ${moreMenuOpen ? 'btn-jelly' : 'btn-glass'}`}
              onClick={() => {
                setMoreMenuOpen((v) => !v);
                setAttachmentPopoverOpen(false);
                setCachePopoverOpen(false);
              }}
              aria-expanded={moreMenuOpen}
              aria-label="更多房间功能"
              title="更多房间功能"
            >
              <MoreHorizontal size={15} aria-hidden="true" />
            </button>

            {moreMenuOpen && (
              <div className="floating-popover more-menu-popover" role="menu">
                <button
                  type="button"
                  className="more-menu-item"
                  onClick={() => {
                    setMoreMenuOpen(false);
                    setPlaneComposerOpen(true);
                  }}
                >
                  <span className="more-menu-item-left">
                    <Send size={14} style={{ color: 'var(--accent)' }} aria-hidden="true" />
                    <span>放飞纸飞机（分享房间文件）</span>
                  </span>
                </button>

                <button
                  type="button"
                  className="more-menu-item"
                  onClick={() => {
                    setMoreMenuOpen(false);
                    void handleToggleVoiceCall();
                  }}
                >
                  <span className="more-menu-item-left">
                    <Headphones size={14} style={{ color: 'var(--accent)' }} aria-hidden="true" />
                    <span>{micActive ? '挂断语音连麦' : '实时语音连麦'}</span>
                  </span>
                </button>

                <button
                  type="button"
                  className="more-menu-item"
                  onClick={() => {
                    setMoreMenuOpen(false);
                    void handleToggleScreenShare();
                  }}
                >
                  <span className="more-menu-item-left">
                    <MonitorUp size={14} style={{ color: 'var(--accent)' }} aria-hidden="true" />
                    <span>{localScreenStream ? '停止屏幕共享' : '屏幕共享投屏'}</span>
                  </span>
                </button>

                <button
                  type="button"
                  className="more-menu-item"
                  onClick={() => {
                    setMoreMenuOpen(false);
                    handleOpenWhiteboard();
                  }}
                >
                  <span className="more-menu-item-left">
                    <Palette size={14} style={{ color: 'var(--accent)' }} aria-hidden="true" />
                    <span>多人协作白板</span>
                  </span>
                </button>

                <div className="more-menu-divider" />

                <button
                  type="button"
                  className="more-menu-item"
                  onClick={() => {
                    setMoreMenuOpen(false);
                    setCachePopoverOpen(false);
                    setAttachmentPopoverOpen(true);
                  }}
                >
                  <span className="more-menu-item-left">
                    <SlidersHorizontal size={14} style={{ color: 'var(--accent)' }} aria-hidden="true" />
                    <span>单附件上限</span>
                  </span>
                  <span className="mono more-menu-meta">
                    {effectiveMaxFileMB === 0 ? '不限' : formatLimitMB(effectiveMaxFileMB)}
                  </span>
                </button>

                <button
                  type="button"
                  className="more-menu-item"
                  onClick={() => {
                    setMoreMenuOpen(false);
                    setAttachmentPopoverOpen(false);
                    setCachePopoverOpen(true);
                  }}
                >
                  <span className="more-menu-item-left">
                    <Clock size={14} style={{ color: 'var(--accent)' }} aria-hidden="true" />
                    <span>服务器保鲜期</span>
                  </span>
                  <span className="more-menu-meta">{cacheShortLabel}</span>
                </button>

                {onToggleMuteRoom && (
                  <button
                    type="button"
                    className="more-menu-item"
                    onClick={() => {
                      setMoreMenuOpen(false);
                      onToggleMuteRoom(roomCode);
                    }}
                  >
                    <span className="more-menu-item-left">
                      {isMutedRoom ? (
                        <BellOff size={14} style={{ color: 'var(--warning)' }} aria-hidden="true" />
                      ) : (
                        <Bell size={14} style={{ color: 'var(--accent)' }} aria-hidden="true" />
                      )}
                      <span>{isMutedRoom ? '取消本房免打扰' : '本房消息免打扰'}</span>
                    </span>
                    <span className="more-menu-meta">{isMutedRoom ? '已静音' : '正常提醒'}</span>
                  </button>
                )}

                <button
                  type="button"
                  className="more-menu-item"
                  onClick={() => {
                    setMoreMenuOpen(false);
                    setSelectionMode((v) => !v);
                    setSelectedMsgIds(new Set());
                  }}
                >
                  <span className="more-menu-item-left">
                    <CheckSquare size={14} style={{ color: 'var(--accent)' }} aria-hidden="true" />
                    <span>{selectionMode ? '退出多选模式' : '多选与转发消息'}</span>
                  </span>
                </button>

                {canManageRoom && (
                  <button
                    type="button"
                    className="more-menu-item"
                    onClick={() => {
                      setMoreMenuOpen(false);
                      handleOpenRoomProfile();
                    }}
                  >
                    <span className="more-menu-item-left">
                      <Settings2 size={14} style={{ color: 'var(--accent)' }} aria-hidden="true" />
                      <span>修改密码与主题</span>
                    </span>
                  </button>
                )}

                {canManageRoom && !roomInfo?.hasPassword && (
                  <button
                    type="button"
                    className="more-menu-item"
                    disabled={updatingAllowRandom}
                    onClick={() => {
                      setMoreMenuOpen(false);
                      void handleToggleAllowRandom();
                    }}
                  >
                    <span className="more-menu-item-left">
                      <Globe size={14} style={{ color: 'var(--accent)' }} aria-hidden="true" />
                      <span>公开随机加入</span>
                    </span>
                    <span className="more-menu-meta">{roomInfo?.allowRandom ? '已开放' : '已关闭'}</span>
                  </button>
                )}

                {canManageRoom && (
                  <>
                    <div className="more-menu-divider" />
                    <button
                      type="button"
                      className="more-menu-item danger"
                      onClick={() => {
                        setMoreMenuOpen(false);
                        setConfirmDestroyOpen(true);
                      }}
                    >
                      <span className="more-menu-item-left">
                        <Trash2 size={14} aria-hidden="true" />
                        <span>销毁当前房间</span>
                      </span>
                    </button>
                  </>
                )}
              </div>
            )}

            {attachmentPopoverOpen && (
              <div className="floating-popover" style={{ width: 344 }} onClick={(e) => e.stopPropagation()}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span style={{ fontWeight: 700, fontSize: 'var(--fs-callout)' }}>附件大小与传输调节</span>
                  <button
                    type="button"
                    className="btn btn-ghost btn-icon"
                    style={{ width: 28, height: 28 }}
                    onClick={() => setAttachmentPopoverOpen(false)}
                    aria-label="关闭附件设置"
                    title="关闭"
                  >
                    <X size={14} aria-hidden="true" />
                  </button>
                </div>

                {canManageRoom && (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 'var(--fs-footnote)', fontWeight: 700, color: 'var(--text-secondary)' }}>
                      <span>房间单附件上限（全房同步）</span>
                      <span className="mono" style={{ color: 'var(--accent)' }}>
                        {formatLimitMB(effectiveMaxFileMB)}
                      </span>
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 6 }}>
                      {ROOM_ATTACHMENT_PRESETS.filter((p) => {
                        const cap = serverConfig.maxAttachmentMB || 0;
                        if (cap <= 0) return true;
                        return p.mb > 0 && p.mb <= cap;
                      }).map((p) => (
                        <button
                          key={p.mb}
                          type="button"
                          className={`btn btn-sm ${effectiveMaxFileMB === p.mb ? 'btn-jelly' : 'btn-glass'}`}
                          onClick={() => handleApplyRoomMaxFileMB(p.mb)}
                        >
                          {p.label}
                        </button>
                      ))}
                    </div>
                    <div style={{ display: 'flex', gap: 6, marginTop: 2 }}>
                      <input
                        className="field-input mono"
                        type="number"
                        min={0}
                        max={serverConfig.maxAttachmentMB > 0 ? serverConfig.maxAttachmentMB : 102400}
                        style={{ height: 32, fontSize: 12.5, borderRadius: 10, padding: '0 10px' }}
                        value={customLimitMBInput}
                        onChange={(e) => setCustomLimitMBInput(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' && customLimitMBInput.trim() !== '') {
                            e.preventDefault();
                            handleApplyRoomMaxFileMB(Number(customLimitMBInput));
                          }
                        }}
                        placeholder="自定义 MB（填 0 = 不限制）"
                      />
                      <button
                        type="button"
                        className="btn btn-soft btn-sm"
                        style={{ height: 32 }}
                        disabled={customLimitMBInput.trim() === ''}
                        onClick={() => handleApplyRoomMaxFileMB(Number(customLimitMBInput))}
                      >
                        应用
                      </button>
                    </div>
                    {serverConfig.maxAttachmentMB > 0 && (
                      <div style={{ fontSize: 'var(--fs-caption)', color: 'var(--text-muted)' }}>
                        服务端配置硬上限：{formatLimitMB(serverConfig.maxAttachmentMB)}
                      </div>
                    )}
                  </div>
                )}

                <div className="popover-divider" style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  <Switch
                    checked={prefs.autoAcceptSmallFiles}
                    onChange={(v) => onUpdatePrefs({ ...prefs, autoAcceptSmallFiles: v })}
                    label={`自动免确认接收 ≤${prefs.autoAcceptMaxMB}MB`}
                  />
                  {prefs.autoAcceptSmallFiles && (
                    <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                      {AUTO_ACCEPT_PRESETS.map((mb) => (
                        <button
                          key={mb}
                          type="button"
                          className={`btn btn-sm ${prefs.autoAcceptMaxMB === mb ? 'btn-jelly' : 'btn-glass'}`}
                          style={{ height: 25, padding: '0 8px', fontSize: 11.5 }}
                          onClick={() => onUpdatePrefs({ ...prefs, autoAcceptMaxMB: mb })}
                        >
                          {mb}MB
                        </button>
                      ))}
                    </div>
                  )}
                </div>

                <div className="popover-divider" style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  <span style={{ fontSize: 'var(--fs-footnote)', fontWeight: 700, color: 'var(--text-secondary)' }}>
                    图片默认发送模式
                  </span>
                  <div className="segmented full">
                    <button
                      type="button"
                      className={`seg-item ${prefs.imageSendMode === 'original' ? 'active' : ''}`}
                      style={{ height: 28, fontSize: 12 }}
                      onClick={() => onUpdatePrefs({ ...prefs, imageSendMode: 'original' })}
                    >
                      原图无损
                    </button>
                    <button
                      type="button"
                      className={`seg-item ${prefs.imageSendMode === 'compressed' ? 'active' : ''}`}
                      style={{ height: 28, fontSize: 12 }}
                      onClick={() => onUpdatePrefs({ ...prefs, imageSendMode: 'compressed' })}
                    >
                      智能压缩省流
                    </button>
                  </div>
                  <div style={{ fontSize: 'var(--fs-caption)', color: 'var(--text-muted)' }}>
                    服务端图片/语音缓存单文件上限：{formatBytes(serverConfig.historyMaxMediaBytes)}（超限自动瘦身缓存，P2P 原图直传不受影响）
                  </div>
                </div>
              </div>
            )}

            {cachePopoverOpen && (
              <div className="floating-popover" onClick={(e) => e.stopPropagation()}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span style={{ fontWeight: 700, fontSize: 'var(--fs-callout)' }}>服务器缓存保鲜期</span>
                  <button
                    type="button"
                    className="btn btn-ghost btn-icon"
                    style={{ width: 28, height: 28 }}
                    onClick={() => setCachePopoverOpen(false)}
                    aria-label="关闭缓存设置"
                    title="关闭"
                  >
                    <X size={14} aria-hidden="true" />
                  </button>
                </div>
                <div style={{ fontSize: 'var(--fs-footnote)', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
                  {cacheDetailText}
                </div>
                {canManageRoom && (
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 6 }}>
                    {RETENTION_STEPS.map((st) => {
                      const active = historyMeta.retentionDays === st.days;
                      return (
                        <button
                          key={st.days}
                          type="button"
                          className={`btn btn-sm ${
                            active
                              ? st.days === 0
                                ? 'btn-danger'
                                : 'btn-jelly'
                              : 'btn-glass'
                          }`}
                          onClick={() => {
                            setCachePopoverOpen(false);
                            signalingRef.current?.updateRetention(st.days);
                          }}
                        >
                          {st.label}
                        </button>
                      );
                    })}
                  </div>
                )}
                <button
                  type="button"
                  className="btn btn-glass btn-sm"
                  style={{ width: '100%' }}
                  disabled={messages.length === 0}
                  onClick={() => {
                    setCachePopoverOpen(false);
                    handleExportChatLog();
                  }}
                >
                  <FileDown size={13} aria-hidden="true" /> 导出当前聊天记录 (.txt)
                </button>
                {canManageRoom && (
                  <>
                    <button
                      type="button"
                      className="btn btn-soft btn-sm"
                      style={{ width: '100%' }}
                      onClick={() => {
                        setCachePopoverOpen(false);
                        handleOpenRoomProfile();
                      }}
                    >
                      <Settings2 size={13} aria-hidden="true" /> 修改房间密码与主题
                    </button>
                    <button
                      type="button"
                      className="btn btn-danger-soft btn-sm"
                      style={{ width: '100%' }}
                      onClick={() => {
                        setCachePopoverOpen(false);
                        setConfirmClearOpen(true);
                      }}
                    >
                      <Trash2 size={13} aria-hidden="true" /> 立即清空服务器聊天记录
                    </button>
                  </>
                )}
              </div>
            )}
          </div>
        </div>
      </header>

      {/* 主体双栏工作台 */}
      <div className="chat-layout">
        <aside className={`chat-side-bento ${sidebarOpen ? 'open' : ''}`}>
          {/* 移动端专属顶部房间快捷面板 */}
          <div className="mobile-drawer-tools">
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 6 }}>
              <span className={`pill ${linkStatusPill.cls}`} style={{ height: 24, fontSize: 11.5 }}>
                <span className={`status-dot ${linkStatusPill.dot}`} />
                {linkStatusPill.text}
              </span>
              <button
                type="button"
                className="btn btn-ghost btn-icon"
                style={{ width: 32, height: 32 }}
                onClick={() => setSidebarOpen(false)}
                aria-label="关闭侧边面板"
                title="关闭面板"
              >
                <X size={15} aria-hidden="true" />
              </button>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6 }}>
              <button
                type="button"
                className="btn btn-glass btn-sm"
                onClick={() => {
                  setSidebarOpen(false);
                  setShowSearchBar((v) => !v);
                }}
              >
                <Search size={12} aria-hidden="true" /> 查找记录
              </button>
              <button
                type="button"
                className="btn btn-glass btn-sm"
                onClick={() => {
                  setSidebarOpen(false);
                  setAttachmentPopoverOpen(false);
                  setCachePopoverOpen(true);
                }}
              >
                <Clock size={12} aria-hidden="true" /> {cacheShortLabel}
              </button>
              <button
                type="button"
                className="btn btn-soft btn-sm"
                style={{ gridColumn: '1 / -1' }}
                onClick={() => {
                  setSidebarOpen(false);
                  setPlaneComposerOpen(true);
                }}
              >
                <Send size={12} aria-hidden="true" /> 放飞纸飞机（分享房间文件）
              </button>
              {canManageRoom && roomInfo?.mode === 'ephemeral' && (
                <button
                  type="button"
                  className="btn btn-danger btn-sm"
                  style={{ gridColumn: '1 / -1' }}
                  onClick={() => {
                    setSidebarOpen(false);
                    setConfirmDestroyOpen(true);
                  }}
                >
                  <Flame size={12} aria-hidden="true" /> 销毁当前房间
                </button>
              )}
            </div>
          </div>

          {/* 秘密基地成长统计卡 */}
          {(roomInfo?.mode === 'fixed' || roomStats.totalFiles > 0 || roomStats.totalPomodoros > 0) && (
            <div className="base-stats-card">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ fontSize: 'var(--fs-footnote)', fontWeight: 700, color: 'var(--accent)', display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                  <Flame size={12} aria-hidden="true" /> 基地火花 · 连续 {Math.max(1, roomStats.streakDays)} 天
                </span>
                <span className="mono" style={{ fontSize: 'var(--fs-caption)', color: 'var(--text-muted)' }}>
                  {roomInfo?.mode === 'fixed' ? '永久养成' : '本场统计'}
                </span>
              </div>
              <div className="base-stats-grid">
                <div className="base-stat-cell" title={`累计传输 ${roomStats.totalFiles} 个文件`}>
                  <div className="base-stat-val mono">{formatBytes(roomStats.totalBytes)}</div>
                  <div className="base-stat-label">{roomStats.totalFiles} 文件</div>
                </div>
                <div className="base-stat-cell" title="累计共同完成番茄专注次数">
                  <div className="base-stat-val mono">{roomStats.totalPomodoros}</div>
                  <div className="base-stat-label">番茄专注</div>
                </div>
                <div className="base-stat-cell" title="基地累计聊天互动数">
                  <div className="base-stat-val mono">{roomStats.totalMessages}</div>
                  <div className="base-stat-label">互动数</div>
                </div>
              </div>
            </div>
          )}

          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <div className="sidebar-section-head">
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                <Users size={12} aria-hidden="true" /> 房间成员 ({peerList.length + 1}/{roomInfo?.maxPeers || 6})
              </span>
              <span style={{ color: wsConnected ? 'var(--success)' : 'var(--warning)' }}>
                {wsConnected ? '在线' : '重连中'}
              </span>
            </div>

            <div
              onClick={onEditProfile}
              className="sidebar-peer-card clickable"
              role="button"
              tabIndex={0}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  onEditProfile();
                }
              }}
              title="点击更换头像与昵称"
            >
              <div className={micActive && !micMuted ? 'speaking-ring' : ''}>
                <Avatar name={profile.name} avatar={profile.avatar} size={34} />
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 'var(--fs-subhead)', fontWeight: 700, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {profile.name}（我）
                </div>
                <div style={{ fontSize: 'var(--fs-caption)', color: 'var(--text-muted)' }}>
                  {micActive ? (micMuted ? '连麦静音' : '连麦说话中') : '点击修改头像与昵称'}
                </div>
              </div>
            </div>

            {peerList.map((p) => {
              const st = linkStatuses[p.peerId];
              const ready = Boolean(st?.dataReady);
              const relay = st?.transport === 'relay-turn';
              const pMedia = peerMediaStates[p.peerId];
              const pSpeaking = Boolean(speakingPeers[p.peerId]);
              const sub = !ready
                ? '正在握手...'
                : relay
                  ? `中转${st?.rttMs !== undefined ? ` ${st.rttMs}ms` : ''}`
                  : `P2P 直连${st?.rttMs !== undefined ? ` ${st.rttMs}ms` : ''}`;

              return (
                <div key={p.peerId} className="sidebar-peer-card">
                  <div className={pSpeaking ? 'speaking-ring' : ''}>
                    <Avatar name={p.name} avatar={p.avatar} size={34} />
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                      <span style={{ fontSize: 'var(--fs-subhead)', fontWeight: 700, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {p.name}
                      </span>
                      {pMedia?.micOn && (
                        <span title={pMedia.micMuted ? '已静音' : '语音连麦中'} style={{ display: 'inline-flex', color: pMedia.micMuted ? 'var(--text-muted)' : 'var(--success)' }}>
                          {pMedia.micMuted ? <MicOff size={11} aria-hidden="true" /> : <Mic size={11} aria-hidden="true" />}
                        </span>
                      )}
                      {pMedia?.screenOn && (
                        <span title="正在投屏" style={{ display: 'inline-flex', color: 'var(--accent)' }}>
                          <MonitorUp size={11} aria-hidden="true" />
                        </span>
                      )}
                    </div>
                    <div style={{ fontSize: 'var(--fs-caption)', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: 4 }}>
                      <span className={`status-dot ${!ready ? 'pulse' : relay ? 'amber' : 'emerald'}`} />
                      <span>{sub}</span>
                    </div>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                    {!ready && (
                      <button
                        type="button"
                        className="btn btn-ghost btn-icon"
                        style={{ width: 28, height: 28 }}
                        title="手动重建 P2P 连接"
                        aria-label={`重建与 ${p.name} 的 P2P 连接`}
                        onClick={() => {
                          meshRef.current?.reconnectPeer(p.peerId);
                          onToast(`正在重新尝试与 ${p.name} 打洞直连...`);
                        }}
                      >
                        <RotateCw size={13} aria-hidden="true" />
                      </button>
                    )}
                    <button
                      type="button"
                      className="btn btn-ghost btn-icon"
                      style={{ width: 28, height: 28 }}
                      title={`举报 ${p.name}`}
                      aria-label={`举报 ${p.name}`}
                      onClick={() => {
                        setReportReason('发送垃圾广告或恶意文件');
                        setReportTarget({
                          peerId: p.peerId,
                          clientId: p.clientId || p.peerId,
                          name: p.name,
                        });
                      }}
                    >
                      <Flag size={12} aria-hidden="true" />
                    </button>
                    <button
                      type="button"
                      className="btn btn-ghost btn-icon"
                      style={{ width: 28, height: 28 }}
                      title={`屏蔽 ${p.name}`}
                      aria-label={`屏蔽 ${p.name}`}
                      onClick={() => handleBlockPeer(p)}
                    >
                      <Ban size={12} aria-hidden="true" />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>

          {blockedPeers.length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div className="sidebar-section-head">
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                  <Ban size={12} aria-hidden="true" /> 已屏蔽成员 ({blockedPeers.length})
                </span>
              </div>
              {blockedPeers.map((b) => (
                <div key={b.clientId} className="sidebar-blocked-row">
                  <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {b.name}
                  </span>
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm"
                    style={{ height: 22, padding: '0 7px', fontSize: 11 }}
                    onClick={() => handleUnblockPeer(b.clientId)}
                  >
                    解除
                  </button>
                </div>
              ))}
            </div>
          )}

          {/* 陪伴自习室：全房同步番茄钟 + 零依赖合成环境白噪音 */}
          <StudyPomodoroCard
            pomodoro={pomodoro}
            displaySeconds={pomodoroDisplaySec}
            ambientSound={ambientSound}
            ambientVolume={ambientVolume}
            onStartToggle={handlePomodoroToggle}
            onSwitchMode={handlePomodoroSwitchMode}
            onReset={handlePomodoroReset}
            onSelectAmbient={handleSelectAmbient}
            onChangeVolume={handleChangeAmbientVolume}
          />

          {savedFixedRooms.filter((r) => r.code !== roomCode).length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div className="sidebar-section-head">
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                  <Pin size={12} aria-hidden="true" /> 切换秘密基地
                </span>
              </div>
              {savedFixedRooms
                .filter((r) => r.code !== roomCode)
                .slice(0, 4)
                .map((r) => (
                  <button
                    key={r.code}
                    type="button"
                    className="btn btn-glass btn-sm"
                    style={{ justifyContent: 'space-between' }}
                    onClick={() => void handleSwitchSavedFixed(r)}
                  >
                    <span className="mono">#{r.code}</span>
                    {r.hasPassword ? (
                      <Lock size={12} aria-hidden="true" />
                    ) : (
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 3 }}>
                        进入 <ArrowRight size={12} aria-hidden="true" />
                      </span>
                    )}
                  </button>
                ))}
            </div>
          )}

          {recentRooms.filter(
            (r) => r.code !== roomCode && !savedFixedRooms.some((s) => s.code === r.code)
          ).length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div className="sidebar-section-head">
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                  <Clock size={12} aria-hidden="true" /> 最近房间
                </span>
              </div>
              {recentRooms
                .filter(
                  (r) => r.code !== roomCode && !savedFixedRooms.some((s) => s.code === r.code)
                )
                .slice(0, 4)
                .map((r) => (
                  <button
                    key={r.code}
                    type="button"
                    className="btn btn-glass btn-sm"
                    style={{ justifyContent: 'space-between' }}
                    onClick={() => void handleSwitchSavedFixed(r)}
                  >
                    <span className="mono">#{r.code}</span>
                    <span style={{ fontSize: 'var(--fs-caption)', color: 'var(--text-muted)' }}>
                      {formatRelativeTime(r.lastVisitedAt)}
                    </span>
                  </button>
                ))}
            </div>
          )}

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

          <div className="sidebar-footer-section">
            <div className="popover-anchor" onClick={(e) => e.stopPropagation()}>
              <button
                type="button"
                className={`btn btn-sm sidebar-settings-trigger ${settingsPopoverOpen ? 'btn-jelly' : 'btn-glass'}`}
                onClick={() => setSettingsPopoverOpen((v) => !v)}
                aria-expanded={settingsPopoverOpen}
              >
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                  <Settings2 size={14} aria-hidden="true" />
                  <span>外观与设置</span>
                </span>
                <span className="mono" style={{ opacity: 0.75 }}>
                  {formatLimitMB(effectiveMaxFileMB)}
                </span>
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
                  placement="top-left"
                  extraSection={
                    <button
                      type="button"
                      className="btn btn-glass btn-sm"
                      style={{ width: '100%', justifyContent: 'space-between' }}
                      onClick={() => {
                        setSettingsPopoverOpen(false);
                        setSidebarOpen(false);
                        setAttachmentPopoverOpen(true);
                      }}
                    >
                      <span>调整单附件上限</span>
                      <span className="mono" style={{ color: 'var(--accent)', fontWeight: 700 }}>
                        {formatLimitMB(effectiveMaxFileMB)}
                      </span>
                    </button>
                  }
                />
              )}
            </div>

            {canManageRoom && roomInfo?.mode === 'fixed' && (
              <button
                type="button"
                className="btn btn-danger-soft btn-sm"
                style={{ marginTop: 2 }}
                onClick={() => setConfirmDestroyOpen(true)}
              >
                <Trash2 size={13} aria-hidden="true" /> 彻底删除此常驻基地
              </button>
            )}
          </div>
        </aside>

        {/* 右侧主聊天互动舱 */}
        <section className="chat-center-bento">
          {/* 实时屏幕共享高清舞台（有人投屏时自动展开，支持全屏与折叠） */}
          {activeScreenFeeds.length > 0 && (
            <ScreenShareStage
              feeds={activeScreenFeeds}
              onStopLocalScreen={() => void handleToggleScreenShare()}
            />
          )}

          {/* 附近设备邀请状态轮询横幅（发起方进房后每 3 秒更新） */}
          {pendingNearbyInvite && nearbyInviteStatus && (
            <div className="wb-invite-banner">
              <span>
                {nearbyInviteStatus === 'pending' &&
                  `正在等待 ${pendingNearbyInvite.targetName} 接受邀请...`}
                {nearbyInviteStatus === 'accepted' &&
                  `${pendingNearbyInvite.targetName} 已接受邀请，正在建立连接`}
                {nearbyInviteStatus === 'declined' &&
                  `${pendingNearbyInvite.targetName} 婉拒了本次传输邀请`}
                {nearbyInviteStatus === 'expired' &&
                  `等待 ${pendingNearbyInvite.targetName} 响应已超时`}
              </span>
              <button
                type="button"
                className="btn btn-ghost btn-icon"
                style={{ width: 28, height: 28 }}
                onClick={() => onClearPendingNearbyInvite?.()}
                aria-label="关闭邀请状态提示"
                title="关闭"
              >
                <X size={13} aria-hidden="true" />
              </button>
            </div>
          )}

          {/* 协作白板召唤横幅（对方开启白板画画时提示一键加入同屏创作） */}
          {wbInviteBanner && !showWhiteboard && (
            <div className="wb-invite-banner">
              <span>{wbInviteBanner} 正在多人协作白板创作，快来一起同屏涂鸦贴便签</span>
              <div style={{ display: 'flex', gap: 6 }}>
                <button
                  type="button"
                  className="btn btn-jelly btn-sm"
                  style={{ height: 26, fontSize: 11.5 }}
                  onClick={handleOpenWhiteboard}
                >
                  加入白板
                </button>
                <button
                  type="button"
                  className="btn btn-ghost btn-icon"
                  style={{ width: 28, height: 28 }}
                  onClick={() => setWbInviteBanner(null)}
                  aria-label="关闭白板提示"
                  title="关闭"
                >
                  <X size={13} aria-hidden="true" />
                </button>
              </div>
            </div>
          )}

          {/* 查找与分类筛选条 */}
          {showSearchBar && (
            <div className="chat-filter-bar">
              <div style={{ position: 'relative', display: 'flex', alignItems: 'center', flex: 1 }}>
                <Search
                  size={13}
                  style={{ position: 'absolute', left: 10, color: 'var(--text-muted)', pointerEvents: 'none' }}
                  aria-hidden="true"
                />
                <input
                  ref={searchInputRef}
                  className="filter-search-input"
                  value={searchKeyword}
                  onChange={(e) => setSearchKeyword(e.target.value)}
                  placeholder="搜索聊天文字、文件名或发送人..."
                  autoFocus
                />
              </div>
              <div className="segmented" style={{ padding: 3 }}>
                {(
                  [
                    { id: 'all', label: '全部' },
                    { id: 'image', label: '图片' },
                    { id: 'file', label: '文件' },
                    { id: 'audio', label: '语音' },
                    { id: 'link', label: '链接' },
                  ] as const
                ).map((t) => (
                  <button
                    key={t.id}
                    type="button"
                    className={`seg-item ${filterTab === t.id ? 'active' : ''}`}
                    style={{ height: 26, fontSize: 12, padding: '0 9px' }}
                    onClick={() => setFilterTab(t.id)}
                  >
                    {t.label}
                  </button>
                ))}
              </div>
              <button
                type="button"
                className="btn btn-ghost btn-icon"
                style={{ width: 28, height: 28 }}
                onClick={() => {
                  setShowSearchBar(false);
                  setSearchKeyword('');
                  setFilterTab('all');
                }}
                aria-label="关闭查找栏"
                title="关闭"
              >
                <X size={14} aria-hidden="true" />
              </button>
            </div>
          )}

          {/* 未读 @我 快速跳转条 */}
          {unreadMentionIds.length > 0 && (
            <div className="batch-accept-bar">
              <span style={{ fontSize: 'var(--fs-subhead)', fontWeight: 700, color: 'var(--accent)' }}>
                有人 @了你（{unreadMentionIds.length} 条）
              </span>
              <button
                type="button"
                className="btn btn-jelly btn-sm"
                onClick={() => {
                  const targetId = unreadMentionIds[0];
                  setUnreadMentionIds((prev) => prev.slice(1));
                  document.getElementById(`msg-${targetId}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
                }}
              >
                定位到 @我
              </button>
            </div>
          )}

          {/* 批量待接收文件快捷条（收到 >= 2 个大文件时自动弹出） */}
          {pendingIncomingFiles.length >= 2 && (
            <div className="batch-accept-bar">
              <span style={{ fontSize: 'var(--fs-subhead)', fontWeight: 700, color: 'var(--accent)' }}>
                收到 {pendingIncomingFiles.length} 个待接收文件，可一键全部接收
              </span>
              <button
                type="button"
                className="btn btn-jelly btn-sm"
                onClick={handleAcceptAllPending}
              >
                <Download size={13} aria-hidden="true" /> 一键全部接收
              </button>
            </div>
          )}

          {/* 消息流 / 雷达空态 */}
          <div
            className="chat-scroll-area"
            onClick={() => {
              setShowEmoji(false);
              setCachePopoverOpen(false);
              setAttachmentPopoverOpen(false);
            }}
          >
            {messages.length === 0 ? (
              <div className="airdrop-radar-stage">
                <div className="radar-orbit-box">
                  {radarQrUrl && (
                    <div className="qr-crystal-frame" onClick={() => setShowInvite(true)}>
                      <img src={radarQrUrl} alt="扫码进房" />
                    </div>
                  )}
                </div>

                <div>
                  <div className="mono radar-room-code">
                    {roomCode}
                  </div>
                  <div style={{ fontSize: 'var(--fs-subhead)', color: 'var(--text-secondary)', marginTop: 6 }}>
                    {peerList.length === 0
                      ? '手机扫码或发送口令给朋友，即可开始 P2P 文件直传与实时聊天'
                      : '已连上伙伴，发消息、拖入文件或直接粘贴截图即可传输'}
                  </div>
                </div>

                <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', justifyContent: 'center' }}>
                  <button
                    type="button"
                    className="btn btn-jelly"
                    onClick={async () => {
                      if (await copyTextToClipboard(inviteUrl)) {
                        onToast('已复制直连邀请链接');
                      }
                    }}
                  >
                    <Copy size={14} aria-hidden="true" /> 复制邀请链接
                  </button>
                  <button
                    type="button"
                    className="btn btn-glass"
                    onClick={handleOpenWhiteboard}
                  >
                    <Palette size={15} aria-hidden="true" /> 多人协作白板
                  </button>
                </div>
              </div>
            ) : visibleMessages.length === 0 ? (
              <div style={{ margin: 'auto', textAlign: 'center', color: 'var(--text-muted)', fontSize: 13.5 }}>
                没有找到符合当前筛选条件的记录
              </div>
            ) : (
              <MessageList
                items={visibleMessages}
                selfPeerId={selfPeerId}
                selfName={profile.name}
                recalledTexts={recalledTexts}
                isSelectionMode={selectionMode}
                selectedIds={selectedMsgIds}
                onToggleSelect={toggleSelectMessage}
                onEnterSelectionMode={(id) => {
                  setSelectionMode(true);
                  setSelectedMsgIds(new Set([id]));
                }}
                onFavoriteMessage={onFavoriteMessage}
                onNudgeAvatar={handleNudgePeer}
                shakePeerId={nudgeShake?.peerId}
                shakeNonce={nudgeShake?.nonce}
                onReEditRecalled={(text) => {
                  setInputText(text);
                  window.setTimeout(() => {
                    const el = composerInputRef.current;
                    if (el) {
                      el.focus();
                      el.setSelectionRange(text.length, text.length);
                    }
                  }, 0);
                }}
                onReactMessage={handleReactMessage}
                onReplyMessage={handleReplyMessage}
                onRecallMessage={handleRecallMessage}
                onAcceptFile={(peerId, tid, mode, fn, fsize, resumeKey) =>
                  void handleAcceptFile(peerId, tid, mode, fn, fsize, resumeKey)
                }
                onRejectFile={(peerId, tid) => meshRef.current?.rejectIncomingTransfer(peerId, tid)}
                onCancelTransfer={(peerId, tid) => meshRef.current?.cancelTransfer(peerId, tid)}
                onRequestResume={(peerId, tr) => void handleRequestResume(peerId, tr)}
                onPreviewImage={(src, fileName, caption) =>
                  setLightboxData({ src, fileName, caption })
                }
              />
            )}
            <div ref={messageEndRef} />
          </div>

          {typingNames.length > 0 && (
            <div style={{ maxWidth: 860, width: '100%', margin: '0 auto', padding: '0 20px 4px', fontSize: 'var(--fs-footnote)', color: 'var(--accent)', fontWeight: 600 }}>
              {typingNames.join('、')} 正在输入中...
            </div>
          )}

          {/* 多选批量操作悬浮条 */}
          {selectionMode && (
            <div className="chat-selection-bar">
              <span style={{ fontSize: 'var(--fs-subhead)', fontWeight: 700 }}>
                已选中 {selectedMsgIds.size} 条记录
              </span>
              <div style={{ display: 'flex', gap: 8 }}>
                <button
                  type="button"
                  className="btn btn-soft btn-sm"
                  disabled={selectedMsgIds.size === 0}
                  onClick={() => setForwardModalOpen(true)}
                >
                  <Forward size={13} aria-hidden="true" /> 合并转发
                </button>
                <button
                  type="button"
                  className="btn btn-danger-soft btn-sm"
                  disabled={selectedMsgIds.size === 0}
                  onClick={handleDeleteSelectedMessages}
                >
                  <Trash2 size={13} aria-hidden="true" /> 本地删除
                </button>
                <button
                  type="button"
                  className="btn btn-glass btn-sm"
                  onClick={() => {
                    setSelectionMode(false);
                    setSelectedMsgIds(new Set());
                  }}
                >
                  完成
                </button>
              </div>
            </div>
          )}

          {/* 底部输入坞 */}
          <div className="composer-zone">
            {mentionCandidates.length > 0 && (
              <div className="mention-autocomplete-bar">
                <span className="mention-ac-label">选择要 @提醒 的成员：</span>
                {mentionCandidates.map((p) => (
                  <button
                    key={p.peerId}
                    type="button"
                    className="btn btn-soft btn-sm"
                    onClick={() => insertMentionName(p.name)}
                  >
                    @{p.name}
                  </button>
                ))}
              </div>
            )}
            <div className="composer-pod">
              {replyingTo && (
                <div className="composer-reply-bar">
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0, flex: 1 }}>
                    <Reply size={13} style={{ color: 'var(--accent)', flexShrink: 0 }} aria-hidden="true" />
                    <span style={{ fontWeight: 700, fontSize: 'var(--fs-footnote)', color: 'var(--accent)', flexShrink: 0 }}>
                      回复 {replyingTo.senderName}：
                    </span>
                    <span
                      style={{
                        fontSize: 'var(--fs-footnote)',
                        color: 'var(--text-secondary)',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {replyingTo.preview}
                    </span>
                  </div>
                  <button
                    type="button"
                    className="btn btn-ghost btn-icon"
                    style={{ width: 24, height: 24 }}
                    onClick={() => setReplyingTo(null)}
                    title="取消引用"
                    aria-label="取消引用"
                  >
                    <X size={13} aria-hidden="true" />
                  </button>
                </div>
              )}

              {pendingPaste && (
                <div className="pending-paste-card">
                  <img
                    src={pendingPaste.previewUrl}
                    alt="待发送截图"
                    style={{ width: 48, height: 48, borderRadius: 10, objectFit: 'cover' }}
                  />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 'var(--fs-subhead)', fontWeight: 700 }}>
                      剪贴板图片就绪 · <span className="mono">{formatBytes(pendingPaste.file.size)}</span>
                    </div>
                    <div style={{ display: 'flex', gap: 6, marginTop: 4 }}>
                      <button
                        type="button"
                        className={`btn btn-sm ${!pendingPaste.compress ? 'btn-jelly' : 'btn-glass'}`}
                        style={{ height: 23, fontSize: 11, padding: '0 8px' }}
                        onClick={() => setPendingPaste({ ...pendingPaste, compress: false })}
                      >
                        原图直发
                      </button>
                      <button
                        type="button"
                        className={`btn btn-sm ${pendingPaste.compress ? 'btn-jelly' : 'btn-glass'}`}
                        style={{ height: 23, fontSize: 11, padding: '0 8px' }}
                        onClick={() => setPendingPaste({ ...pendingPaste, compress: true })}
                      >
                        压缩发送
                      </button>
                    </div>
                  </div>
                  <button
                    type="button"
                    className="btn btn-ghost btn-icon"
                    aria-label="取消发送剪贴板图片"
                    title="取消"
                    onClick={() => {
                      URL.revokeObjectURL(pendingPaste.previewUrl);
                      setPendingPaste(null);
                    }}
                  >
                    <X size={15} aria-hidden="true" />
                  </button>
                </div>
              )}

              <textarea
                ref={composerInputRef}
                className="composer-textarea"
                rows={1}
                value={inputText}
                onChange={(e) => {
                  setInputText(e.target.value);
                  meshRef.current?.broadcastTyping(true);
                  if (typingTimerRef.current !== null) clearTimeout(typingTimerRef.current);
                  typingTimerRef.current = window.setTimeout(() => {
                    meshRef.current?.broadcastTyping(false);
                  }, 1500);
                }}
                onPaste={handleComposerPaste}
                onKeyDown={(e) => {
                  // 中文/日文输入法组合候选阶段按下回车绝不误发消息
                  if (e.nativeEvent.isComposing || e.keyCode === 229) return;
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    if (pendingPaste) void handleSendPendingPaste();
                    else handleSendText();
                  }
                }}
                placeholder={
                  pendingPaste
                    ? '配上一句说明，按 Enter 发出图片...'
                    : '聊点什么，或直接粘贴截图、拖入文件...'
                }
              />

              {showEmoji && (
                <div className="emoji-palette">
                  {EMOJI_LIST.map((em) => (
                    <button
                      key={em}
                      type="button"
                      className="emoji-cell"
                      onClick={() => handleInsertEmoji(em)}
                      aria-label={`插入表情 ${em}`}
                    >
                      {em}
                    </button>
                  ))}
                </div>
              )}

              <div className="composer-bottom">
                <div className="composer-tools-scroll">
                  <button
                    type="button"
                    className="tool-pill"
                    onClick={() => fileInputRef.current?.click()}
                  >
                    <Paperclip size={14} aria-hidden="true" /> 文件
                  </button>
                  <button
                    type="button"
                    className="tool-pill"
                    onClick={() => imageInputRef.current?.click()}
                  >
                    <ImageIcon size={14} aria-hidden="true" /> 图片
                  </button>
                  <button
                    type="button"
                    className={`tool-pill ${recordingVoice ? 'recording-pill' : ''}`}
                    onClick={() => void handleToggleVoiceRecord()}
                    title="点击开始/停止录制语音消息（最长 60 秒）"
                  >
                    {recordingVoice ? (
                      <>
                        <Square size={12} aria-hidden="true" /> 停止 {recordSeconds}s
                      </>
                    ) : (
                      <>
                        <Mic size={14} aria-hidden="true" /> 语音
                      </>
                    )}
                  </button>
                  <button
                    type="button"
                    className="tool-pill"
                    onClick={handleOpenWhiteboard}
                    title="打开多人实时协作白板"
                  >
                    <Palette size={14} aria-hidden="true" /> 白板
                  </button>
                  <button
                    type="button"
                    className="tool-pill"
                    onClick={() => void handleSendClipboardSync()}
                    title="读取当前剪贴板或输入框内容，同步给同房设备"
                  >
                    <Clipboard size={14} aria-hidden="true" /> 剪贴板
                  </button>
                  <button
                    type="button"
                    className="tool-pill"
                    onClick={() => setShowEmoji((v) => !v)}
                    title="插入表情"
                    aria-label="插入表情"
                  >
                    <Smile size={15} aria-hidden="true" />
                  </button>
                </div>

                <button
                  type="button"
                  className="btn btn-jelly btn-sm"
                  style={{ height: 34, padding: '0 15px', borderRadius: 12, flexShrink: 0 }}
                  disabled={!inputText.trim() && !pendingPaste}
                  onClick={() => {
                    if (pendingPaste) void handleSendPendingPaste();
                    else handleSendText();
                  }}
                >
                  <span>发送</span>
                  <Send size={13} aria-hidden="true" />
                </button>
              </div>

              <button
                type="button"
                className="p2p-drop-strip"
                onClick={() => fileInputRef.current?.click()}
                title="拖拽文件或整个文件夹到聊天区即可免压缩 P2P 直传，点击也可选择本地文件"
              >
                <span className="p2p-drop-strip-left">
                  <FolderUp size={15} style={{ color: 'var(--accent)', flexShrink: 0 }} aria-hidden="true" />
                  <span className="p2p-drop-strip-title">
                    拖拽文件或文件夹至此 · 零压缩 P2P 分片直传
                  </span>
                </span>
                <span className="p2p-drop-strip-action">选文件</span>
              </button>

              <input
                ref={fileInputRef}
                type="file"
                multiple
                style={{ display: 'none' }}
                onChange={(e) => {
                  if (e.target.files) void sendFiles(e.target.files);
                  e.target.value = '';
                }}
              />
              <input
                ref={imageInputRef}
                type="file"
                accept="image/*"
                multiple
                style={{ display: 'none' }}
                onChange={(e) => {
                  if (e.target.files) void sendFiles(e.target.files);
                  e.target.value = '';
                }}
              />
            </div>
          </div>

          {draggingOver && (
            <div className="dropzone-veil">
              <UploadCloud size={46} aria-hidden="true" />
              <div style={{ fontSize: 17, fontWeight: 700 }}>
                松手即可发送文件，拖入整个文件夹将自动打包成 ZIP
              </div>
            </div>
          )}
        </section>

        {monitorOpen && (
          <P2PMonitorPanel
            roomCode={roomCode}
            wsConnected={wsConnected}
            peerCount={Object.keys(peers).length}
            directPeerCount={directPeerCount}
            transfers={roomTransfers}
            onOpenQrInvite={() => setShowInvite(true)}
            onCancelTransfer={(pid, tid) => meshRef.current?.cancelTransfer(pid, tid)}
            onRequestResume={(pid, tr) => void handleRequestResume(pid, tr)}
          />
        )}
      </div>

      {showInvite && roomInfo && (
        <InviteModal
          room={roomInfo}
          onClose={() => setShowInvite(false)}
          onRotateCode={
            canManageRoom && roomInfo.mode === 'ephemeral'
              ? () => void handleRotateEphemeralCode()
              : undefined
          }
          rotating={rotatingCode}
        />
      )}

      {/* 远端语音连麦音频播放器（自动解析声纹并驱动头像呼吸光环） */}
      {Object.entries(remoteAudioStreams).map(([pid, stream]) => (
        <RemoteAudioPlayer
          key={pid}
          peerId={pid}
          stream={stream}
          onSpeakingChange={handleSpeakingChange}
        />
      ))}

      {showWhiteboard && (
        <WhiteboardModal
          selfPeerId={selfPeerId}
          selfName={profile.name}
          onlinePeerCount={peerList.length}
          bgStyle={wbBgStyle}
          strokes={wbStrokes}
          notes={wbNotes}
          remoteCursors={wbCursors}
          onEmitEvent={handleEmitWhiteboard}
          onExportImage={(blob, caption) => {
            setShowWhiteboard(false);
            launchScreenEffect('confetti');
            void sendImageBlob(blob, {
              fileName: `whiteboard-${Date.now()}.png`,
              caption,
              compress: false,
            });
          }}
          onClose={() => setShowWhiteboard(false)}
        />
      )}

      {lightboxData && (
        <Lightbox
          src={lightboxData.src}
          fileName={lightboxData.fileName}
          caption={lightboxData.caption}
          onClose={() => setLightboxData(null)}
        />
      )}

      {confirmClearOpen && (
        <div className="modal-overlay" onClick={() => setConfirmClearOpen(false)}>
          <div className="modal-sheet" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 400 }}>
            <div className="modal-head">
              <h3>立即清空服务器聊天记录？</h3>
              <button
                type="button"
                className="btn btn-ghost btn-icon"
                onClick={() => setConfirmClearOpen(false)}
                aria-label="关闭确认弹窗"
                title="关闭"
              >
                <X size={16} aria-hidden="true" />
              </button>
            </div>
            <p style={{ margin: 0, fontSize: 13.5, color: 'var(--text-secondary)', lineHeight: 1.55 }}>
              将立即从服务器物理抹除本房间全部缓存消息与媒体（当前 {historyMeta.messageCount} 条），并同步清空所有在线伙伴画面。
            </p>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
              <button
                type="button"
                className="btn btn-glass"
                onClick={() => setConfirmClearOpen(false)}
              >
                取消
              </button>
              <button
                type="button"
                className="btn btn-danger"
                onClick={() => {
                  setConfirmClearOpen(false);
                  signalingRef.current?.clearHistory();
                }}
              >
                <Trash2 size={14} aria-hidden="true" /> 确认清空
              </button>
            </div>
          </div>
        </div>
      )}

      {confirmDestroyOpen && (
        <div className="modal-overlay" onClick={() => setConfirmDestroyOpen(false)}>
          <div className="modal-sheet" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 400 }}>
            <div className="modal-head">
              <h3>
                {roomInfo?.mode === 'ephemeral' ? '结束并销毁临时房间？' : '彻底删除常驻基地？'}
              </h3>
              <button
                type="button"
                className="btn btn-ghost btn-icon"
                onClick={() => setConfirmDestroyOpen(false)}
                aria-label="关闭确认弹窗"
                title="关闭"
              >
                <X size={16} aria-hidden="true" />
              </button>
            </div>
            <p style={{ margin: 0, fontSize: 13.5, color: 'var(--text-secondary)', lineHeight: 1.55 }}>
              {roomInfo?.mode === 'ephemeral'
                ? '确认后将断开全体连接，该 6 位口令永久作废不可再用，并同步物理清空服务器聊天缓存。'
                : '确认后将移除此固定基地号并物理清空服务器缓存聊天记录。'}
            </p>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
              <button
                type="button"
                className="btn btn-glass"
                onClick={() => setConfirmDestroyOpen(false)}
              >
                取消
              </button>
              <button type="button" className="btn btn-danger" onClick={handleConfirmDestroy}>
                <Flame size={14} aria-hidden="true" /> 确认销毁
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 违规举报弹窗 */}
      {reportTarget && (
        <div className="modal-overlay" onClick={() => setReportTarget(null)}>
          <div className="modal-sheet" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 420 }}>
            <div className="modal-head">
              <h3>举报成员：{reportTarget.name}</h3>
              <button
                type="button"
                className="btn btn-ghost btn-icon"
                onClick={() => setReportTarget(null)}
                aria-label="关闭举报弹窗"
                title="关闭"
              >
                <X size={16} aria-hidden="true" />
              </button>
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
              {['发送垃圾广告或恶意文件', '骚扰辱骂或不当言论', '传播违法违规内容'].map((preset) => (
                <button
                  key={preset}
                  type="button"
                  className={`btn btn-sm ${reportReason === preset ? 'btn-jelly' : 'btn-glass'}`}
                  onClick={() => setReportReason(preset)}
                >
                  {preset}
                </button>
              ))}
            </div>
            <textarea
              className="field-input"
              rows={3}
              style={{ padding: '10px 12px', height: 'auto', resize: 'vertical' }}
              value={reportReason}
              onChange={(e) => setReportReason(e.target.value)}
              placeholder="请补充举报原因..."
            />
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
              <button
                type="button"
                className="btn btn-glass"
                onClick={() => setReportTarget(null)}
              >
                取消
              </button>
              <button
                type="button"
                className="btn btn-danger"
                disabled={submittingReport}
                onClick={() => void handleSubmitReport()}
              >
                {submittingReport ? '提交中...' : '提交举报'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 房主修改房间密码与主题弹窗 */}
      {roomProfileOpen && (
        <div className="modal-overlay" onClick={() => setRoomProfileOpen(false)}>
          <div className="modal-sheet" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 420 }}>
            <div className="modal-head">
              <h3>修改房间密码与主题</h3>
              <button
                type="button"
                className="btn btn-ghost btn-icon"
                onClick={() => setRoomProfileOpen(false)}
                aria-label="关闭房间设置弹窗"
                title="关闭"
              >
                <X size={16} aria-hidden="true" />
              </button>
            </div>
            <form onSubmit={(e) => void handleSaveRoomProfile(e)} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                <span style={{ fontSize: 'var(--fs-footnote)', fontWeight: 700, color: 'var(--text-secondary)' }}>
                  房间主题标签（最多 24 字）
                </span>
                <input
                  className="field-input"
                  value={editTopicInput}
                  onChange={(e) => setEditTopicInput(e.target.value)}
                  maxLength={24}
                  placeholder="例如：项目文件互传"
                />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                <span style={{ fontSize: 'var(--fs-footnote)', fontWeight: 700, color: 'var(--text-secondary)' }}>
                  新访问密码（留空则保持原密码不变）
                </span>
                <input
                  className="field-input"
                  type="password"
                  disabled={clearPasswordChecked}
                  value={editPasswordInput}
                  onChange={(e) => setEditPasswordInput(e.target.value)}
                  placeholder="输入新密码（设置后将自动关闭随机串门）"
                />
              </div>
              <Switch
                checked={clearPasswordChecked}
                onChange={setClearPasswordChecked}
                label="移除房间访问密码"
                description="开启后房间将变为免密状态"
              />
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
                <button
                  type="button"
                  className="btn btn-glass"
                  onClick={() => setRoomProfileOpen(false)}
                >
                  取消
                </button>
                <button type="submit" className="btn btn-jelly" disabled={savingRoomProfile}>
                  {savingRoomProfile ? '保存中...' : '保存设置'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* 合并转发目标房间选择弹窗 */}
      {forwardModalOpen && (
        <div className="modal-overlay" onClick={() => setForwardModalOpen(false)}>
          <div className="modal-sheet" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 420 }}>
            <div className="modal-head">
              <h3>转发选中消息至房间</h3>
              <button
                type="button"
                className="btn btn-ghost btn-icon"
                onClick={() => setForwardModalOpen(false)}
                aria-label="关闭转发弹窗"
              >
                <X size={16} aria-hidden="true" />
              </button>
            </div>
            <p style={{ margin: 0, fontSize: 13, color: 'var(--text-secondary)' }}>
              将已选中的 {selectedMsgIds.size} 条文本摘要复制到剪贴板，并跳转进目标房间：
            </p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, maxHeight: 280, overflowY: 'auto' }}>
              {[
                ...savedFixedRooms.map((r) => ({ code: r.code, password: r.password || '', mode: 'fixed' as RoomMode, label: r.label })),
                ...recentRooms
                  .filter((r) => !savedFixedRooms.some((s) => s.code === r.code))
                  .map((r) => ({ code: r.code, password: r.password || '', mode: r.mode, label: undefined })),
              ]
                .filter((r) => r.code !== roomCode)
                .map((r) => (
                  <button
                    key={r.code}
                    type="button"
                    className="btn btn-glass"
                    style={{ justifyContent: 'space-between' }}
                    onClick={() => handleForwardToRoom(r.code, r.password, r.mode)}
                  >
                    <span className="mono">{r.label ? `${r.label} (#${r.code})` : `#${r.code}`}</span>
                    <ArrowRight size={14} aria-hidden="true" />
                  </button>
                ))}
              {savedFixedRooms.filter((r) => r.code !== roomCode).length === 0 &&
                recentRooms.filter((r) => r.code !== roomCode).length === 0 && (
                  <div style={{ padding: 14, textAlign: 'center', fontSize: 13, color: 'var(--text-muted)' }}>
                    暂无其他可用最近/常驻房间
                  </div>
                )}
            </div>
          </div>
        </div>
      )}

      {planeComposerOpen && (
        <PlaneComposerModal
          roomCode={roomCode}
          hasPassword={Boolean(roomInfo?.hasPassword || password)}
          isOwner={canManageRoom}
          ownerToken={ownerToken}
          roomPassword={password}
          profile={profile}
          recentFiles={planeCandidateFiles}
          onClose={() => setPlaneComposerOpen(false)}
          onSuccess={() => {
            setPlaneComposerOpen(false);
            onToast('纸飞机已放飞到大厅广场，24 小时内有效');
          }}
          onToast={onToast}
        />
      )}
    </div>
  );
};
