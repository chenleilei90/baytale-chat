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
  onReactMessage: (msgId: string, emoji: string) => void;
  onReplyMessage: (item: ChatItem) => void;
  onRecallMessage: (msgId: string) => void;
  onAcceptFile: (
    peerId: string,
    transferId: string,
    mode: 'memory' | 'disk' | 'resume',
    fileName: string,
    fileSize: number
  ) => void;
  onRejectFile: (peerId: string, transferId: string) => void;
  onCancelTransfer: (peerId: string, transferId: string) => void;
  onPreviewImage: (src: string, fileName?: string, caption?: string) => void;
}

const URL_REGEX = /(https?:\/\/[^\s<>"']+)/g;
const REACTION_EMOJIS = ['👍', '❤️', '😂', '🔥', '🎉', '👀'];

// 灵动消息流：防窥流光刮刮卡密信、悬停贴纸回应/引用/撤回、拍立得图卡与果冻文件卡
export const MessageList: React.FC<MessageListProps> = ({
  items,
  onReactMessage,
  onReplyMessage,
  onRecallMessage,
  onAcceptFile,
  onRejectFile,
  onCancelTransfer,
  onPreviewImage,
}) => {
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [revealedIds, setRevealedIds] = useState<Record<string, boolean>>({});
  const [activeActionId, setActiveActionId] = useState<string | null>(null);
  const revealTimersRef = useRef<Record<string, number>>({});
  const canDirectDisk = supportsDirectDiskSave();

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
      {items.map((item) => {
        if (item.kind === 'system') {
          return (
            <div key={item.id} className="sys-pill">
              {item.text}
            </div>
          );
        }

        const timeLabel = formatClock(item.timestamp);
        const reactionEntries = Object.entries(item.reactions || {}).filter(
          ([, users]) => users.length > 0
        );
        const isSecretHidden = Boolean(item.isSecret && !revealedIds[item.id]);

        return (
          <div key={item.id} className={`msg-item ${item.isSelf ? 'self' : 'other'}`}>
            <Avatar name={item.senderName} avatar={item.senderAvatar} size={36} />
            <div className="msg-stack">
              <div className="msg-header">
                <span style={{ fontWeight: 700, color: 'var(--text-sub)' }}>
                  {item.isSelf ? `${item.senderName}（我）` : item.senderName}
                </span>
                <span className="mono">{timeLabel}</span>
                {item.isSecret && (
                  <span style={{ color: 'var(--accent)', fontWeight: 700, display: 'inline-flex', alignItems: 'center', gap: 2 }}>
                    <EyeOff size={11} /> 密信
                  </span>
                )}
                {item.fromCache && (
                  <span
                    title="恢复自服务器缓存"
                    style={{ display: 'inline-flex', alignItems: 'center', gap: 2 }}
                  >
                    <Cloud size={11} /> 缓存
                  </span>
                )}
                {item.kind === 'text' && item.text && !isSecretHidden && (
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm"
                    style={{ height: 20, padding: '0 6px', fontSize: 11 }}
                    onClick={() => void handleCopyText(item.id, item.text || '')}
                  >
                    {copiedId === item.id ? <Check size={11} /> : <Copy size={11} />}
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
                  {REACTION_EMOJIS.map((em) => (
                    <button
                      key={em}
                      type="button"
                      className="react-pick-btn"
                      onClick={() => {
                        onReactMessage(item.id, em);
                        setActiveActionId(null);
                      }}
                      title={`回应 ${em}`}
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
                  >
                    <Reply size={13} />
                  </button>
                  {item.isSelf && item.kind !== 'file' && (
                    <button
                      type="button"
                      className="react-action-btn danger"
                      onClick={() => {
                        onRecallMessage(item.id);
                        setActiveActionId(null);
                      }}
                      title="撤回此消息（同步删除服务器缓存）"
                    >
                      <Undo2 size={13} />
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
                        <Sparkles size={15} />
                        <span>防窥刮刮卡 · 点击揭晓</span>
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
                  <DopamineFileCard
                    transfer={item.transfer}
                    canDirectDisk={canDirectDisk}
                    onAccept={onAcceptFile}
                    onReject={onRejectFile}
                    onCancel={onCancelTransfer}
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
                    >
                      <span>{em}</span>
                      <span className="mono">{users.length}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>
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

      <div style={{ padding: '7px 6px 0', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, fontSize: 12, color: 'var(--text-sub)' }}>
        <div style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', display: 'flex', alignItems: 'center', gap: 5 }}>
          {item.isScreenshot && (
            <span className="pill pill-accent" style={{ height: 20, padding: '0 7px', fontSize: 11 }}>
              <Camera size={10} /> 截图
            </span>
          )}
          <span style={{ fontWeight: 600 }}>{item.caption || tr?.fileName || '精选图片'}</span>
        </div>
        {imgSrc && (
          <button
            type="button"
            className="btn btn-ghost btn-icon"
            style={{ width: 26, height: 26 }}
            title="保存原图"
            onClick={() =>
              triggerBlobDownload(imgSrc, tr?.fileName || `baychat-${item.timestamp}.png`)
            }
          >
            <Download size={13} />
          </button>
        )}
      </div>
    </div>
  );
};

const VoiceNoteCard: React.FC<{ item: ChatItem }> = ({ item }) => {
  const src = item.audioUrl || item.transfer?.objectUrl;
  return (
    <div className="voice-bubble-card">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 6 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="pill pill-accent" style={{ height: 22, padding: '0 8px', fontSize: 11 }}>
            <Mic size={11} /> 语音对讲
          </span>
          {item.caption && (
            <span className="mono" style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-sub)' }}>
              {item.caption}
            </span>
          )}
        </div>
        {src && (
          <button
            type="button"
            className="btn btn-ghost btn-icon"
            style={{ width: 24, height: 24 }}
            title="保存语音文件"
            onClick={() =>
              triggerBlobDownload(src, item.transfer?.fileName || `voice-${item.timestamp}.webm`)
            }
          >
            <Download size={12} />
          </button>
        )}
      </div>
      {src ? (
        <audio controls preload="metadata" src={src} style={{ width: 250, maxWidth: '68vw', height: 36 }} />
      ) : (
        <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>正在接收语音消息...</div>
      )}
    </div>
  );
};

function transferIdOf(tr: FileTransferInfo): string {
  return tr.transferId;
}

const DopamineFileCard: React.FC<{
  transfer: FileTransferInfo;
  canDirectDisk: boolean;
  onAccept: (
    peerId: string,
    transferId: string,
    mode: 'memory' | 'disk' | 'resume',
    fileName: string,
    fileSize: number
  ) => void;
  onReject: (peerId: string, transferId: string) => void;
  onCancel: (peerId: string, transferId: string) => void;
}> = ({ transfer, canDirectDisk, onAccept, onReject, onCancel }) => {
  const rawPct =
    transfer.fileSize > 0
      ? Math.min(100, (transfer.transferredBytes / transfer.fileSize) * 100)
      : 100;
  // 传输中给予最小 2% 可见填充条，避免 8GB+ 超大文件前几十 MB 看起来像空白槽
  const barPct = transfer.status === 'transferring' ? Math.max(2, rawPct) : rawPct;
  const pctLabel = rawPct > 0 && rawPct < 10 ? rawPct.toFixed(1) : String(Math.round(rawPct));
  const ext = extractExtension(transfer.fileName);
  const isLargeFile = transfer.fileSize >= 500 * 1024 * 1024;
  const hasCachedResume = Boolean(transfer.canResumeBytes && transfer.canResumeBytes > 0);

  return (
    <div className="file-bento-card">
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <div className="ext-jelly-box">
          {ext ? <span>{ext}</span> : <FileIcon size={18} />}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 14, fontWeight: 700, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={transfer.fileName}>
            {transfer.fileName}
          </div>
          <div className="mono" style={{ fontSize: 11.5, color: 'var(--text-muted)', marginTop: 2 }}>
            {formatBytes(transfer.fileSize)} ·{' '}
            {transfer.direction === 'send'
              ? transfer.targetPeerName
                ? `直发给 ${transfer.targetPeerName}`
                : '高速直发'
              : '点对点接收'}
          </div>
        </div>
      </div>

      {(transfer.status === 'transferring' || transfer.status === 'waiting_accept') && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
          <div className="progress-rail">
            <div className="progress-bar" style={{ width: `${barPct}%` }} />
          </div>
          <div className="mono" style={{ display: 'flex', justifyContent: 'space-between', gap: 6, fontSize: 11, color: 'var(--text-muted)', flexWrap: 'wrap' }}>
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
            <div style={{ fontSize: 11, color: 'var(--accent)', fontWeight: 600 }}>
              ⚡ 已从 {formatBytes(transfer.resumedFromBytes)} 断点自动续传
            </div>
          )}
        </div>
      )}

      <div style={{ display: 'flex', alignItems: 'center', gap: 7, flexWrap: 'wrap' }}>
        {transfer.direction === 'receive' && transfer.status === 'offering' && transfer.targetPeerId && (
          <>
            {canDirectDisk && hasCachedResume && (
              <button
                type="button"
                className="btn btn-jelly btn-sm"
                onClick={() =>
                  onAccept(
                    transfer.targetPeerId!,
                    transfer.transferId,
                    'resume',
                    transfer.fileName,
                    transfer.fileSize
                  )
                }
              >
                <HardDriveDownload size={13} /> 断点续传 (已收 {formatBytes(transfer.canResumeBytes!)})
              </button>
            )}
            {canDirectDisk && (
              <button
                type="button"
                className={`btn btn-sm ${hasCachedResume ? 'btn-glass' : 'btn-jelly'}`}
                onClick={() =>
                  onAccept(
                    transfer.targetPeerId!,
                    transfer.transferId,
                    'disk',
                    transfer.fileName,
                    transfer.fileSize
                  )
                }
                title="边收边直接写入电脑硬盘，传几十 GB 也不占内存"
              >
                <HardDriveDownload size={13} /> {isLargeFile ? '直写硬盘 (推荐)' : '直写硬盘'}
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
                    transfer.fileSize
                  )
                }
                title="选择硬盘里上次中断未下完的半成品文件，从断点接着传"
              >
                续传半成品
              </button>
            )}
            <button
              type="button"
              className={`btn btn-sm ${canDirectDisk ? 'btn-glass' : 'btn-jelly'}`}
              onClick={() =>
                onAccept(
                  transfer.targetPeerId!,
                  transfer.transferId,
                  'memory',
                  transfer.fileName,
                  transfer.fileSize
                )
              }
              title={isLargeFile ? '超大文件不建议内存接收，容易爆浏览器内存，建议点「直写硬盘」' : '先收到浏览器内存再保存'}
            >
              <Download size={13} /> {isLargeFile && canDirectDisk ? '内存接收' : '接收'}
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
            <span className="pill pill-emerald">
              <CheckCircle2 size={12} />
              {transfer.verified ? 'SHA-256 满分校验' : '传输完成'}
            </span>
            {transfer.direction === 'receive' && transfer.savedToDisk && (
              <span style={{ fontSize: 11.5, color: 'var(--text-muted)' }}>已写入本地磁盘</span>
            )}
            {transfer.direction === 'receive' && transfer.objectUrl && !transfer.savedToDisk && (
              <button
                type="button"
                className="btn btn-soft btn-sm"
                onClick={() => triggerBlobDownload(transfer.objectUrl!, transfer.fileName)}
              >
                <Download size={13} /> 保存文件
              </button>
            )}
          </>
        )}

        {transfer.status === 'rejected' && (
          <span className="pill pill-neutral">
            <XCircle size={12} /> 已婉拒
          </span>
        )}
        {transfer.status === 'cancelled' && (
          <span className="pill pill-neutral">
            <XCircle size={12} /> 已取消
          </span>
        )}
        {transfer.status === 'error' && (
          <span className="pill" style={{ background: 'rgba(244,63,94,0.14)', color: '#e11d48' }}>
            <XCircle size={12} /> {transfer.errorMessage || '传输异常'}
          </span>
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
