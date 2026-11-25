import React, { useEffect, useRef, useState } from 'react';
import {
  Download,
  HardDriveDownload,
  CheckCircle2,
  XCircle,
  Copy,
  Check,
  Camera,
  Cloud,
  File as FileIcon,
  FileText,
  FileSpreadsheet,
  FileArchive,
  FileCode2,
  FileVideo,
  FileAudio,
  FileImage,
  Smartphone,
  Clipboard,
  Sparkles,
  EyeOff,
  Mic,
  Reply,
  Undo2,
} from 'lucide-react';
import type { ChatItem, FileTransferInfo } from '../types';
import {
  copyTextToClipboard,
  formatBytes,
  formatDuration,
  supportsDirectDiskSave,
  triggerBlobDownload,
} from '../lib/platform';
import { launchScreenEffect } from '../lib/effects';
import { Avatar } from './Avatar';

interface MessageListProps {
  items: ChatItem[];
  recalledTexts?: Record<string, string>;
  onReEditRecalled?: (text: string) => void;
  onReactMessage: (msgId: string, emoji: string) => void;
  onReplyMessage: (item: ChatItem) => void;
  onRecallMessage: (msgId: string) => void;
  onAcceptFile: (
    peerId: string,
    transferId: string,
    mode: 'memory' | 'disk' | 'resume',
    fileName: string,
    fileSize: number,
    resumeKey?: string
  ) => void;
  onRejectFile: (peerId: string, transferId: string) => void;
  onCancelTransfer: (peerId: string, transferId: string) => void;
  onRequestResume?: (peerId: string, transfer: FileTransferInfo) => void;
  onPreviewImage: (src: string, fileName?: string, caption?: string) => void;
}

const URL_REGEX = /(https?:\/\/[^\s<>"']+)/g;
const QUICK_EMOJIS = ['👍', '❤️', '😂', '🔥', '🎉', '👀'];
const RECALL_WINDOW_MS = 120_000;
const TIME_DIVIDER_GAP_MS = 5 * 60 * 1000;
const WEEKDAY_NAMES = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];

// 消息流：防窥密信卡、悬停贴纸回应/引用/2分钟撤回/重新编辑、居中时间胶囊、图片与语义文件卡
export const MessageList: React.FC<MessageListProps> = ({
  items,
  recalledTexts,
  onReEditRecalled,
  onReactMessage,
  onReplyMessage,
  onRecallMessage,
  onAcceptFile,
  onRejectFile,
  onCancelTransfer,
  onRequestResume,
  onPreviewImage,
}) => {
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [revealedIds, setRevealedIds] = useState<Record<string, boolean>>({});
  const [activeActionId, setActiveActionId] = useState<string | null>(null);
  const [nowMs, setNowMs] = useState<number>(() => Date.now());
  const revealTimersRef = useRef<Record<string, number>>({});
  const canDirectDisk = supportsDirectDiskSave();

  const hasRecallableMessage = items.some(
    (it) =>
      it.isSelf &&
      it.kind !== 'file' &&
      it.kind !== 'system' &&
      nowMs - it.timestamp <= RECALL_WINDOW_MS
  );

  // 仅当存在仍处于 2 分钟撤回窗口内的自己消息时运行定时器，超时自动隐藏撤回按钮
  useEffect(() => {
    if (!hasRecallableMessage) return;
    const id = window.setInterval(() => setNowMs(Date.now()), 1000);
    return () => clearInterval(id);
  }, [hasRecallableMessage]);

  useEffect(() => {
    return () => {
      Object.values(revealTimersRef.current).forEach((tid) => clearTimeout(tid));
      revealTimersRef.current = {};
    };
  }, []);

  const handleCopyText = async (id: string, text: string) => {
    if (await copyTextToClipboard(text)) {
      setCopiedId(id);
      setTimeout(() => setCopiedId((cur) => (cur === id ? null : cur)), 1500);
    }
  };

  const handleRevealSecret = (id: string) => {
    if (revealTimersRef.current[id]) {
      clearTimeout(revealTimersRef.current[id]);
      delete revealTimersRef.current[id];
    }
    if (revealedIds[id]) {
      setRevealedIds((prev) => ({ ...prev, [id]: false }));
      return;
    }
    setRevealedIds((prev) => ({ ...prev, [id]: true }));
    launchScreenEffect('confetti');
    // 8 秒后自动重新盖上防窥涂层，防止身后他人窥屏（重复点击自动重置计时器）
    revealTimersRef.current[id] = window.setTimeout(() => {
      delete revealTimersRef.current[id];
      setRevealedIds((prev) => ({ ...prev, [id]: false }));
    }, 8000);
  };

  return (
    <div className="chat-stream">
      {items.map((item, idx) => {
        const prevItem = idx > 0 ? items[idx - 1] : undefined;
        const showTimeDivider = shouldShowTimeDivider(prevItem, item);

        if (item.kind === 'system') {
          const reeditText = recalledTexts?.[item.id];
          return (
            <React.Fragment key={item.id}>
              {showTimeDivider && (
                <div className="msg-time-divider">{formatMessageDividerTime(item.timestamp)}</div>
              )}
              <div className="sys-pill">
                <span>{item.text}</span>
                {reeditText && onReEditRecalled && (
                  <button
                    type="button"
                    className="reedit-link-btn"
                    onClick={() => onReEditRecalled(reeditText)}
                    aria-label="重新编辑已撤回的消息"
                  >
                    重新编辑
                  </button>
                )}
              </div>
            </React.Fragment>
          );
        }

        const timeLabel = formatClock(item.timestamp);
        const reactionEntries = Object.entries(item.reactions || {}).filter(
          ([, users]) => users.length > 0
        );
        const isSecretHidden = Boolean(item.isSecret && !revealedIds[item.id]);
        const canRecall =
          item.isSelf &&
          item.kind !== 'file' &&
          nowMs - item.timestamp <= RECALL_WINDOW_MS;

        return (
          <React.Fragment key={item.id}>
            {showTimeDivider && (
              <div className="msg-time-divider">{formatMessageDividerTime(item.timestamp)}</div>
            )}
            <div className={`msg-item ${item.isSelf ? 'self' : 'other'}`}>
              <Avatar name={item.senderName} avatar={item.senderAvatar} size={36} />
              <div className="msg-stack">
                <div className="msg-header">
                  <span className="msg-sender-name">
                    {item.isSelf ? `${item.senderName}（我）` : item.senderName}
                  </span>
                  <span className="mono">{timeLabel}</span>
                  {item.isSecret && (
                    <span className="msg-secret-tag">
                      <EyeOff size={11} aria-hidden="true" /> 密信
                    </span>
                  )}
                  {item.isClipSync && (
                    <span className="pill pill-accent msg-mini-pill">
                      <Clipboard size={10} aria-hidden="true" /> 剪贴板同步
                    </span>
                  )}
                  {item.fromCache && (
                    <span
                      title="恢复自服务器缓存"
                      className="msg-cache-tag"
                    >
                      <Cloud size={11} aria-hidden="true" /> 缓存
                    </span>
                  )}
                  {item.kind === 'text' && item.text && !isSecretHidden && (
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm msg-copy-btn"
                      onClick={() => void handleCopyText(item.id, item.text || '')}
                      aria-label="复制消息内容"
                    >
                      {copiedId === item.id ? <Check size={11} aria-hidden="true" /> : <Copy size={11} aria-hidden="true" />}
                      {copiedId === item.id ? '已拷' : '复制'}
                    </button>
                  )}
                </div>

                <div
                  className={`bubble-wrap ${activeActionId === item.id ? 'touch-active' : ''}`}
                  onClick={() => {
                    // 触摸屏无悬停态：轻点气泡可切换显示表情回应/引用/撤回快捷栏
                    if (window.matchMedia && window.matchMedia('(hover: none)').matches) {
                      setActiveActionId((cur) => (cur === item.id ? null : item.id));
                    }
                  }}
                >
                  {/* 悬停或移动端点击弹出贴纸回应 + 引用回复 + 撤回条 */}
                  <div className="quick-react-bar" onClick={(e) => e.stopPropagation()}>
                    {QUICK_EMOJIS.map((em) => (
                      <button
                        key={em}
                        type="button"
                        className="react-pick-btn"
                        onClick={() => {
                          onReactMessage(item.id, em);
                          setActiveActionId(null);
                        }}
                        title={`回应 ${em}`}
                        aria-label={`用 ${em} 回应`}
                      >
                        {em}
                      </button>
                    ))}
                    <span className="react-divider" />
                    <button
                      type="button"
                      className="react-action-btn"
                      onClick={() => {
                        onReplyMessage(item);
                        setActiveActionId(null);
                      }}
                      title="引用回复"
                      aria-label="引用回复此消息"
                    >
                      <Reply size={13} aria-hidden="true" />
                    </button>
                    {canRecall && (
                      <button
                        type="button"
                        className="react-action-btn danger"
                        onClick={() => {
                          onRecallMessage(item.id);
                          setActiveActionId(null);
                        }}
                        title="撤回此消息（2 分钟内有效，同步删除服务器缓存）"
                        aria-label="撤回此消息"
                      >
                        <Undo2 size={13} aria-hidden="true" />
                      </button>
                    )}
                  </div>

                  {item.kind === 'text' && (
                    <div
                      className={`bubble-text ${item.isSecret ? 'secret-bubble' : ''}`}
                      onClick={item.isSecret ? () => handleRevealSecret(item.id) : undefined}
                      title={item.isSecret ? '点击刮开/重新遮盖防窥涂层' : undefined}
                    >
                      {item.replyTo && (
                        <div className="reply-quote-box">
                          <span className="reply-quote-sender">@{item.replyTo.senderName}</span>
                          <span className="reply-quote-text">{item.replyTo.preview}</span>
                        </div>
                      )}
                      <span style={{ filter: isSecretHidden ? 'blur(10px)' : 'none', userSelect: isSecretHidden ? 'none' : 'text' }}>
                        {renderTextWithLinks(item.text || '')}
                      </span>
                      {isSecretHidden && (
                        <div className="secret-foil">
                          <Sparkles size={15} aria-hidden="true" />
                          <span>防窥密信 · 点击揭晓</span>
                        </div>
                      )}
                    </div>
                  )}

                  {item.kind === 'image' && (
                    <PolaroidImageCard
                      item={item}
                      onPreview={onPreviewImage}
                      onCancelTransfer={onCancelTransfer}
                    />
                  )}

                  {item.kind === 'audio' && <VoiceNoteCard item={item} />}

                  {item.kind === 'file' && item.transfer && (
                    <TransferFileCard
                      transfer={item.transfer}
                      canDirectDisk={canDirectDisk}
                      onAccept={onAcceptFile}
                      onReject={onRejectFile}
                      onCancel={onCancelTransfer}
                      onRequestResume={onRequestResume}
                    />
                  )}
                </div>

                {reactionEntries.length > 0 && (
                  <div className="reaction-chips">
                    {reactionEntries.map(([em, users]) => (
                      <button
                        key={em}
                        type="button"
                        className="reaction-chip"
                        onClick={() => onReactMessage(item.id, em)}
                        aria-label={`表情回应 ${em}，共 ${users.length} 人`}
                      >
                        <span>{em}</span>
                        <span className="mono">{users.length}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </React.Fragment>
        );
      })}
    </div>
  );
};

const PolaroidImageCard: React.FC<{
  item: ChatItem;
  onPreview: (src: string, fileName?: string, caption?: string) => void;
  onCancelTransfer: (peerId: string, transferId: string) => void;
}> = ({ item, onPreview, onCancelTransfer }) => {
  const tr = item.transfer;
  const imgSrc = item.imageUrl || tr?.objectUrl;
  const pct =
    tr && tr.fileSize > 0
      ? Math.min(100, Math.round((tr.transferredBytes / tr.fileSize) * 100))
      : 100;

  return (
    <div className="media-polaroid">
      {imgSrc ? (
        <img
          src={imgSrc}
          alt={item.caption || tr?.fileName || '图片'}
          onClick={() => onPreview(imgSrc, tr?.fileName, item.caption)}
        />
      ) : (
        <div style={{ padding: 18, width: 244, display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div style={{ fontSize: 13, fontWeight: 700 }}>
            {tr?.status === 'error'
              ? '图片接收中断'
              : tr?.status === 'cancelled'
                ? '已取消传输'
                : `正在直传图片 ${pct}%`}
          </div>
          {tr && tr.status === 'transferring' && (
            <>
              <div className="progress-rail">
                <div className="progress-bar" style={{ width: `${pct}%` }} />
              </div>
              <div className="mono" style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: 'var(--text-muted)' }}>
                <span>{formatBytes(tr.speedBps)}/s</span>
                {tr.targetPeerId && (
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm"
                    style={{ height: 20, padding: '0 6px', fontSize: 11 }}
                    onClick={() => onCancelTransfer(tr.targetPeerId!, transferIdOf(tr))}
                  >
                    取消
                  </button>
                )}
              </div>
            </>
          )}
        </div>
      )}

      <div className="media-polaroid-foot">
        <div className="media-polaroid-meta">
          {item.isScreenshot && (
            <span className="pill pill-accent msg-mini-pill">
              <Camera size={10} aria-hidden="true" /> 截图
            </span>
          )}
          <span className="media-polaroid-caption">{item.caption || tr?.fileName || '图片'}</span>
        </div>
        {imgSrc && (
          <button
            type="button"
            className="btn btn-ghost btn-icon msg-mini-icon-btn"
            title="保存原图"
            aria-label="保存原图"
            onClick={() =>
              triggerBlobDownload(imgSrc, tr?.fileName || `baychat-${item.timestamp}.png`)
            }
          >
            <Download size={13} aria-hidden="true" />
          </button>
        )}
      </div>
    </div>
  );
};

const VoiceNoteCard: React.FC<{ item: ChatItem }> = ({ item }) => {
  const src = item.audioUrl || item.transfer?.objectUrl;
  const [playError, setPlayError] = useState(false);
  const mimeLower = (item.transfer?.mimeType || '').toLowerCase();
  const fallbackExt =
    mimeLower.includes('mp4') || mimeLower.includes('m4a') || mimeLower.includes('aac')
      ? 'm4a'
      : mimeLower.includes('ogg')
        ? 'ogg'
        : 'webm';
  return (
    <div className="voice-bubble-card">
      <div className="voice-bubble-head">
        <div className="voice-bubble-meta">
          <span className="pill pill-accent msg-mini-pill">
            <Mic size={11} aria-hidden="true" /> 语音消息
          </span>
          {item.caption && (
            <span className="mono voice-bubble-duration">
              {item.caption}
            </span>
          )}
        </div>
        {src && (
          <button
            type="button"
            className="btn btn-ghost btn-icon msg-mini-icon-btn"
            title="保存语音文件"
            aria-label="保存语音文件"
            onClick={() =>
              triggerBlobDownload(src, item.transfer?.fileName || `voice-${item.timestamp}.${fallbackExt}`)
            }
          >
            <Download size={12} aria-hidden="true" />
          </button>
        )}
      </div>
      {src ? (
        <>
          <audio
            controls
            preload="metadata"
            src={src}
            onError={() => setPlayError(true)}
            className="voice-audio-player"
          />
          {playError && (
            <div className="voice-decode-hint">
              当前浏览器无法内联解码此音频格式，可点击右上角保存后播放
            </div>
          )}
        </>
      ) : (
        <div className="voice-loading-hint">正在接收语音消息...</div>
      )}
    </div>
  );
};

function transferIdOf(tr: FileTransferInfo): string {
  return tr.transferId;
}

const TransferFileCard: React.FC<{
  transfer: FileTransferInfo;
  canDirectDisk: boolean;
  onAccept: (
    peerId: string,
    transferId: string,
    mode: 'memory' | 'disk' | 'resume',
    fileName: string,
    fileSize: number,
    resumeKey?: string
  ) => void;
  onReject: (peerId: string, transferId: string) => void;
  onCancel: (peerId: string, transferId: string) => void;
  onRequestResume?: (peerId: string, transfer: FileTransferInfo) => void;
}> = ({ transfer, canDirectDisk, onAccept, onReject, onCancel, onRequestResume }) => {
  const rawPct =
    transfer.fileSize > 0
      ? Math.min(100, (transfer.transferredBytes / transfer.fileSize) * 100)
      : 100;
  // 传输中给予最小 2% 可见填充条，避免超大文件初期看起来像空白槽
  const barPct = transfer.status === 'transferring' ? Math.max(2, rawPct) : rawPct;
  const pctLabel = rawPct > 0 && rawPct < 10 ? rawPct.toFixed(1) : String(Math.round(rawPct));
  const ext = extractExtension(transfer.fileName);
  const { categoryClass, icon } = resolveFileTypeMeta(transfer.fileName, transfer.mimeType);
  const { stem, extPart } = splitFileNameParts(transfer.fileName);
  const isLargeFile = transfer.fileSize >= 500 * 1024 * 1024;
  const hasCachedResume = Boolean(transfer.canResumeBytes && transfer.canResumeBytes > 0);

  return (
    <div className="file-bento-card">
      <div className="file-card-top">
        <div className={`ext-jelly-box file-type-box ${categoryClass}`}>
          {icon}
          {ext && <span className="file-type-ext-tag">{ext}</span>}
        </div>
        <div className="file-card-info">
          <div className="file-name-middle" title={transfer.fileName}>
            <span className="file-name-stem">{stem}</span>
            {extPart && <span className="file-name-ext">{extPart}</span>}
          </div>
          <div className="mono file-card-sub">
            {formatBytes(transfer.fileSize)} ·{' '}
            {transfer.isHistoryRecord
              ? transfer.cachedDownloadUrl
                ? '云端限时缓存 · 随时可下'
                : 'P2P 传输历史记录'
              : transfer.direction === 'send'
                ? transfer.targetPeerName
                  ? `直发给 ${transfer.targetPeerName}`
                  : transfer.cachedDownloadUrl
                    ? '已上传至房间缓存'
                    : 'P2P 直发'
                : '点对点接收'}
          </div>
        </div>
      </div>

      {(transfer.status === 'transferring' || transfer.status === 'waiting_accept') && (
        <div className="file-progress-wrap">
          <div className="progress-rail">
            <div className="progress-bar" style={{ width: `${barPct}%` }} />
          </div>
          <div className="mono file-progress-meta">
            <span>
              {transfer.status === 'waiting_accept'
                ? '等待对方确认接收...'
                : transfer.transferredBytes === 0
                  ? '已确认，等待数据流...'
                  : `${pctLabel}% · ${formatBytes(transfer.transferredBytes)} / ${formatBytes(transfer.fileSize)} · ${formatBytes(transfer.speedBps)}/s`}
            </span>
            {transfer.status === 'transferring' && transfer.speedBps > 0 && (
              <span>剩余 {formatDuration(transfer.etaSeconds)}</span>
            )}
          </div>
          {transfer.resumedFromBytes && transfer.resumedFromBytes > 0 && (
            <div className="file-resume-hint">
              已从 {formatBytes(transfer.resumedFromBytes)} 断点自动续传
            </div>
          )}
        </div>
      )}

      <div className="file-actions-row">
        {transfer.direction === 'receive' && transfer.status === 'offering' && transfer.targetPeerId && (
          <>
            {canDirectDisk && hasCachedResume && (
              <button
                type="button"
                className="btn btn-primary btn-sm"
                onClick={() =>
                  onAccept(
                    transfer.targetPeerId!,
                    transfer.transferId,
                    'resume',
                    transfer.fileName,
                    transfer.fileSize,
                    transfer.resumeKey
                  )
                }
              >
                <HardDriveDownload size={13} aria-hidden="true" /> 断点续传 (已收 {formatBytes(transfer.canResumeBytes!)})
              </button>
            )}
            {canDirectDisk && (
              <button
                type="button"
                className={`btn btn-sm ${hasCachedResume ? 'btn-secondary' : 'btn-primary'}`}
                onClick={() =>
                  onAccept(
                    transfer.targetPeerId!,
                    transfer.transferId,
                    'disk',
                    transfer.fileName,
                    transfer.fileSize,
                    transfer.resumeKey
                  )
                }
                title="边收边直接写入电脑硬盘，大文件不占内存"
              >
                <HardDriveDownload size={13} aria-hidden="true" /> {isLargeFile ? '直写硬盘 (推荐)' : '直写硬盘'}
              </button>
            )}
            {canDirectDisk && !hasCachedResume && transfer.fileSize >= 50 * 1024 * 1024 && (
              <button
                type="button"
                className="btn btn-soft btn-sm"
                onClick={() =>
                  onAccept(
                    transfer.targetPeerId!,
                    transfer.transferId,
                    'resume',
                    transfer.fileName,
                    transfer.fileSize,
                    transfer.resumeKey
                  )
                }
                title="选择硬盘里上次中断未下完的半成品文件，从断点接着传"
              >
                续传半成品
              </button>
            )}
            <button
              type="button"
              className={`btn btn-sm ${canDirectDisk ? 'btn-secondary' : 'btn-primary'}`}
              onClick={() =>
                onAccept(
                  transfer.targetPeerId!,
                  transfer.transferId,
                  'memory',
                  transfer.fileName,
                  transfer.fileSize,
                  transfer.resumeKey
                )
              }
              title={isLargeFile ? '超大文件不建议内存接收，建议点「直写硬盘」' : '先收到浏览器内存再保存'}
            >
              <Download size={13} aria-hidden="true" /> {isLargeFile && canDirectDisk ? '内存接收' : '接收'}
            </button>
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => onReject(transfer.targetPeerId!, transfer.transferId)}
            >
              婉拒
            </button>
          </>
        )}

        {(transfer.status === 'transferring' || transfer.status === 'waiting_accept') &&
          transfer.targetPeerId && (
            <button
              type="button"
              className="btn btn-danger-soft btn-sm"
              onClick={() => onCancel(transfer.targetPeerId!, transfer.transferId)}
            >
              取消
            </button>
          )}

        {transfer.status === 'completed' && (
          <>
            {transfer.isHistoryRecord && !transfer.cachedDownloadUrl ? (
              <span className="pill pill-neutral" title="超过云端单文件缓存上限或发送时未缓存，仅保留文件传输记录">
                <Cloud size={12} aria-hidden="true" /> 仅 P2P 记录（未存云端，需发送方在线重传）
              </span>
            ) : (
              <>
                <span className="pill pill-emerald">
                  <CheckCircle2 size={12} aria-hidden="true" />
                  {transfer.verified
                    ? 'SHA-256 校验通过'
                    : transfer.cachedDownloadUrl
                      ? '云端可下载'
                      : '传输完成'}
                </span>
                {transfer.direction === 'receive' && transfer.savedToDisk && (
                  <span className="file-saved-hint">已写入本地磁盘</span>
                )}
                {transfer.direction === 'receive' && transfer.objectUrl && !transfer.savedToDisk && (
                  <button
                    type="button"
                    className="btn btn-soft btn-sm"
                    onClick={() => triggerBlobDownload(transfer.objectUrl!, transfer.fileName)}
                  >
                    <Download size={13} aria-hidden="true" /> 保存文件
                  </button>
                )}
                {transfer.cachedDownloadUrl && (
                  <a
                    href={transfer.cachedDownloadUrl}
                    download={transfer.fileName}
                    className="btn btn-primary btn-sm"
                    style={{ textDecoration: 'none' }}
                    title="从房间服务器限时缓存直接下载此历史附件（支持断点续传）"
                  >
                    <Download size={13} aria-hidden="true" /> 下载附件
                  </a>
                )}
              </>
            )}
          </>
        )}

        {transfer.status === 'rejected' && (
          <span className="pill pill-neutral">
            <XCircle size={12} aria-hidden="true" /> 已婉拒
          </span>
        )}
        {transfer.status === 'cancelled' && (
          <>
            <span className="pill pill-neutral">
              <XCircle size={12} aria-hidden="true" /> 已取消
            </span>
            {transfer.direction === 'receive' &&
              transfer.resumeKey &&
              transfer.targetPeerId &&
              onRequestResume && (
                <button
                  type="button"
                  className="btn btn-soft btn-sm"
                  onClick={() => onRequestResume(transfer.targetPeerId!, transfer)}
                >
                  <HardDriveDownload size={13} aria-hidden="true" /> 请求对方续传
                </button>
              )}
          </>
        )}
        {transfer.status === 'error' && (
          <>
            <span className="pill pill-danger">
              <XCircle size={12} aria-hidden="true" /> {transfer.errorMessage || '传输异常'}
            </span>
            {transfer.direction === 'receive' &&
              transfer.resumeKey &&
              transfer.targetPeerId &&
              onRequestResume && (
                <button
                  type="button"
                  className="btn btn-soft btn-sm"
                  onClick={() => onRequestResume(transfer.targetPeerId!, transfer)}
                >
                  <HardDriveDownload size={13} aria-hidden="true" /> 请求对方续传
                </button>
              )}
          </>
        )}
      </div>
    </div>
  );
};

function extractExtension(fileName: string): string {
  const idx = fileName.lastIndexOf('.');
  if (idx <= 0 || idx === fileName.length - 1) return '';
  return fileName.slice(idx + 1, idx + 5).toUpperCase();
}

// 将文件名拆分为主干与扩展名，保护长文件名截断时扩展名始终完整可见
function splitFileNameParts(fileName: string): { stem: string; extPart: string } {
  const idx = fileName.lastIndexOf('.');
  if (idx > 0 && idx < fileName.length - 1) {
    const ext = fileName.slice(idx);
    if (ext.length <= 10 && !ext.includes(' ')) {
      return { stem: fileName.slice(0, idx), extPart: ext };
    }
  }
  if (fileName.length > 26) {
    return { stem: fileName.slice(0, 14), extPart: `…${fileName.slice(-8)}` };
  }
  return { stem: fileName, extPart: '' };
}

// 按扩展名与 MIME 类型映射语义色块与图标
function resolveFileTypeMeta(
  fileName: string,
  mimeType: string
): { categoryClass: string; icon: React.ReactNode } {
  const ext = extractExtension(fileName).toLowerCase();
  const mime = (mimeType || '').toLowerCase();

  if (ext === 'pdf' || mime === 'application/pdf') {
    return { categoryClass: 'file-kind-pdf', icon: <FileText size={18} aria-hidden="true" /> };
  }
  if (['xls', 'xlsx', 'csv', 'ods'].includes(ext) || mime.includes('spreadsheet') || mime.includes('excel')) {
    return { categoryClass: 'file-kind-sheet', icon: <FileSpreadsheet size={18} aria-hidden="true" /> };
  }
  if (['doc', 'docx', 'txt', 'md', 'rtf', 'ppt', 'pptx', 'pages'].includes(ext) || mime.startsWith('text/')) {
    return { categoryClass: 'file-kind-doc', icon: <FileText size={18} aria-hidden="true" /> };
  }
  if (['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz'].includes(ext) || mime.includes('zip') || mime.includes('archive') || mime.includes('compressed')) {
    return { categoryClass: 'file-kind-archive', icon: <FileArchive size={18} aria-hidden="true" /> };
  }
  if (['apk', 'ipa', 'exe', 'msi', 'dmg', 'pkg', 'deb'].includes(ext) || mime.includes('android.package')) {
    return { categoryClass: 'file-kind-app', icon: <Smartphone size={18} aria-hidden="true" /> };
  }
  if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'heic', 'bmp'].includes(ext) || mime.startsWith('image/')) {
    return { categoryClass: 'file-kind-image', icon: <FileImage size={18} aria-hidden="true" /> };
  }
  if (['mp4', 'mov', 'mkv', 'avi', 'webm', 'flv'].includes(ext) || mime.startsWith('video/')) {
    return { categoryClass: 'file-kind-video', icon: <FileVideo size={18} aria-hidden="true" /> };
  }
  if (['mp3', 'wav', 'flac', 'aac', 'm4a', 'ogg'].includes(ext) || mime.startsWith('audio/')) {
    return { categoryClass: 'file-kind-audio', icon: <FileAudio size={18} aria-hidden="true" /> };
  }
  if (['js', 'ts', 'tsx', 'jsx', 'go', 'py', 'java', 'kt', 'rs', 'json', 'yaml', 'yml', 'html', 'css', 'sql', 'sh'].includes(ext)) {
    return { categoryClass: 'file-kind-code', icon: <FileCode2 size={18} aria-hidden="true" /> };
  }
  return { categoryClass: 'file-kind-other', icon: <FileIcon size={18} aria-hidden="true" /> };
}

function isSameCalendarDay(tsA: number, tsB: number): boolean {
  const a = new Date(tsA);
  const b = new Date(tsB);
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}

// 超过 5 分钟或跨自然日时展示居中时间胶囊
function shouldShowTimeDivider(prev: ChatItem | undefined, curr: ChatItem): boolean {
  if (!prev) return true;
  if (curr.timestamp - prev.timestamp >= TIME_DIVIDER_GAP_MS) return true;
  return !isSameCalendarDay(prev.timestamp, curr.timestamp);
}

// 按微信对齐规则格式化时间胶囊文案
function formatMessageDividerTime(ts: number): string {
  const d = new Date(ts);
  const now = new Date();
  const clock = formatClock(ts);

  const msgDayStart = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const diffDays = Math.round((todayStart - msgDayStart) / 86_400_000);

  if (diffDays === 0) return clock;
  if (diffDays === 1) return `昨天 ${clock}`;
  if (diffDays >= 2 && diffDays < 7) return `${WEEKDAY_NAMES[d.getDay()]} ${clock}`;
  if (d.getFullYear() === now.getFullYear()) {
    return `${d.getMonth() + 1}月${d.getDate()}日 ${clock}`;
  }
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 ${clock}`;
}

function renderTextWithLinks(text: string): React.ReactNode {
  const parts = text.split(URL_REGEX);
  return parts.map((part, idx) => {
    if (part.startsWith('http://') || part.startsWith('https://')) {
      return (
        <a key={idx} href={part} target="_blank" rel="noopener noreferrer">
          {part}
        </a>
      );
    }
    return <React.Fragment key={idx}>{part}</React.Fragment>;
  });
}

function formatClock(ts: number): string {
  const d = new Date(ts);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}
