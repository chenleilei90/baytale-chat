// 核心 WebRTC Mesh P2P 引擎
// 特性：
// 1. Perfect Negotiation（完美协商）消除双端同时建连竞态，断网自动 ICE 重连
// 2. 双 DataChannel 分离：chat 通道专管文字与信令，file 通道专管二进制分片，传大文件不卡聊天
// 3. 48KB 分片 + bufferedAmount 背压流控 + 增量 SHA-256 校验（GB 级文件低内存稳传）
// 4. 自动检测 P2P 直连（局域网/公网）vs TURN 服务器中转，实时计算 RTT 延迟

import type {
  FileTransferInfo,
  FunEffectType,
  LinkTransportType,
  PeerLinkStatus,
  PeerMediaState,
  PomodoroState,
  PropThrowType,
  ReplyQuote,
  SignalEnvelope,
  WhiteboardEvent,
} from '../types';
import { bytesToHex, IncrementalSHA256 } from './sha256';
import type { DiskWriteStream } from './platform';

const DEFAULT_CHUNK_SIZE = 48 * 1024; // 48KB，跨全平台浏览器稳定无 SCTP 超限风险
const HIGH_WATER_MARK = 1024 * 1024; // 1MB 发送缓冲区上限
const LOW_WATER_MARK = 256 * 1024; // 256KB 低水位恢复阈值
const EOF_CHUNK_INDEX = 0xffffffff; // 文件尾帧标识（附带 32 字节原始 SHA-256）

// 聊天通道 JSON 协议消息
type ChatPacket =
  | {
      kind: 'text';
      id: string;
      text: string;
      replyTo?: ReplyQuote;
      timestamp: number;
    }
  | {
      kind: 'recall';
      msgId: string;
    }
  | {
      kind: 'typing';
      isTyping: boolean;
    }
  | {
      kind: 'fx';
      fxType: FunEffectType;
    }
  | {
      kind: 'reaction';
      msgId: string;
      emoji: string;
    }
  | {
      kind: 'clip-sync';
      text: string;
      updatedAt: number;
    }
  | {
      kind: 'media-state';
      state: PeerMediaState;
    }
  | {
      kind: 'mood';
      mood: string;
    }
  | {
      kind: 'prop';
      propType: PropThrowType;
      targetPeerId: string;
      targetName: string;
    }
  | {
      kind: 'pomodoro';
      state: PomodoroState;
    }
  | {
      kind: 'wb';
      event: WhiteboardEvent;
    }
  | {
      kind: 'file-offer';
      msgId: string;
      transferId: string;
      streamId: number;
      fileName: string;
      fileSize: number;
      mimeType: string;
      isImage: boolean;
      isAudio?: boolean;
      isScreenshot?: boolean;
      caption?: string;
      timestamp: number;
    }
  | {
      kind: 'file-accept';
      transferId: string;
    }
  | {
      kind: 'file-reject';
      transferId: string;
    }
  | {
      kind: 'file-cancel';
      transferId: string;
    }
  | {
      kind: 'file-ack';
      transferId: string;
      verified: boolean;
      sha256: string;
    };

interface OutgoingTransfer {
  transferId: string;
  streamId: number;
  file: File | Blob;
  info: FileTransferInfo;
  cancelled: boolean;
  started: boolean;
}

interface IncomingTransfer {
  transferId: string;
  streamId: number;
  peerId: string;
  info: FileTransferInfo;
  caption?: string;
  timestamp: number;
  accepted: boolean;
  cancelled: boolean;
  sha: IncrementalSHA256;
  chunks: BlobPart[];
  diskStream?: DiskWriteStream | null;
  startedAt: number;
  lastProgressAt: number;
  lastProgressBytes: number;
}

interface PeerSession {
  peerId: string;
  polite: boolean;
  pc: RTCPeerConnection;
  chatChannel: RTCDataChannel | null;
  fileChannel: RTCDataChannel | null;
  makingOffer: boolean;
  ignoreOffer: boolean;
  pendingIceCandidates: RTCIceCandidateInit[]; // 在 remoteDescription 就绪前缓存 ICE 候选，显著提升跨公网打洞成功率
  fileReceiveQueue: Promise<void>; // 严格串行化二进制文件分片处理，防止直写硬盘时异步乱序或提前 close 损坏文件
  statsTimer: number | null;
  status: PeerLinkStatus;
  outgoing: Map<string, OutgoingTransfer>;
  incomingByStream: Map<number, IncomingTransfer>;
  incomingById: Map<string, IncomingTransfer>;
  micSenders: RTCRtpSender[];
  screenSenders: RTCRtpSender[];
  remoteAudioStream: MediaStream | null;
  remoteScreenStream: MediaStream | null;
}

export interface MeshCallbacks {
  sendSignal: (env: SignalEnvelope) => void;
  onLinkStatusChange: (status: PeerLinkStatus) => void;
  onTextMessage: (
    peerId: string,
    msg: { id: string; text: string; replyTo?: ReplyQuote; timestamp: number }
  ) => void;
  onRecallMessage?: (peerId: string, msgId: string) => void;
  onTyping: (peerId: string, isTyping: boolean) => void;
  onFunEffect?: (peerId: string, fxType: FunEffectType) => void;
  onReaction?: (peerId: string, msgId: string, emoji: string) => void;
  onClipboardSync?: (peerId: string, text: string, updatedAt: number) => void;
  onRemoteAudioStream?: (peerId: string, stream: MediaStream | null) => void;
  onRemoteScreenStream?: (peerId: string, stream: MediaStream | null) => void;
  onPeerMediaState?: (peerId: string, state: PeerMediaState) => void;
  onPeerMood?: (peerId: string, mood: string) => void;
  onPropThrow?: (
    peerId: string,
    propType: PropThrowType,
    targetPeerId: string,
    targetName: string
  ) => void;
  onPomodoroSync?: (peerId: string, state: PomodoroState) => void;
  onWhiteboardEvent?: (peerId: string, event: WhiteboardEvent) => void;
  onPeerDataReady?: (peerId: string) => void;
  onFileOffer: (peerId: string, info: FileTransferInfo, msgId: string, caption?: string, timestamp?: number) => void;
  onTransferUpdate: (info: FileTransferInfo) => void;
  shouldAutoAccept: (info: FileTransferInfo) => boolean;
}

export class WebRTCMeshManager {
  private selfPeerId: string;
  private iceServers: RTCIceServer[];
  private callbacks: MeshCallbacks;
  private sessions = new Map<string, PeerSession>();
  private nextStreamId = 1;
  private localMicStream: MediaStream | null = null;
  private localMicMuted = false;
  private localScreenStream: MediaStream | null = null;

  constructor(selfPeerId: string, iceServers: RTCIceServer[], callbacks: MeshCallbacks) {
    this.selfPeerId = selfPeerId;
    this.iceServers = iceServers;
    this.callbacks = callbacks;
  }

  // 动态更新最新 STUN/TURN 凭证，并对尚未打通的会话平滑热更新 ICE 配置并重启打洞（避免邀请直链竞态丢失 TURN）
  updateIceServers(iceServers: RTCIceServer[]): void {
    this.iceServers = iceServers;
    for (const session of this.sessions.values()) {
      if (session.pc.connectionState === 'closed') continue;
      try {
        if (typeof session.pc.setConfiguration === 'function') {
          session.pc.setConfiguration({
            iceServers: this.iceServers,
            iceCandidatePoolSize: 4,
          });
        }
        if (!session.status.dataReady && typeof session.pc.restartIce === 'function') {
          session.pc.restartIce();
        }
      } catch {
        // 忽略个别浏览器不支持运行时更新配置
      }
    }
  }

  // 在信令重连拿到全量在线列表后，同步清理已离房僵尸会话并建立缺失连接
  syncPeers(activePeerIds: string[]): void {
    const activeSet = new Set(activePeerIds);
    for (const existingId of Array.from(this.sessions.keys())) {
      if (!activeSet.has(existingId)) {
        this.removePeer(existingId);
      }
    }
    for (const id of activePeerIds) {
      this.ensurePeer(id);
    }
  }

  // 与指定远端成员建立或复用 PeerConnection（若旧连接已失效则自动重建）
  ensurePeer(remotePeerId: string, forceReset = false): void {
    if (!remotePeerId || remotePeerId === this.selfPeerId) {
      return;
    }
    const existing = this.sessions.get(remotePeerId);
    if (existing) {
      const dead =
        existing.pc.connectionState === 'failed' ||
        existing.pc.connectionState === 'closed' ||
        existing.pc.iceConnectionState === 'failed' ||
        existing.pc.iceConnectionState === 'closed';
      if (!forceReset && !dead) {
        return;
      }
      this.removePeer(remotePeerId);
    }

    // 通过 peerId 字典序确定礼貌端与主动端，彻底避免双端同时发 Offer 的协商冲突
    const polite = this.selfPeerId < remotePeerId;
    const pc = new RTCPeerConnection({
      iceServers: this.iceServers,
      iceCandidatePoolSize: 4,
    });

    const session: PeerSession = {
      peerId: remotePeerId,
      polite,
      pc,
      chatChannel: null,
      fileChannel: null,
      makingOffer: false,
      ignoreOffer: false,
      pendingIceCandidates: [],
      fileReceiveQueue: Promise.resolve(),
      statsTimer: null,
      status: {
        peerId: remotePeerId,
        transport: 'connecting',
        dataReady: false,
      },
      outgoing: new Map(),
      incomingByStream: new Map(),
      incomingById: new Map(),
      micSenders: [],
      screenSenders: [],
      remoteAudioStream: null,
      remoteScreenStream: null,
    };

    this.sessions.set(remotePeerId, session);
    this.callbacks.onLinkStatusChange({ ...session.status });

    // 由 Impolite 端创建 chat 与 file 双 DataChannel；Polite 端通过 ondatachannel 接收
    if (!polite) {
      const chatCh = pc.createDataChannel('chat', { ordered: true });
      const fileCh = pc.createDataChannel('file', { ordered: true });
      this.bindDataChannel(session, chatCh);
      this.bindDataChannel(session, fileCh);
    }

    // 若本地已开启语音连麦或实时投屏，自动挂载到新建立的 PeerConnection
    this.attachLocalStreamsToSession(session);

    pc.ondatachannel = (ev) => {
      this.bindDataChannel(session, ev.channel);
    };

    // 接收远端语音连麦与实时屏幕共享轨道（严格区分纯语音轨与带音频的投屏轨，避免相互覆盖）
    pc.ontrack = (ev) => {
      const track = ev.track;
      const incomingStream = ev.streams[0] || new MediaStream([track]);
      if (track.kind === 'audio' && incomingStream.getVideoTracks().length === 0) {
        session.remoteAudioStream = incomingStream;
        this.callbacks.onRemoteAudioStream?.(remotePeerId, incomingStream);
        track.onended = () => {
          if (session.remoteAudioStream === incomingStream) {
            session.remoteAudioStream = null;
            this.callbacks.onRemoteAudioStream?.(remotePeerId, null);
          }
        };
      } else if (track.kind === 'video') {
        session.remoteScreenStream = incomingStream;
        this.callbacks.onRemoteScreenStream?.(remotePeerId, incomingStream);
        const clearScreen = () => {
          if (session.remoteScreenStream === incomingStream) {
            session.remoteScreenStream = null;
            this.callbacks.onRemoteScreenStream?.(remotePeerId, null);
          }
        };
        track.onended = clearScreen;
        track.onmute = () => {
          // 短暂弱网丢包也可能触发 mute，仅在 readyState === 'ended' 时立即清理
          if (track.readyState === 'ended') clearScreen();
        };
      }
    };

    pc.onnegotiationneeded = async () => {
      try {
        session.makingOffer = true;
        await pc.setLocalDescription();
        if (pc.localDescription) {
          this.callbacks.sendSignal({
            type: 'offer',
            targetPeerId: remotePeerId,
            payload: pc.localDescription,
          });
        }
      } catch {
        // 忽略协商瞬态异常
      } finally {
        session.makingOffer = false;
      }
    };

    pc.onicecandidate = (ev) => {
      if (ev.candidate) {
        this.callbacks.sendSignal({
          type: 'ice-candidate',
          targetPeerId: remotePeerId,
          payload: ev.candidate,
        });
      }
    };

    pc.oniceconnectionstatechange = () => {
      const state = pc.iceConnectionState;
      if (state === 'failed') {
        // 自动触发 ICE Restart 恢复跨公网切换 Wi-Fi/4G 后的连接
        if (typeof pc.restartIce === 'function') {
          pc.restartIce();
        }
      } else if (state === 'disconnected' || state === 'closed') {
        session.status.transport = 'disconnected';
        session.status.dataReady = false;
        this.callbacks.onLinkStatusChange({ ...session.status });
      } else if (state === 'connected' || state === 'completed') {
        void this.probeLinkStats(session);
      }
    };

    // 定期探测当前连接走的是 P2P 直连还是 TURN 服务器中转，以及 RTT 延迟
    session.statsTimer = window.setInterval(() => {
      void this.probeLinkStats(session);
    }, 3000);
  }

  // 处理来自信令服务器的 WebRTC offer / answer / ice-candidate
  async handleSignal(env: SignalEnvelope): Promise<void> {
    const fromId = env.fromPeerId;
    if (!fromId || fromId === this.selfPeerId) return;

    this.ensurePeer(fromId);
    const session = this.sessions.get(fromId);
    if (!session) return;
    const { pc } = session;

    try {
      if (env.type === 'offer' || env.type === 'answer') {
        const desc = env.payload as RTCSessionDescriptionInit;
        const offerCollision =
          desc.type === 'offer' && (session.makingOffer || pc.signalingState !== 'stable');
        session.ignoreOffer = !session.polite && offerCollision;
        if (session.ignoreOffer) {
          return;
        }
        await pc.setRemoteDescription(desc);
        // RemoteDescription 就绪后立即冲刷此前暂存的 ICE 候选
        if (session.pendingIceCandidates.length > 0) {
          const queued = session.pendingIceCandidates.splice(0);
          for (const cand of queued) {
            try {
              await pc.addIceCandidate(cand);
            } catch {
              // 忽略个别失效候选
            }
          }
        }
        if (desc.type === 'offer') {
          await pc.setLocalDescription();
          if (pc.localDescription) {
            this.callbacks.sendSignal({
              type: 'answer',
              targetPeerId: fromId,
              payload: pc.localDescription,
            });
          }
        }
      } else if (env.type === 'ice-candidate') {
        const candidate = env.payload as RTCIceCandidateInit;
        if (!pc.remoteDescription) {
          // 防止跨公网时 candidate 早于 offer/answer 到达而被丢弃
          session.pendingIceCandidates.push(candidate);
        } else {
          try {
            await pc.addIceCandidate(candidate);
          } catch {
            if (!session.ignoreOffer) {
              // 忽略过期候选
            }
          }
        }
      }
    } catch {
      // 忽略异常信令
    }
  }

  // 手动强制重建与指定远端节点的 P2P 连接（用于切网/卡顿后一键恢复）
  reconnectPeer(remotePeerId: string): void {
    this.ensurePeer(remotePeerId, true);
  }

  // 移除离开房间的远端节点
  removePeer(remotePeerId: string): void {
    const session = this.sessions.get(remotePeerId);
    if (!session) return;

    if (session.statsTimer !== null) {
      clearInterval(session.statsTimer);
    }
    for (const out of session.outgoing.values()) {
      out.cancelled = true;
    }
    for (const inc of session.incomingById.values()) {
      inc.cancelled = true;
      if (inc.diskStream) {
        void inc.diskStream.abort().catch(() => {});
      }
    }
    try {
      session.chatChannel?.close();
      session.fileChannel?.close();
      session.pc.close();
    } catch {
      // 忽略关闭异常
    }
    if (session.remoteAudioStream) {
      session.remoteAudioStream = null;
      this.callbacks.onRemoteAudioStream?.(remotePeerId, null);
    }
    if (session.remoteScreenStream) {
      session.remoteScreenStream = null;
      this.callbacks.onRemoteScreenStream?.(remotePeerId, null);
    }
    this.sessions.delete(remotePeerId);
  }

  // 销毁所有点对点连接与本地音视频采集
  destroyAll(): void {
    this.localMicStream = null;
    this.localScreenStream = null;
    for (const peerId of Array.from(this.sessions.keys())) {
      this.removePeer(peerId);
    }
  }

  // 挂载当前本地连麦/投屏流到指定会话
  private attachLocalStreamsToSession(session: PeerSession): void {
    if (this.localMicStream) {
      for (const track of this.localMicStream.getAudioTracks()) {
        try {
          session.micSenders.push(session.pc.addTrack(track, this.localMicStream));
        } catch {
          // 忽略重复轨道异常
        }
      }
    }
    if (this.localScreenStream) {
      for (const track of this.localScreenStream.getTracks()) {
        try {
          session.screenSenders.push(session.pc.addTrack(track, this.localScreenStream));
        } catch {
          // 忽略重复轨道异常
        }
      }
    }
  }

  private getLocalMediaState(): PeerMediaState {
    return {
      micOn: Boolean(this.localMicStream),
      micMuted: this.localMicMuted,
      screenOn: Boolean(this.localScreenStream),
    };
  }

  private broadcastLocalMediaState(): void {
    const pkt: ChatPacket = { kind: 'media-state', state: this.getLocalMediaState() };
    for (const session of this.sessions.values()) {
      this.sendChatPacket(session, pkt);
    }
  }

  // 动态开启/关闭语音连麦流（Perfect Negotiation 自动触发重协商）
  setLocalMicStream(stream: MediaStream | null, muted = false): void {
    this.localMicStream = stream;
    this.localMicMuted = muted;
    for (const session of this.sessions.values()) {
      for (const sender of session.micSenders) {
        try {
          session.pc.removeTrack(sender);
        } catch {
          // 忽略移除异常
        }
      }
      session.micSenders = [];
      if (stream) {
        for (const track of stream.getAudioTracks()) {
          track.enabled = !muted;
          try {
            session.micSenders.push(session.pc.addTrack(track, stream));
          } catch {
            // 忽略添加异常
          }
        }
      }
    }
    this.broadcastLocalMediaState();
  }

  setLocalMicMuted(muted: boolean): void {
    this.localMicMuted = muted;
    if (this.localMicStream) {
      this.localMicStream.getAudioTracks().forEach((t) => {
        t.enabled = !muted;
      });
    }
    this.broadcastLocalMediaState();
  }

  // 动态开启/停止实时屏幕共享投屏流
  setLocalScreenStream(stream: MediaStream | null): void {
    this.localScreenStream = stream;
    for (const session of this.sessions.values()) {
      for (const sender of session.screenSenders) {
        try {
          session.pc.removeTrack(sender);
        } catch {
          // 忽略移除异常
        }
      }
      session.screenSenders = [];
      if (stream) {
        for (const track of stream.getTracks()) {
          try {
            session.screenSenders.push(session.pc.addTrack(track, stream));
          } catch {
            // 忽略添加异常
          }
        }
      }
    }
    this.broadcastLocalMediaState();
  }

  // 广播成员实时状态胶囊
  broadcastMood(mood: string): void {
    const pkt: ChatPacket = { kind: 'mood', mood };
    for (const session of this.sessions.values()) {
      this.sendChatPacket(session, pkt);
    }
  }

  sendMoodToPeer(peerId: string, mood: string): void {
    const session = this.sessions.get(peerId);
    if (session) this.sendChatPacket(session, { kind: 'mood', mood });
  }

  // 向指定伙伴扔趣味道具（砸番茄 / 送鲜花 / 冰冻 / 扔炸弹）
  broadcastPropThrow(propType: PropThrowType, targetPeerId: string, targetName: string): void {
    const pkt: ChatPacket = { kind: 'prop', propType, targetPeerId, targetName };
    for (const session of this.sessions.values()) {
      this.sendChatPacket(session, pkt);
    }
  }

  // 全房同步番茄钟状态
  broadcastPomodoro(state: PomodoroState): void {
    const pkt: ChatPacket = { kind: 'pomodoro', state };
    for (const session of this.sessions.values()) {
      this.sendChatPacket(session, pkt);
    }
  }

  sendPomodoroToPeer(peerId: string, state: PomodoroState): void {
    const session = this.sessions.get(peerId);
    if (session) this.sendChatPacket(session, { kind: 'pomodoro', state });
  }

  // 多人同屏协作白板事件同步
  broadcastWhiteboard(event: WhiteboardEvent): void {
    const pkt: ChatPacket = { kind: 'wb', event };
    for (const session of this.sessions.values()) {
      this.sendChatPacket(session, pkt);
    }
  }

  sendWhiteboardToPeer(peerId: string, event: WhiteboardEvent): void {
    const session = this.sessions.get(peerId);
    if (session) this.sendChatPacket(session, { kind: 'wb', event });
  }

  // 获取当前已建立 P2P 数据通道的节点数量
  getConnectedPeerCount(): number {
    let count = 0;
    for (const s of this.sessions.values()) {
      if (s.status.dataReady) count++;
    }
    return count;
  }

  // 通过 P2P 聊天通道广播纯文本消息（返回成功通过 P2P 发送的节点数）
  broadcastText(id: string, text: string, timestamp: number, replyTo?: ReplyQuote): number {
    const pkt: ChatPacket = { kind: 'text', id, text, replyTo, timestamp };
    let sent = 0;
    for (const session of this.sessions.values()) {
      if (this.sendChatPacket(session, pkt)) {
        sent++;
      }
    }
    return sent;
  }

  // 通过 P2P 通道广播单条消息撤回指令
  broadcastRecall(msgId: string): void {
    const pkt: ChatPacket = { kind: 'recall', msgId };
    for (const session of this.sessions.values()) {
      this.sendChatPacket(session, pkt);
    }
  }

  // 广播正在输入状态
  broadcastTyping(isTyping: boolean): void {
    const pkt: ChatPacket = { kind: 'typing', isTyping };
    for (const session of this.sessions.values()) {
      this.sendChatPacket(session, pkt);
    }
  }

  // 广播全屏互动表情雨或戳一戳震动特效
  broadcastEffect(fxType: FunEffectType): void {
    const pkt: ChatPacket = { kind: 'fx', fxType };
    for (const session of this.sessions.values()) {
      this.sendChatPacket(session, pkt);
    }
  }

  // 广播消息气泡贴纸回应
  broadcastReaction(msgId: string, emoji: string): void {
    const pkt: ChatPacket = { kind: 'reaction', msgId, emoji };
    for (const session of this.sessions.values()) {
      this.sendChatPacket(session, pkt);
    }
  }

  // 广播跨设备共享闪贴板文本
  broadcastClipboard(text: string, updatedAt: number): number {
    const pkt: ChatPacket = { kind: 'clip-sync', text, updatedAt };
    let sent = 0;
    for (const session of this.sessions.values()) {
      if (this.sendChatPacket(session, pkt)) sent++;
    }
    return sent;
  }

  // 向房间所有已连接 P2P 节点发起文件或图片传输
  sendFileToAll(
    file: File | Blob,
    options: {
      transferIdPrefix: string;
      fileName: string;
      mimeType: string;
      isImage: boolean;
      isAudio?: boolean;
      isScreenshot?: boolean;
      caption?: string;
      timestamp: number;
    }
  ): FileTransferInfo[] {
    const created: FileTransferInfo[] = [];
    for (const session of this.sessions.values()) {
      if (!session.status.dataReady) continue;

      const streamId = this.nextStreamId++;
      const transferId = `${options.transferIdPrefix}_${session.peerId}`;
      const info: FileTransferInfo = {
        transferId,
        streamId,
        fileName: options.fileName,
        fileSize: file.size,
        mimeType: options.mimeType || 'application/octet-stream',
        isImage: options.isImage,
        isAudio: options.isAudio,
        isScreenshot: options.isScreenshot,
        direction: 'send',
        targetPeerId: session.peerId,
        status: 'waiting_accept',
        transferredBytes: 0,
        speedBps: 0,
        etaSeconds: 0,
      };

      session.outgoing.set(transferId, {
        transferId,
        streamId,
        file,
        info,
        cancelled: false,
        started: false,
      });

      this.sendChatPacket(session, {
        kind: 'file-offer',
        msgId: options.transferIdPrefix,
        transferId,
        streamId,
        fileName: info.fileName,
        fileSize: info.fileSize,
        mimeType: info.mimeType,
        isImage: info.isImage,
        isAudio: info.isAudio,
        isScreenshot: info.isScreenshot,
        caption: options.caption,
        timestamp: options.timestamp,
      });

      created.push({ ...info });
    }
    return created;
  }

  // 接收方同意接收文件（可选传入流式写盘句柄以实现 GB 级零内存落盘）
  acceptIncomingTransfer(peerId: string, transferId: string, diskStream?: DiskWriteStream | null): void {
    const session = this.sessions.get(peerId);
    if (!session) return;
    const inc = session.incomingById.get(transferId);
    if (!inc || inc.accepted || inc.cancelled) return;

    inc.accepted = true;
    inc.diskStream = diskStream || null;
    inc.startedAt = performance.now();
    inc.lastProgressAt = inc.startedAt;
    inc.info.status = 'transferring';
    inc.info.savedToDisk = Boolean(diskStream);
    this.callbacks.onTransferUpdate({ ...inc.info });

    this.sendChatPacket(session, {
      kind: 'file-accept',
      transferId,
    });
  }

  // 接收方拒绝文件
  rejectIncomingTransfer(peerId: string, transferId: string): void {
    const session = this.sessions.get(peerId);
    if (!session) return;
    const inc = session.incomingById.get(transferId);
    if (!inc) return;

    inc.cancelled = true;
    inc.info.status = 'rejected';
    this.callbacks.onTransferUpdate({ ...inc.info });
    session.incomingById.delete(transferId);
    session.incomingByStream.delete(inc.streamId);

    this.sendChatPacket(session, {
      kind: 'file-reject',
      transferId,
    });
  }

  // 任一方随时取消正在进行的传输
  cancelTransfer(peerId: string, transferId: string): void {
    const session = this.sessions.get(peerId);
    if (!session) return;

    const out = session.outgoing.get(transferId);
    if (out) {
      out.cancelled = true;
      out.info.status = 'cancelled';
      this.callbacks.onTransferUpdate({ ...out.info });
      session.outgoing.delete(transferId);
      this.sendChatPacket(session, { kind: 'file-cancel', transferId });
      return;
    }

    const inc = session.incomingById.get(transferId);
    if (inc) {
      inc.cancelled = true;
      if (inc.diskStream) {
        void inc.diskStream.abort().catch(() => {});
      }
      inc.info.status = 'cancelled';
      this.callbacks.onTransferUpdate({ ...inc.info });
      session.incomingById.delete(transferId);
      session.incomingByStream.delete(inc.streamId);
      this.sendChatPacket(session, { kind: 'file-cancel', transferId });
    }
  }

  private bindDataChannel(session: PeerSession, ch: RTCDataChannel): void {
    if (ch.label === 'chat') {
      session.chatChannel = ch;
      ch.onopen = () => this.refreshDataReady(session);
      ch.onclose = () => this.refreshDataReady(session);
      ch.onmessage = (ev) => {
        if (typeof ev.data === 'string') {
          this.handleChatPacket(session, ev.data);
        }
      };
    } else if (ch.label === 'file') {
      session.fileChannel = ch;
      ch.binaryType = 'arraybuffer';
      ch.bufferedAmountLowThreshold = LOW_WATER_MARK;
      ch.onopen = () => this.refreshDataReady(session);
      ch.onclose = () => this.refreshDataReady(session);
      ch.onmessage = (ev) => {
        if (ev.data instanceof ArrayBuffer) {
          const frame = ev.data;
          // 严格按 FIFO 链式串行写入，防止 File System Access API 流式写盘并发错乱或 EOF 抢跑
          session.fileReceiveQueue = session.fileReceiveQueue
            .then(() => this.handleFileBinaryFrame(session, frame))
            .catch(() => {});
        }
      };
    }
  }

  private refreshDataReady(session: PeerSession): void {
    const wasReady = session.status.dataReady;
    const ready =
      session.chatChannel?.readyState === 'open' && session.fileChannel?.readyState === 'open';
    session.status.dataReady = ready;
    if (ready) {
      if (!wasReady) {
        // 数据通道首次打通时，自动同步本地连麦/投屏状态并触发状态/番茄钟补同步
        this.sendChatPacket(session, { kind: 'media-state', state: this.getLocalMediaState() });
        this.callbacks.onPeerDataReady?.(session.peerId);
      }
      void this.probeLinkStats(session);
    } else {
      this.callbacks.onLinkStatusChange({ ...session.status });
    }
  }

  private sendChatPacket(session: PeerSession, pkt: ChatPacket): boolean {
    if (!session.chatChannel || session.chatChannel.readyState !== 'open') {
      return false;
    }
    try {
      session.chatChannel.send(JSON.stringify(pkt));
      return true;
    } catch {
      return false;
    }
  }

  private handleChatPacket(session: PeerSession, raw: string): void {
    let pkt: ChatPacket;
    try {
      pkt = JSON.parse(raw) as ChatPacket;
    } catch {
      return;
    }

    switch (pkt.kind) {
      case 'text':
        this.callbacks.onTextMessage(session.peerId, {
          id: pkt.id,
          text: pkt.text,
          replyTo: pkt.replyTo,
          timestamp: pkt.timestamp,
        });
        break;

      case 'recall':
        this.callbacks.onRecallMessage?.(session.peerId, pkt.msgId);
        break;

      case 'typing':
        this.callbacks.onTyping(session.peerId, pkt.isTyping);
        break;

      case 'fx':
        this.callbacks.onFunEffect?.(session.peerId, pkt.fxType);
        break;

      case 'reaction':
        this.callbacks.onReaction?.(session.peerId, pkt.msgId, pkt.emoji);
        break;

      case 'clip-sync':
        this.callbacks.onClipboardSync?.(session.peerId, pkt.text, pkt.updatedAt);
        break;

      case 'media-state':
        this.callbacks.onPeerMediaState?.(session.peerId, pkt.state);
        if (!pkt.state.screenOn && session.remoteScreenStream) {
          session.remoteScreenStream = null;
          this.callbacks.onRemoteScreenStream?.(session.peerId, null);
        }
        if (!pkt.state.micOn && session.remoteAudioStream) {
          session.remoteAudioStream = null;
          this.callbacks.onRemoteAudioStream?.(session.peerId, null);
        }
        break;

      case 'mood':
        this.callbacks.onPeerMood?.(session.peerId, pkt.mood);
        break;

      case 'prop':
        this.callbacks.onPropThrow?.(session.peerId, pkt.propType, pkt.targetPeerId, pkt.targetName);
        break;

      case 'pomodoro':
        this.callbacks.onPomodoroSync?.(session.peerId, pkt.state);
        break;

      case 'wb':
        this.callbacks.onWhiteboardEvent?.(session.peerId, pkt.event);
        break;

      case 'file-offer': {
        const info: FileTransferInfo = {
          transferId: pkt.transferId,
          streamId: pkt.streamId,
          fileName: pkt.fileName,
          fileSize: pkt.fileSize,
          mimeType: pkt.mimeType,
          isImage: pkt.isImage,
          isAudio: pkt.isAudio,
          isScreenshot: pkt.isScreenshot,
          direction: 'receive',
          targetPeerId: session.peerId,
          status: 'offering',
          transferredBytes: 0,
          speedBps: 0,
          etaSeconds: 0,
        };
        const inc: IncomingTransfer = {
          transferId: pkt.transferId,
          streamId: pkt.streamId,
          peerId: session.peerId,
          info,
          caption: pkt.caption,
          timestamp: pkt.timestamp,
          accepted: false,
          cancelled: false,
          sha: new IncrementalSHA256(),
          chunks: [],
          startedAt: 0,
          lastProgressAt: 0,
          lastProgressBytes: 0,
        };
        session.incomingById.set(pkt.transferId, inc);
        session.incomingByStream.set(pkt.streamId, inc);
        this.callbacks.onFileOffer(session.peerId, { ...info }, pkt.msgId || `tr_${pkt.transferId}`, pkt.caption, pkt.timestamp);

        // 图片/截图或符合自动接收阈值的小文件直接开始流式接收
        if (this.callbacks.shouldAutoAccept(info)) {
          this.acceptIncomingTransfer(session.peerId, pkt.transferId, null);
        }
        break;
      }

      case 'file-accept': {
        const out = session.outgoing.get(pkt.transferId);
        if (out && !out.started && !out.cancelled) {
          out.started = true;
          void this.pumpOutgoingFile(session, out);
        }
        break;
      }

      case 'file-reject': {
        const out = session.outgoing.get(pkt.transferId);
        if (out) {
          out.cancelled = true;
          out.info.status = 'rejected';
          this.callbacks.onTransferUpdate({ ...out.info });
          session.outgoing.delete(pkt.transferId);
        }
        break;
      }

      case 'file-cancel': {
        const out = session.outgoing.get(pkt.transferId);
        if (out) {
          out.cancelled = true;
          out.info.status = 'cancelled';
          this.callbacks.onTransferUpdate({ ...out.info });
          session.outgoing.delete(pkt.transferId);
        }
        const inc = session.incomingById.get(pkt.transferId);
        if (inc) {
          inc.cancelled = true;
          if (inc.diskStream) {
            void inc.diskStream.abort().catch(() => {});
          }
          inc.info.status = 'cancelled';
          this.callbacks.onTransferUpdate({ ...inc.info });
          session.incomingById.delete(pkt.transferId);
          session.incomingByStream.delete(inc.streamId);
        }
        break;
      }

      case 'file-ack': {
        const out = session.outgoing.get(pkt.transferId);
        if (out) {
          out.info.status = 'completed';
          out.info.verified = pkt.verified;
          out.info.sha256 = pkt.sha256;
          out.info.transferredBytes = out.info.fileSize;
          out.info.etaSeconds = 0;
          this.callbacks.onTransferUpdate({ ...out.info });
          session.outgoing.delete(pkt.transferId);
        }
        break;
      }
    }
  }

  // 分片流式读取本地文件并带背压发送，绝不一次性将大文件读入内存
  private async pumpOutgoingFile(session: PeerSession, out: OutgoingTransfer): Promise<void> {
    const ch = session.fileChannel;
    if (!ch || ch.readyState !== 'open') {
      out.info.status = 'error';
      out.info.errorMessage = 'P2P 数据通道未就绪';
      this.callbacks.onTransferUpdate({ ...out.info });
      return;
    }

    const maxMsg = session.pc.sctp?.maxMessageSize || 65536;
    const chunkSize = Math.max(16 * 1024, Math.min(DEFAULT_CHUNK_SIZE, maxMsg - 1024));
    const sha = new IncrementalSHA256();

    out.info.status = 'transferring';
    this.callbacks.onTransferUpdate({ ...out.info });

    let offset = 0;
    let chunkIndex = 0;
    const totalSize = out.file.size;
    let lastReportAt = performance.now();
    let lastReportBytes = 0;

    try {
      while (offset < totalSize) {
        if (out.cancelled) return;
        if (ch.readyState !== 'open') {
          throw new Error('P2P 传输通道已断开');
        }

        // 背压流控：等待发送缓冲区回落到低水位再继续发下一片
        if (ch.bufferedAmount > HIGH_WATER_MARK) {
          await waitBufferedLow(ch);
          if (out.cancelled) return;
        }

        const end = Math.min(offset + chunkSize, totalSize);
        const sliceBuf = await out.file.slice(offset, end).arrayBuffer();
        const payload = new Uint8Array(sliceBuf);
        sha.update(payload);

        // 组装二进制帧：[streamId 4B][chunkIndex 4B][payload]
        const frame = new Uint8Array(8 + payload.byteLength);
        const view = new DataView(frame.buffer);
        view.setUint32(0, out.streamId, false);
        view.setUint32(4, chunkIndex, false);
        frame.set(payload, 8);

        ch.send(frame);

        offset = end;
        chunkIndex++;

        const now = performance.now();
        if (now - lastReportAt >= 200 || offset >= totalSize) {
          const dt = Math.max((now - lastReportAt) / 1000, 0.05);
          const speed = (offset - lastReportBytes) / dt;
          out.info.transferredBytes = offset;
          out.info.speedBps = speed;
          out.info.etaSeconds = speed > 0 ? (totalSize - offset) / speed : 0;
          this.callbacks.onTransferUpdate({ ...out.info });
          lastReportAt = now;
          lastReportBytes = offset;
        }
      }

      // 在同一有序 file 通道上发送 EOF 尾帧（含 32 字节原始 SHA-256），保证严格位于全部分片之后到达
      const digest = sha.digestBytes();
      const eofFrame = new Uint8Array(8 + 32);
      const eofView = new DataView(eofFrame.buffer);
      eofView.setUint32(0, out.streamId, false);
      eofView.setUint32(4, EOF_CHUNK_INDEX, false);
      eofFrame.set(digest, 8);

      if (ch.bufferedAmount > HIGH_WATER_MARK) {
        await waitBufferedLow(ch);
      }
      if (out.cancelled || ch.readyState !== 'open') return;
      ch.send(eofFrame);

      out.info.sha256 = bytesToHex(digest);
      out.info.transferredBytes = totalSize;
      out.info.etaSeconds = 0;
      this.callbacks.onTransferUpdate({ ...out.info });
    } catch (err) {
      if (!out.cancelled) {
        out.info.status = 'error';
        out.info.errorMessage = err instanceof Error ? err.message : '发送中断';
        this.callbacks.onTransferUpdate({ ...out.info });
      }
    }
  }

  // 处理接收到的二进制文件分片与 EOF 校验帧
  private async handleFileBinaryFrame(session: PeerSession, buffer: ArrayBuffer): Promise<void> {
    if (buffer.byteLength < 8) return;
    const view = new DataView(buffer);
    const streamId = view.getUint32(0, false);
    const chunkIndex = view.getUint32(4, false);

    const inc = session.incomingByStream.get(streamId);
    if (!inc || inc.cancelled) return;

    // EOF 尾帧：结束写入并比对 SHA-256
    if (chunkIndex === EOF_CHUNK_INDEX) {
      const expectedHex =
        buffer.byteLength >= 40 ? bytesToHex(new Uint8Array(buffer, 8, 32)) : '';
      const actualHex = inc.sha.digestHex();
      const verified = Boolean(expectedHex) && expectedHex === actualHex;

      try {
        if (inc.diskStream) {
          await inc.diskStream.close();
          inc.diskStream = null;
        } else {
          const blob = new Blob(inc.chunks, { type: inc.info.mimeType });
          inc.chunks = [];
          inc.info.objectUrl = URL.createObjectURL(blob);
        }

        inc.info.status = 'completed';
        inc.info.transferredBytes = inc.info.fileSize;
        inc.info.sha256 = actualHex;
        inc.info.verified = verified;
        inc.info.etaSeconds = 0;
        this.callbacks.onTransferUpdate({ ...inc.info });

        this.sendChatPacket(session, {
          kind: 'file-ack',
          transferId: inc.transferId,
          verified,
          sha256: actualHex,
        });
      } catch (err) {
        inc.info.status = 'error';
        inc.info.errorMessage = err instanceof Error ? err.message : '文件保存失败';
        this.callbacks.onTransferUpdate({ ...inc.info });
      } finally {
        session.incomingById.delete(inc.transferId);
        session.incomingByStream.delete(streamId);
      }
      return;
    }

    // 普通数据分片
    const payload = new Uint8Array(buffer, 8);
    inc.sha.update(payload);

    try {
      if (inc.diskStream) {
        await inc.diskStream.write(payload);
      } else {
        // 拷贝分片字节以便独立持有并释放原始帧
        inc.chunks.push(payload.slice());
      }
    } catch (err) {
      inc.cancelled = true;
      inc.chunks = [];
      if (inc.diskStream) {
        void inc.diskStream.abort().catch(() => {});
        inc.diskStream = null;
      }
      inc.info.status = 'error';
      inc.info.errorMessage = err instanceof Error ? err.message : '写入硬盘失败';
      this.callbacks.onTransferUpdate({ ...inc.info });
      session.incomingById.delete(inc.transferId);
      session.incomingByStream.delete(streamId);
      this.sendChatPacket(session, { kind: 'file-cancel', transferId: inc.transferId });
      return;
    }

    inc.info.transferredBytes += payload.byteLength;
    const now = performance.now();
    if (now - inc.lastProgressAt >= 200 || inc.info.transferredBytes >= inc.info.fileSize) {
      const dt = Math.max((now - inc.lastProgressAt) / 1000, 0.05);
      const speed = (inc.info.transferredBytes - inc.lastProgressBytes) / dt;
      inc.info.speedBps = speed;
      inc.info.etaSeconds =
        speed > 0 ? Math.max(0, (inc.info.fileSize - inc.info.transferredBytes) / speed) : 0;
      this.callbacks.onTransferUpdate({ ...inc.info });
      inc.lastProgressAt = now;
      inc.lastProgressBytes = inc.info.transferredBytes;
    }
  }

  // 解析 RTCPeerConnection.getStats() 以精准区分 局域网直连 / 公网P2P直连 / TURN服务器中转
  private async probeLinkStats(session: PeerSession): Promise<void> {
    if (session.pc.connectionState === 'closed') return;
    try {
      const stats = await session.pc.getStats();
      let selectedPair: Record<string, unknown> | null = null;
      const candidates = new Map<string, Record<string, unknown>>();

      stats.forEach((report) => {
        const rec = report as unknown as Record<string, unknown>;
        if (rec.type === 'local-candidate' || rec.type === 'remote-candidate') {
          candidates.set(String(rec.id), rec);
        }
        if (rec.type === 'candidate-pair') {
          const isSelected = Boolean(rec.selected) || (rec.state === 'succeeded' && Boolean(rec.nominated));
          if (isSelected) {
            selectedPair = rec;
          }
        }
      });

      if (!selectedPair) return;
      const pair = selectedPair as Record<string, unknown>;
      const localCand = candidates.get(String(pair.localCandidateId));
      const remoteCand = candidates.get(String(pair.remoteCandidateId));
      const localType = String(localCand?.candidateType || '');
      const remoteType = String(remoteCand?.candidateType || '');

      let transport: LinkTransportType = 'p2p-wan';
      if (localType === 'relay' || remoteType === 'relay') {
        transport = 'relay-turn';
      } else if (localType === 'host' && remoteType === 'host') {
        transport = 'p2p-lan';
      }

      const rttSec = Number(pair.currentRoundTripTime);
      session.status.transport = transport;
      session.status.rttMs = Number.isFinite(rttSec) && rttSec >= 0 ? Math.round(rttSec * 1000) : undefined;
      this.callbacks.onLinkStatusChange({ ...session.status });
    } catch {
      // 忽略统计探测瞬态错误
    }
  }
}

function waitBufferedLow(channel: RTCDataChannel): Promise<void> {
  return new Promise((resolve) => {
    if (channel.bufferedAmount <= LOW_WATER_MARK || channel.readyState !== 'open') {
      resolve();
      return;
    }
    const onLow = () => {
      cleanup();
      resolve();
    };
    const onClose = () => {
      cleanup();
      resolve();
    };
    const cleanup = () => {
      channel.removeEventListener('bufferedamountlow', onLow);
      channel.removeEventListener('close', onClose);
    };
    channel.addEventListener('bufferedamountlow', onLow);
    channel.addEventListener('close', onClose);
  });
}
