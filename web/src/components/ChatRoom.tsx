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
  ChevronDown,
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
} from 'lucide-react';
import QRCode from 'qrcode';
import type {
  CachedMessage,
  ChatItem,
  HistoryMeta,
  PeerLinkStatus,
  PeerMediaState,
  PeerProfile,
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
  UserProfile,
  WhiteboardBgStyle,
  WhiteboardEvent,
  WhiteboardNote,
  WhiteboardStroke,
} from '../types';
import {
  ApiError,
  buildCachedMediaUrl,
  createRoom,
  fetchRoomInfo,
  SignalingClient,
  uploadCachedMedia,
} from '../lib/signaling';
import { WebRTCMeshManager } from '../lib/webrtc';
import { compressChatImage } from '../lib/avatar';
import {
  copyTextToClipboard,
  formatBytes,
  formatExpiryTime,
  formatRelativeTime,
  openDiskResumeStream,
  openDiskWriteStream,
  playNoticeTone,
  supportsScreenCapture,
  triggerBlobDownload,
} from '../lib/platform';
import { decodeSecretText, launchScreenEffect } from '../lib/effects';
import { ambientEngine, playPomodoroChime, type AmbientSoundType } from '../lib/ambient';
import {
  buildZipArchive,
  computeEntriesTotalBytes,
  extractDroppedItems,
} from '../lib/zip';
import { getTabPeerId, type UserPrefs } from '../lib/storage';
import { Avatar } from './Avatar';
import { PalettePicker, Switch } from './Controls';
import { MessageList } from './MessageList';
import { InviteModal } from './InviteModal';
import { WhiteboardModal, type RemoteCursorState } from './WhiteboardModal';
import { StudyPomodoroCard } from './StudyPomodoroCard';
import { RemoteAudioPlayer, ScreenShareStage, type ScreenFeedItem } from './MediaCallBar';
import { Lightbox } from './Lightbox';

interface ChatRoomProps {
  roomCode: string;
  password: string;
  profile: UserProfile;
  prefs: UserPrefs;
  theme: ThemeMode;
  serverConfig: ServerConfig;
  savedFixedRooms: SavedFixedRoom[];
  recentRooms: RecentRoom[];
  onSelectTheme: (theme: ThemeMode) => void;
  onUpdatePrefs: (next: UserPrefs) => void;
  onEditProfile: () => void;
  onSwitchRoom: (newCode: string, password: string, mode?: RoomMode) => void;
  onRemoveRecentRoom: (code: string) => void;
  onMinimizeRoom: () => void;
  onStatusSummary?: (summary: string) => void;
  onExitToLobby: (notice?: string) => void;
  onToast: (msg: string) => void;
}

type MessageFilterTab = 'all' | 'image' | 'file' | 'audio';

const DEFAULT_DOC_TITLE = 'BayChat · 跨公网 P2P 闪传对讲屋';
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
const QUICK_EMOJIS = [
  '😀', '😄', '😂', '🥰', '😎', '🤩', '👍', '👏',
  '🙏', '🎉', '🔥', '🚀', '✨', '💖', '🐱', '🌈',
];
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
  profile,
  prefs,
  theme,
  serverConfig,
  savedFixedRooms,
  recentRooms,
  onSelectTheme,
  onUpdatePrefs,
  onEditProfile,
  onSwitchRoom,
  onRemoveRecentRoom,
  onMinimizeRoom,
  onStatusSummary,
  onExitToLobby,
  onToast,
}) => {
  // 按浏览器标签页生成独立 peerId（存在 sessionStorage），彻底避免同电脑双开标签页互踢重连循环
  const [selfPeerId] = useState<string>(() => getTabPeerId());

  const [roomInfo, setRoomInfo] = useState<RoomSummary | null>(null);
  const [historyMeta, setHistoryMeta] = useState<HistoryMeta>({
    retentionDays: 3,
    messageCount: 0,
  });
  const [wsConnected, setWsConnected] = useState(false);
  const [peers, setPeers] = useState<Record<string, PeerProfile>>({});
  const [linkStatuses, setLinkStatuses] = useState<Record<string, PeerLinkStatus>>({});
  const [typingPeers, setTypingPeers] = useState<Record<string, boolean>>({});
  const [messages, setMessages] = useState<ChatItem[]>([]);

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
  const [cachePopoverOpen, setCachePopoverOpen] = useState(false);
  const [attachmentPopoverOpen, setAttachmentPopoverOpen] = useState(false);
  const [customLimitMBInput, setCustomLimitMBInput] = useState('');
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [showInvite, setShowInvite] = useState(false);
  const [showWhiteboard, setShowWhiteboard] = useState(false);

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
  const fileInputRef = useRef<HTMLInputElement>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const typingTimerRef = useRef<number | null>(null);
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
          `⚠️「${fileName}」（${formatBytes(sizeBytes)}）超出当前附件上限 ${formatLimitMB(limitMB)}，可点顶部「附件上限」随时调大`
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

  // 后台标签页未读消息角标提醒
  const bumpUnreadIfHidden = useCallback(() => {
    if (typeof document !== 'undefined' && document.hidden) {
      unreadCountRef.current += 1;
      document.title = `(${unreadCountRef.current}) 新消息 · BayChat`;
    }
  }, []);

  useEffect(() => {
    const onVisibility = () => {
      if (!document.hidden) {
        unreadCountRef.current = 0;
        document.title = DEFAULT_DOC_TITLE;
      }
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      document.title = DEFAULT_DOC_TITLE;
    };
  }, []);

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
            ? '🍅 太棒了！全房共同完成一轮番茄专注，基地成长 +1🍅 ✨'
            : '☕ 小憩时间结束，准备进入下一轮高效专注吧！'
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
          const url = buildCachedMediaUrl(roomCode, cm.mediaId, password);
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
          const url = buildCachedMediaUrl(roomCode, cm.mediaId, password);
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
          return [
            ...prev,
            {
              id: cm.id,
              kind: 'system',
              senderId: cm.senderId,
              senderName: cm.senderName,
              isSelf: false,
              timestamp: cm.timestamp,
              text: `📦 ${cm.senderName} 闪传过文件「${cm.fileName || '文件'}」（${formatBytes(cm.fileSize || 0)}）`,
              fromCache: markFromCache,
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
    [roomCode, password, selfPeerId]
  );

  // 本地移除被撤回的消息并插入系统提示（严格校验消息发送者归属，防止伪造撤回他人消息）
  const applyMessageRecalled = useCallback((msgId: string, actorPeerId?: string, actorName?: string) => {
    let removedAny = false;
    setMessages((prev) => {
      const target = prev.find((m) => m.id === msgId);
      if (!target) return prev;
      if (actorPeerId && target.senderId !== actorPeerId) return prev;
      removedAny = true;
      const filtered = prev.filter((m) => m.id !== msgId);
      return [
        ...filtered,
        {
          id: `recalled_${msgId}`,
          kind: 'system',
          senderId: 'system',
          senderName: '系统',
          isSelf: false,
          timestamp: Date.now(),
          text: `↩ ${actorName || target.senderName || '成员'} 撤回了一条消息`,
        },
      ];
    });
    if (removedAny) {
      setReplyingTo((cur) => (cur?.id === msgId ? null : cur));
    }
  }, []);

  // 初始化 WebSocket 信令与 WebRTC Mesh 管理器（严禁把 serverConfig 放入依赖，避免配置加载后断线清屏）
  useEffect(() => {
    mountedRef.current = true;
    setMessages([]);
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

    const selfPeerProfile: PeerProfile = {
      peerId: selfPeerId,
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
        bumpUnreadIfHidden();
        if (prefsRef.current.soundEnabled) playNoticeTone();
      },
      onRecallMessage: (peerId, msgId) => {
        const sender = peersRef.current[peerId];
        applyMessageRecalled(msgId, peerId, sender?.name);
      },
      onTyping: (peerId, isTyping) => {
        setTypingPeers((prev) => ({ ...prev, [peerId]: isTyping }));
      },
      onReaction: (peerId, msgId, emoji) => {
        toggleMessageReaction(msgId, emoji, peerId);
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
          onToast(`🖥️ ${sender?.name || '伙伴'} 开启了实时屏幕共享`);
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
        const sender = peersRef.current[peerId];
        const kind = info.isImage ? 'image' : info.isAudio ? 'audio' : 'file';
        const itemId = info.isImage || info.isAudio ? msgId : `tr_${info.transferId}`;
        const limitMB = effectiveMaxFileMBRef.current;
        if (limitMB > 0 && info.fileSize > limitMB * 1024 * 1024) {
          onToast(
            `⚠️ 收到超大附件「${info.fileName}」（${formatBytes(info.fileSize)}），超过本房上限 ${formatLimitMB(limitMB)}，请手动确认接收或婉拒`
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
        bumpUnreadIfHidden();
        if (prefsRef.current.soundEnabled) playNoticeTone();
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
          if (updated.direction === 'send') {
            const baseId = updated.transferId.split('_').slice(0, 3).join('_');
            if (!recordedTransferIdsRef.current.has(baseId)) {
              recordedTransferIdsRef.current.add(baseId);
              signalingRef.current?.recordActivity('file', updated.fileSize);
            }
          }
          onToast(
            updated.verified
              ? `✅ ${updated.fileName} 传输完成且 SHA-256 校验通过`
              : `📦 ${updated.fileName} 传输完成`
          );
        }
      },
      shouldAutoAccept: (info) => {
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

    const sig = new SignalingClient(roomCode, password, selfPeerProfile, {
      onConnectionState: (connected) => setWsConnected(connected),
      onJoined: (env) => {
        if (env.room) {
          setRoomInfo(env.room);
          if (env.room.stats) setRoomStats(env.room.stats);
        }
        if (env.history) setHistoryMeta(env.history);

        const peerMap: Record<string, PeerProfile> = {};
        const activeIds: string[] = [];
        (env.peers || []).forEach((p) => {
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
        setPeers((prev) => ({ ...prev, [p.peerId]: p }));
        // 成员新加入或重连时强制重建干净的 PeerConnection，防止僵尸连接卡死
        mesh.ensurePeer(p.peerId, true);
        appendSystemMessage(`✨ ${p.name} 加入了房间`);
      },
      onPeerUpdated: (p) => {
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
        if (leftPeer) appendSystemMessage(`👋 ${leftPeer.name} 离开了房间`);
      },
      onSignal: (env) => void mesh.handleSignal(env),
      onMessageCached: (env) => {
        if (env.history) setHistoryMeta(env.history);
        if (env.message && env.message.senderId !== selfPeerId) {
          mergeCachedMessage(env.message, false);
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
          appendSystemMessage(`⏱ ${env.actorName || '伙伴'} 将保鲜期设为：${desc}`);
        }
      },
      onRoomSettingsUpdated: (env) => {
        if (typeof env.maxFileSizeMB === 'number') {
          const nextMB = env.maxFileSizeMB;
          setRoomInfo((prev) => (prev ? { ...prev, maxFileSizeMB: nextMB } : prev));
          appendSystemMessage(
            `📦 ${env.actorName || '伙伴'} 将单附件大小上限调整为：${formatLimitMB(nextMB)}`
          );
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
        setMessages([
          {
            id: `cleared_${Date.now()}`,
            kind: 'system',
            senderId: 'system',
            senderName: '系统',
            isSelf: false,
            timestamp: Date.now(),
            text: `🧹 ${env.actorName || '伙伴'} 已一键清空服务器缓存记录`,
          },
        ]);
        onToast('房间服务器聊天缓存已秒清 ✨');
      },
      onRoomDestroyed: (reason) => {
        mesh.destroyAll();
        revokeAllTrackedUrls();
        onExitToLobby(reason);
      },
      onError: (code, msg) => {
        if (FATAL_WS_ERRORS.has(code)) {
          mesh.destroyAll();
          revokeAllTrackedUrls();
          onExitToLobby(msg);
        } else {
          onToast(msg);
        }
      },
    });
    signalingRef.current = sig;
    sig.connect();

    return () => {
      mountedRef.current = false;
      voiceCancelledRef.current = true;
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
    password,
    selfPeerId,
    appendSystemMessage,
    applyMessageRecalled,
    applyWhiteboardEvent,
    bumpUnreadIfHidden,
    mergeCachedMessage,
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
      appendSystemMessage('🎧 你已退出语音连麦');
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
      appendSystemMessage('🎧 你开启了实时语音连麦，房间伙伴可直接听到你说话');
    } catch {
      onToast('无法开启麦克风，请检查浏览器权限或 HTTPS 环境');
    }
  };

  const handleToggleMicMute = () => {
    const next = !micMuted;
    setMicMuted(next);
    meshRef.current?.setLocalMicMuted(next);
    onToast(next ? '🔇 麦克风已静音' : '🎙️ 已恢复开麦');
  };

  // 一键实时屏幕共享（WebRTC 动态挂载视频轨，监听原生「停止共享」按钮）
  const handleToggleScreenShare = async () => {
    if (localScreenStreamRef.current) {
      localScreenStreamRef.current.getTracks().forEach((t) => t.stop());
      localScreenStreamRef.current = null;
      setLocalScreenStream(null);
      meshRef.current?.setLocalScreenStream(null);
      appendSystemMessage('🖥️ 你已停止实时屏幕共享');
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
            appendSystemMessage('🖥️ 实时屏幕共享已结束');
          }
        };
      }
      localScreenStreamRef.current = stream;
      setLocalScreenStream(stream);
      meshRef.current?.setLocalScreenStream(stream);
      appendSystemMessage('🖥️ 你开启了实时屏幕共享，房间伙伴正在顶部舞台同步观看');
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
      appendSystemMessage(`🍅 ${profile.name} 启动了全房同步番茄钟（${Math.ceil(secs / 60)} 分钟）`);
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
          ? '🕵️ [防窥密信]'
          : (item.text || '').slice(0, 60)
        : item.kind === 'image'
          ? `🖼️ [图片] ${item.caption || item.transfer?.fileName || ''}`
          : item.kind === 'audio'
            ? `🎙️ [语音 ${item.caption || ''}]`
            : `📦 [文件] ${item.transfer?.fileName || ''}`;
    setReplyingTo({
      id: item.id,
      senderName: item.senderName,
      preview: preview.trim(),
    });
  };

  const handleRecallMessage = (msgId: string) => {
    applyMessageRecalled(msgId, selfPeerId, profile.name);
    meshRef.current?.broadcastRecall(msgId);
    signalingRef.current?.recallMessage(msgId);
    onToast('↩ 已撤回该消息');
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
    onToast('💾 已导出聊天记录到本地');
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
      meshRef.current?.sendFileToAll(blob, {
        transferIdPrefix: idPrefix,
        fileName: options.fileName,
        mimeType: blob.type || 'image/png',
        isImage: true,
        isScreenshot: options.isScreenshot,
        caption: options.caption,
        timestamp,
      }) || [];

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
    const fileName = `voice-${Date.now()}.webm`;
    if (!checkAttachmentAllowed(blob.size, fileName)) {
      return;
    }

    const timestamp = Date.now();
    const idPrefix = `aud_${timestamp}_${Math.random().toString(36).slice(2, 7)}`;
    const localUrl = trackObjectUrl(URL.createObjectURL(blob));
    const caption = `${Math.max(1, durationSec)}″`;

    const transfers =
      meshRef.current?.sendFileToAll(blob, {
        transferIdPrefix: idPrefix,
        fileName,
        mimeType: blob.type || 'audio/webm',
        isImage: false,
        isAudio: true,
        caption,
        timestamp,
      }) || [];

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
    for (const file of files) {
      if (!checkAttachmentAllowed(file.size, file.name)) {
        continue;
      }
      if (file.type.startsWith('image/') && file.size <= 25 * 1024 * 1024) {
        await sendImageBlob(file, { fileName: file.name });
        continue;
      }
      if (connectedPeers === 0) {
        onToast('文件直传需等小伙伴进房建立 P2P 通道后发送哦');
        continue;
      }

      const timestamp = Date.now();
      const idPrefix = `file_${timestamp}_${Math.random().toString(36).slice(2, 7)}`;
      const transfers =
        meshRef.current?.sendFileToAll(file, {
          transferIdPrefix: idPrefix,
          fileName: file.name,
          mimeType: file.type || 'application/octet-stream',
          isImage: false,
          timestamp,
        }) || [];

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

      if (historyMetaRef.current.retentionDays > 0) {
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

  // 支持 Ctrl+V 粘贴剪贴板截图，也支持从资源管理器 Ctrl+C/V 直接粘贴任意普通文件发送
  const handleComposerPaste = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const items = e.clipboardData?.items;
    if (!items) return;
    const extraFiles: File[] = [];
    for (let i = 0; i < items.length; i++) {
      const it = items[i];
      if (it.kind !== 'file') continue;
      const file = it.getAsFile();
      if (!file) continue;
      if (it.type.startsWith('image/')) {
        e.preventDefault();
        if (pendingPaste) URL.revokeObjectURL(pendingPaste.previewUrl);
        setPendingPaste({
          file,
          previewUrl: URL.createObjectURL(file),
          fileName: file.name || `screenshot-${Date.now()}.png`,
          compress: prefsRef.current.imageSendMode === 'compressed',
        });
        return;
      }
      extraFiles.push(file);
    }
    if (extraFiles.length > 0) {
      e.preventDefault();
      void sendFiles(extraFiles);
    }
  };

  const handleAcceptFile = async (
    peerId: string,
    transferId: string,
    mode: 'memory' | 'disk' | 'resume',
    fileName: string,
    fileSize: number
  ) => {
    try {
      let diskStream = null;
      if (mode === 'disk') {
        diskStream = await openDiskWriteStream(fileName, fileSize);
        if (!diskStream) return;
      } else if (mode === 'resume') {
        diskStream = await openDiskResumeStream(fileName, fileSize);
        if (!diskStream) return;
      }
      const started = meshRef.current?.acceptIncomingTransfer(peerId, transferId, diskStream);
      if (!started) {
        if (diskStream) void diskStream.close().catch(() => {});
        onToast('⚠️ 发送方已离线或该传输已失效，请让对方重新发送文件');
      } else if (diskStream && diskStream.initialOffset > 0) {
        onToast(`⚡ 正在从 ${formatBytes(diskStream.initialOffset)} 断点续传「${fileName}」`);
      }
    } catch (err) {
      onToast(err instanceof Error ? err.message : '无法打开保存位置');
    }
  };

  const handleAcceptAllPending = () => {
    pendingIncomingFiles.forEach((m) => {
      if (m.transfer?.targetPeerId) {
        meshRef.current?.acceptIncomingTransfer(m.transfer.targetPeerId, m.transfer.transferId, null);
      }
    });
    onToast(`🚀 已开始批量接收 ${pendingIncomingFiles.length} 个文件`);
  };

  // 切换常用固定房间或最近房间前先探测是否仍存在及是否需要密码
  const handleSwitchSavedFixed = async (r: { code: string; password?: string; mode?: RoomMode }) => {
    setSidebarOpen(false);
    try {
      const info = await fetchRoomInfo(r.code);
      if (info.hasPassword && !r.password) {
        signalingRef.current?.leaveRoom();
        onExitToLobby(`房间 #${r.code} 设有密码，请在大厅输入密码进入`);
        return;
      }
      onSwitchRoom(r.code, r.password || '', info.mode);
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

  const handleRotateEphemeralCode = async () => {
    if (rotatingCode) return;
    setRotatingCode(true);
    try {
      const nextRoom = await createRoom({
        mode: 'ephemeral',
        password: password || undefined,
        maxPeers: roomInfo?.maxPeers || serverConfig.maxRoomPeers,
        retentionDays: historyMeta.retentionDays,
        maxFileSizeMB: effectiveMaxFileMB,
      });
      signalingRef.current?.destroyRoom();
      onRemoveRecentRoom(roomCode);
      setShowInvite(false);
      onSwitchRoom(nextRoom.code, password, 'ephemeral');
      onToast(`🎲 已换上全新口令：${nextRoom.code}`);
    } catch (err) {
      onToast(err instanceof Error ? err.message : '换码失败');
    } finally {
      setRotatingCode(false);
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
    onExitToLobby('房间与服务器聊天缓存已彻底销毁 ✨');
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
          onToast(`📦 正在将文件夹「${folder.name}」打包为 ZIP...`);
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

  // 向外同步挂起胶囊条展示的实时状态（传输进度优先，其次展示在线连线状态）
  useEffect(() => {
    if (!onStatusSummary) return;
    const activeTr = [...messages]
      .reverse()
      .find((m) => m.transfer?.status === 'transferring')?.transfer;
    if (activeTr && activeTr.fileSize > 0) {
      const pct = Math.min(100, (activeTr.transferredBytes / activeTr.fileSize) * 100);
      onStatusSummary(`传输中 ${pct.toFixed(1)}% · ${activeTr.fileName}`);
    } else {
      const count = Object.keys(peers).length + 1;
      onStatusSummary(`${linkStatusPill.text} · ${count}人`);
    }
  }, [messages, peers, linkStatusPill.text, onStatusSummary]);

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

  return (
    <div
      className="aurora-canvas"
      onDragEnter={handleDragEnter}
      onDragOver={(e) => e.preventDefault()}
      onDragLeave={handleDragLeave}
      onDrop={(e) => void handleDrop(e)}
    >
      <div
        className={`mobile-backdrop ${sidebarOpen ? 'open' : ''}`}
        onClick={() => setSidebarOpen(false)}
      />

      {/* 顶部晶莹毛玻璃胶囊栏 */}
      <header className="top-glass-nav chat-top-nav">
        <div className="nav-left-group">
          <button
            type="button"
            className="btn btn-glass btn-icon mobile-only nav-menu-btn"
            onClick={() => setSidebarOpen((v) => !v)}
            title="打开房间面板与更多设置"
          >
            <PanelLeft size={16} />
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
              <Pin size={13} style={{ color: 'var(--accent)' }} />
            ) : (
              <Flame size={13} style={{ color: 'var(--accent)' }} />
            )}
            <span>{roomCode}</span>
            <span className={`status-dot ${linkStatusPill.dot} mobile-only`} />
            <Copy size={12} className="hide-mobile" style={{ color: 'var(--text-muted)' }} />
          </button>

          <span className={`pill ${linkStatusPill.cls} hide-mobile`}>
            <span className={`status-dot ${linkStatusPill.dot}`} />
            {linkStatusPill.text}
          </span>
        </div>

        <div className="nav-right-group">
          {/* 语音连麦 & 实时屏幕共享快捷按钮 */}
          {micActive ? (
            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
              <button
                type="button"
                className={`btn btn-sm ${micMuted ? 'btn-glass' : 'mic-live-btn'}`}
                onClick={handleToggleMicMute}
                title={micMuted ? '点击取消静音' : '连麦中，点击静音'}
              >
                {micMuted ? <MicOff size={13} /> : <Headphones size={13} />}
                <span className="hide-mobile">{micMuted ? '已静音' : '连麦中'}</span>
              </button>
              <button
                type="button"
                className="btn btn-danger-soft btn-icon"
                style={{ width: 31, height: 31, borderRadius: 10 }}
                onClick={() => void handleToggleVoiceCall()}
                title="挂断语音连麦"
              >
                <PhoneOff size={13} />
              </button>
            </div>
          ) : (
            <button
              type="button"
              className="btn btn-glass btn-sm"
              onClick={() => void handleToggleVoiceCall()}
              title="一键开启全房实时语音连麦（WebRTC 直连）"
            >
              <Headphones size={13} style={{ color: 'var(--accent)' }} />
              <span className="hide-mobile">连麦</span>
            </button>
          )}

          <button
            type="button"
            className={`btn btn-sm hide-mobile ${localScreenStream ? 'mic-live-btn' : 'btn-glass'}`}
            onClick={() => void handleToggleScreenShare()}
            title={localScreenStream ? '点击停止实时屏幕共享' : '一键开启实时屏幕共享投屏'}
          >
            <MonitorUp size={13} style={{ color: localScreenStream ? '#fff' : 'var(--accent)' }} />
            <span className="hide-mobile">{localScreenStream ? '投屏中' : '投屏'}</span>
          </button>

          <button
            type="button"
            className={`btn btn-sm hide-mobile ${showSearchBar ? 'btn-jelly' : 'btn-glass'}`}
            onClick={() => setShowSearchBar((v) => !v)}
            title="搜索或按图片/文件筛选记录 (Ctrl+F)"
          >
            <Search size={13} />
            <span className="hide-mobile">查找</span>
          </button>

          {/* 附件大小上限 & 传输设置 Popover（移动端入口收敛进侧边抽屉，弹窗自动居中展示） */}
          <div className="popover-anchor">
            <button
              type="button"
              className={`btn btn-sm hide-mobile ${attachmentPopoverOpen ? 'btn-jelly' : 'btn-glass'}`}
              onClick={() => {
                setAttachmentPopoverOpen((v) => !v);
                setCachePopoverOpen(false);
              }}
              title="动态调整房间附件大小上限、自动接收阈值与图片画质"
            >
              <SlidersHorizontal size={13} style={{ color: attachmentPopoverOpen ? '#fff' : 'var(--accent)' }} />
              <span>
                {effectiveMaxFileMB === 0 ? '附件不限' : `≤${formatLimitMB(effectiveMaxFileMB)}`}
              </span>
              <ChevronDown size={12} />
            </button>

            {attachmentPopoverOpen && (
              <div className="floating-popover" style={{ width: 344 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span style={{ fontWeight: 800, fontSize: 14 }}>📦 附件大小与传输调节</span>
                  <button
                    type="button"
                    className="btn btn-ghost btn-icon"
                    style={{ width: 26, height: 26 }}
                    onClick={() => setAttachmentPopoverOpen(false)}
                  >
                    <X size={14} />
                  </button>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, fontWeight: 700, color: 'var(--text-sub)' }}>
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
                    <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                      服务端配置硬上限：{formatLimitMB(serverConfig.maxAttachmentMB)}
                    </div>
                  )}
                </div>

                <div style={{ borderTop: '1px solid var(--line-border)', paddingTop: 10, display: 'flex', flexDirection: 'column', gap: 6 }}>
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

                <div style={{ borderTop: '1px solid var(--line-border)', paddingTop: 10, display: 'flex', flexDirection: 'column', gap: 6 }}>
                  <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-sub)' }}>
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
                  <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                    服务端图片/语音缓存单文件上限：{formatBytes(serverConfig.historyMaxMediaBytes)}（超限自动瘦身缓存，P2P 原图直传不受影响）
                  </div>
                </div>
              </div>
            )}
          </div>

          <div className="popover-anchor">
            <button
              type="button"
              className="btn btn-glass btn-sm hide-mobile"
              onClick={() => {
                setCachePopoverOpen((v) => !v);
                setAttachmentPopoverOpen(false);
              }}
            >
              <Clock size={13} style={{ color: 'var(--accent)' }} />
              <span>{cacheShortLabel}</span>
              {historyMeta.messageCount > 0 && (
                <span className="mono" style={{ opacity: 0.7 }}>
                  · {historyMeta.messageCount}
                </span>
              )}
              <ChevronDown size={12} />
            </button>

            {cachePopoverOpen && (
              <div className="floating-popover">
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span style={{ fontWeight: 800, fontSize: 14 }}>⏱ 服务器缓存保鲜期</span>
                  <button
                    type="button"
                    className="btn btn-ghost btn-icon"
                    style={{ width: 26, height: 26 }}
                    onClick={() => setCachePopoverOpen(false)}
                  >
                    <X size={14} />
                  </button>
                </div>
                <div style={{ fontSize: 12, color: 'var(--text-sub)', lineHeight: 1.5 }}>
                  {cacheDetailText}
                </div>
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
                  <FileDown size={13} /> 导出当前聊天记录 (.txt)
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
                  <Trash2 size={13} /> 立即清空服务器聊天记录
                </button>
              </div>
            )}
          </div>

          <button
            type="button"
            className="btn btn-glass btn-sm"
            onClick={() => setShowInvite(true)}
            title="邀请二维码与链接"
          >
            <QrCode size={14} />
            <span className="hide-mobile">邀请</span>
          </button>

          {roomInfo?.mode === 'ephemeral' && (
            <button
              type="button"
              className="btn btn-danger btn-sm hide-mobile"
              onClick={() => setConfirmDestroyOpen(true)}
              title="结束对话并立即销毁此房间口令"
            >
              <Flame size={13} />
              <span className="hide-mobile">销毁</span>
            </button>
          )}

          <button
            type="button"
            className="btn btn-glass btn-sm"
            onClick={onMinimizeRoom}
            title="最小化挂起房间回大厅（保持 P2P 直连与文件传输不断线）"
          >
            <Minimize2 size={13} />
            <span className="hide-mobile">挂起</span>
          </button>

          <button type="button" className="btn btn-glass btn-sm" onClick={handleLeave} title="离开并断开房间">
            <LogOut size={14} />
            <span className="hide-mobile">离开</span>
          </button>
        </div>
      </header>

      {/* 主体双栏毛玻璃 Bento */}
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
                style={{ width: 28, height: 28 }}
                onClick={() => setSidebarOpen(false)}
              >
                <X size={15} />
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
                <Search size={12} /> 查找记录
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
                <Clock size={12} /> {cacheShortLabel}
              </button>
              {roomInfo?.mode === 'ephemeral' && (
                <button
                  type="button"
                  className="btn btn-danger btn-sm"
                  style={{ gridColumn: '1 / -1' }}
                  onClick={() => {
                    setSidebarOpen(false);
                    setConfirmDestroyOpen(true);
                  }}
                >
                  <Flame size={12} /> 销毁当前房间
                </button>
              )}
            </div>
          </div>

          {/* 秘密基地成长统计卡（常驻房或产生真实传输/专注记录时展示，临时空房保持清爽） */}
          {(roomInfo?.mode === 'fixed' || roomStats.totalFiles > 0 || roomStats.totalPomodoros > 0) && (
            <div className="base-stats-card">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ fontSize: 12, fontWeight: 800, color: 'var(--accent)' }}>
                  🔥 基地火花 · 连续 {Math.max(1, roomStats.streakDays)} 天
                </span>
                <span className="mono" style={{ fontSize: 10.5, color: 'var(--text-muted)' }}>
                  {roomInfo?.mode === 'fixed' ? '永久养成' : '本场统计'}
                </span>
              </div>
              <div className="base-stats-grid">
                <div className="base-stat-cell" title={`累计闪传 ${roomStats.totalFiles} 个文件`}>
                  <div className="base-stat-val mono">{formatBytes(roomStats.totalBytes)}</div>
                  <div className="base-stat-label">📦 {roomStats.totalFiles} 文件</div>
                </div>
                <div className="base-stat-cell" title="累计共同完成番茄专注次数">
                  <div className="base-stat-val mono">{roomStats.totalPomodoros}</div>
                  <div className="base-stat-label">🍅 番茄专注</div>
                </div>
                <div className="base-stat-cell" title="基地累计聊天互动数">
                  <div className="base-stat-val mono">{roomStats.totalMessages}</div>
                  <div className="base-stat-label">💬 互动数</div>
                </div>
              </div>
            </div>
          )}

          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, fontWeight: 800, color: 'var(--text-muted)' }}>
              <span>👥 房间成员 ({peerList.length + 1}/{roomInfo?.maxPeers || 6})</span>
              <span style={{ color: wsConnected ? '#10b981' : '#f59e0b' }}>
                {wsConnected ? '● 在线' : '● 重连'}
              </span>
            </div>

            <div
              onClick={onEditProfile}
              style={{
                padding: '9px 11px',
                borderRadius: 16,
                background: 'var(--glass-bg-strong)',
                border: '1px solid var(--line-border)',
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                cursor: 'pointer',
              }}
              title="点击更换头像与昵称"
            >
              <div className={micActive && !micMuted ? 'speaking-ring' : ''}>
                <Avatar name={profile.name} avatar={profile.avatar} size={34} />
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 13.5, fontWeight: 800, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {profile.name}（我）
                </div>
                <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                  {micActive ? (micMuted ? '🔇 连麦静音' : '🎙️ 连麦说话中') : '点击修改头像与昵称'}
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
                <div
                  key={p.peerId}
                  style={{
                    padding: '9px 11px',
                    borderRadius: 16,
                    background: 'var(--glass-bg-strong)',
                    border: '1px solid var(--line-border)',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                  }}
                >
                  <div className={pSpeaking ? 'speaking-ring' : ''}>
                    <Avatar name={p.name} avatar={p.avatar} size={34} />
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                      <span style={{ fontSize: 13.5, fontWeight: 700, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {p.name}
                      </span>
                      {pMedia?.micOn && (
                        <span title={pMedia.micMuted ? '已静音' : '语音连麦中'} style={{ fontSize: 11 }}>
                          {pMedia.micMuted ? '🔇' : '🎙️'}
                        </span>
                      )}
                      {pMedia?.screenOn && (
                        <span title="正在投屏" style={{ fontSize: 11 }}>
                          🖥️
                        </span>
                      )}
                    </div>
                    <div style={{ fontSize: 11, color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: 4 }}>
                      <span className={`status-dot ${!ready ? 'pulse' : relay ? 'amber' : 'emerald'}`} />
                      <span>{sub}</span>
                    </div>
                  </div>
                  {!ready && (
                    <button
                      type="button"
                      className="btn btn-ghost btn-icon"
                      style={{ width: 26, height: 26 }}
                      title="手动重建 P2P 连接"
                      onClick={() => {
                        meshRef.current?.reconnectPeer(p.peerId);
                        onToast(`🔄 正在重新尝试与 ${p.name} 打洞直连...`);
                      }}
                    >
                      <RotateCw size={13} />
                    </button>
                  )}
                </div>
              );
            })}
          </div>

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
              <div style={{ fontSize: 12, fontWeight: 800, color: 'var(--text-muted)' }}>
                📌 切换秘密基地
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
                    {r.hasPassword ? <Lock size={12} /> : <span>空降 →</span>}
                  </button>
                ))}
            </div>
          )}

          {recentRooms.filter(
            (r) => r.code !== roomCode && !savedFixedRooms.some((s) => s.code === r.code)
          ).length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div style={{ fontSize: 12, fontWeight: 800, color: 'var(--text-muted)' }}>
                🕒 最近房间
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
                    <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                      {formatRelativeTime(r.lastVisitedAt)}
                    </span>
                  </button>
                ))}
            </div>
          )}

          <div style={{ marginTop: 'auto', display: 'flex', flexDirection: 'column', gap: 8, paddingTop: 10, borderTop: '1px solid var(--line-border)' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
              <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text-sub)' }}>主题色</span>
              <PalettePicker theme={theme} onSelect={onSelectTheme} />
            </div>
            <Switch
              checked={prefs.autoAcceptSmallFiles}
              onChange={(v) => onUpdatePrefs({ ...prefs, autoAcceptSmallFiles: v })}
              label={`自动接收 ≤${prefs.autoAcceptMaxMB}MB 文件`}
            />
            <Switch
              checked={prefs.soundEnabled}
              onChange={(v) => onUpdatePrefs({ ...prefs, soundEnabled: v })}
              label="清脆提示音效"
            />
            <button
              type="button"
              className="btn btn-glass btn-sm"
              style={{ justifyContent: 'space-between' }}
              onClick={() => {
                setSidebarOpen(false);
                setAttachmentPopoverOpen(true);
              }}
            >
              <span>📦 附件上限</span>
              <span className="mono" style={{ color: 'var(--accent)', fontWeight: 700 }}>
                {formatLimitMB(effectiveMaxFileMB)}
              </span>
            </button>
            {roomInfo?.mode === 'fixed' && (
              <button
                type="button"
                className="btn btn-danger-soft btn-sm"
                style={{ marginTop: 2 }}
                onClick={() => setConfirmDestroyOpen(true)}
              >
                <Trash2 size={13} /> 彻底删除此常驻基地
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

          {/* 协作白板召唤横幅（对方开启白板画画时提示一键加入同屏创作） */}
          {wbInviteBanner && !showWhiteboard && (
            <div className="wb-invite-banner">
              <span>🎨 {wbInviteBanner} 正在多人协作白板创作，快来一起同屏涂鸦贴便签！</span>
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
                  style={{ width: 26, height: 26 }}
                  onClick={() => setWbInviteBanner(null)}
                >
                  <X size={13} />
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
                    { id: 'image', label: '🖼️ 图片' },
                    { id: 'file', label: '📦 文件' },
                    { id: 'audio', label: '🎙️ 语音' },
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
                style={{ width: 26, height: 26 }}
                onClick={() => {
                  setShowSearchBar(false);
                  setSearchKeyword('');
                  setFilterTab('all');
                }}
              >
                <X size={14} />
              </button>
            </div>
          )}

          {/* 批量待接收文件快捷条（收到 >= 2 个大文件时自动弹出） */}
          {pendingIncomingFiles.length >= 2 && (
            <div className="batch-accept-bar">
              <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--accent)' }}>
                📦 收到 {pendingIncomingFiles.length} 个待接收文件，可一键全部接收
              </span>
              <button
                type="button"
                className="btn btn-jelly btn-sm"
                onClick={handleAcceptAllPending}
              >
                <Download size={13} /> 一键全部接收
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
                  <div
                    className="mono"
                    style={{
                      fontSize: 30,
                      fontWeight: 900,
                      letterSpacing: '0.14em',
                      paddingLeft: '0.14em',
                      background: 'var(--grad-primary)',
                      WebkitBackgroundClip: 'text',
                      WebkitTextFillColor: 'transparent',
                    }}
                  >
                    {roomCode}
                  </div>
                  <div style={{ fontSize: 13, color: 'var(--text-sub)', marginTop: 6 }}>
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
                        onToast('已复制直连邀请链接 🚀');
                      }
                    }}
                  >
                    <Copy size={14} /> 复制邀请链接
                  </button>
                  <button
                    type="button"
                    className="btn btn-glass"
                    onClick={handleOpenWhiteboard}
                  >
                    <Palette size={15} /> 多人协作白板
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
                onReactMessage={handleReactMessage}
                onReplyMessage={handleReplyMessage}
                onRecallMessage={handleRecallMessage}
                onAcceptFile={(peerId, tid, mode, fn, fsize) =>
                  void handleAcceptFile(peerId, tid, mode, fn, fsize)
                }
                onRejectFile={(peerId, tid) => meshRef.current?.rejectIncomingTransfer(peerId, tid)}
                onCancelTransfer={(peerId, tid) => meshRef.current?.cancelTransfer(peerId, tid)}
                onPreviewImage={(src, fileName, caption) =>
                  setLightboxData({ src, fileName, caption })
                }
              />
            )}
            <div ref={messageEndRef} />
          </div>

          {typingNames.length > 0 && (
            <div style={{ maxWidth: 860, width: '100%', margin: '0 auto', padding: '0 20px 4px', fontSize: 12, color: 'var(--accent)', fontWeight: 600 }}>
              💬 {typingNames.join('、')} 正在输入中...
            </div>
          )}

          {/* 底部清爽输入坞 */}
          <div className="composer-zone">
            <div className="composer-pod">
              {replyingTo && (
                <div className="composer-reply-bar">
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0, flex: 1 }}>
                    <Reply size={13} style={{ color: 'var(--accent)', flexShrink: 0 }} />
                    <span style={{ fontWeight: 700, fontSize: 12, color: 'var(--accent)', flexShrink: 0 }}>
                      回复 {replyingTo.senderName}：
                    </span>
                    <span
                      style={{
                        fontSize: 12,
                        color: 'var(--text-sub)',
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
                    style={{ width: 22, height: 22 }}
                    onClick={() => setReplyingTo(null)}
                    title="取消引用"
                  >
                    <X size={13} />
                  </button>
                </div>
              )}

              {pendingPaste && (
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 12,
                    padding: '8px 10px',
                    borderRadius: 14,
                    background: 'var(--glass-subtle)',
                    border: '1px solid var(--line-border)',
                  }}
                >
                  <img
                    src={pendingPaste.previewUrl}
                    alt="待发送截图"
                    style={{ width: 48, height: 48, borderRadius: 10, objectFit: 'cover' }}
                  />
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 13, fontWeight: 700 }}>
                      剪贴板美图就绪 · <span className="mono">{formatBytes(pendingPaste.file.size)}</span>
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
                        轻巧压缩
                      </button>
                    </div>
                  </div>
                  <button
                    type="button"
                    className="btn btn-ghost btn-icon"
                    onClick={() => {
                      URL.revokeObjectURL(pendingPaste.previewUrl);
                      setPendingPaste(null);
                    }}
                  >
                    <X size={15} />
                  </button>
                </div>
              )}

              <textarea
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
                  {QUICK_EMOJIS.map((em) => (
                    <button
                      key={em}
                      type="button"
                      className="emoji-cell"
                      onClick={() => {
                        setInputText((prev) => prev + em);
                        setShowEmoji(false);
                      }}
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
                    <Paperclip size={14} /> 文件
                  </button>
                  <button
                    type="button"
                    className="tool-pill"
                    onClick={() => imageInputRef.current?.click()}
                  >
                    <ImageIcon size={14} /> 图片
                  </button>
                  <button
                    type="button"
                    className={`tool-pill ${recordingVoice ? 'recording-pill' : ''}`}
                    onClick={() => void handleToggleVoiceRecord()}
                    title="点击开始/停止录制语音消息（最长 60 秒）"
                  >
                    {recordingVoice ? (
                      <>
                        <Square size={12} /> 停止 {recordSeconds}s
                      </>
                    ) : (
                      <>
                        <Mic size={14} /> 语音
                      </>
                    )}
                  </button>
                  <button
                    type="button"
                    className="tool-pill"
                    onClick={handleOpenWhiteboard}
                    title="打开多人实时协作白板"
                  >
                    <Palette size={14} /> 白板
                  </button>
                  <button
                    type="button"
                    className="tool-pill"
                    onClick={() => setShowEmoji((v) => !v)}
                    title="插入表情"
                  >
                    <Smile size={15} />
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
                  <Send size={13} />
                </button>
              </div>

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
              <UploadCloud size={46} />
              <div style={{ fontSize: 17, fontWeight: 800 }}>
                松手即可发送文件，拖入整个文件夹将自动打包成 ZIP ✨
              </div>
            </div>
          )}
        </section>
      </div>

      {showInvite && roomInfo && (
        <InviteModal
          room={roomInfo}
          onClose={() => setShowInvite(false)}
          onRotateCode={
            roomInfo.mode === 'ephemeral' ? () => void handleRotateEphemeralCode() : undefined
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
              <h3>🧹 立即清空服务器聊天记录？</h3>
              <button
                type="button"
                className="btn btn-ghost btn-icon"
                onClick={() => setConfirmClearOpen(false)}
              >
                <X size={16} />
              </button>
            </div>
            <p style={{ margin: 0, fontSize: 13.5, color: 'var(--text-sub)', lineHeight: 1.55 }}>
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
                <Trash2 size={14} /> 确认秒清
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
                {roomInfo?.mode === 'ephemeral' ? '🔥 结束并销毁闪传房间？' : '⚠️ 彻底删除常驻基地？'}
              </h3>
              <button
                type="button"
                className="btn btn-ghost btn-icon"
                onClick={() => setConfirmDestroyOpen(false)}
              >
                <X size={16} />
              </button>
            </div>
            <p style={{ margin: 0, fontSize: 13.5, color: 'var(--text-sub)', lineHeight: 1.55 }}>
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
                <Flame size={14} /> 确认销毁
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
