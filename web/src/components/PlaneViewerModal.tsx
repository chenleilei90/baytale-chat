import React, { useState } from 'react';
import {
  Archive,
  ArrowRight,
  File,
  FileText,
  Film,
  Flag,
  Image as ImageIcon,
  KeyRound,
  Lock,
  Music,
  Smartphone,
  X,
} from 'lucide-react';
import type { PaperPlane, PlaneFileKind } from '../types';
import { formatBytes, formatRelativeTime } from '../lib/platform';
import { formatPlaneKindLabel } from '../lib/planeKind';
import { Avatar } from './Avatar';

interface PlaneViewerModalProps {
  plane: PaperPlane;
  joining: boolean;
  onJoinPlane: (plane: PaperPlane) => void;
  onReportPlane: (plane: PaperPlane) => void;
  onClose: () => void;
}

export function renderPlaneKindIcon(kind: PlaneFileKind, size = 16): React.ReactNode {
  switch (kind) {
    case 'apk':
      return <Smartphone size={size} aria-hidden="true" />;
    case 'image':
      return <ImageIcon size={size} aria-hidden="true" />;
    case 'video':
      return <Film size={size} aria-hidden="true" />;
    case 'audio':
      return <Music size={size} aria-hidden="true" />;
    case 'archive':
      return <Archive size={size} aria-hidden="true" />;
    case 'doc':
      return <FileText size={size} aria-hidden="true" />;
    default:
      return <File size={size} aria-hidden="true" />;
  }
}

// 纸飞机详情预览窗：以待接收文件清单为主体 + 发送者会话预览气泡
export const PlaneViewerModal: React.FC<PlaneViewerModalProps> = ({
  plane,
  joining,
  onJoinPlane,
  onReportPlane,
  onClose,
}) => {
  const [reporting, setReporting] = useState(false);

  const handleReportClick = () => {
    if (reporting) return;
    setReporting(true);
    onReportPlane(plane);
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal-sheet plane-viewer-sheet"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={`来自 ${plane.senderName} 的纸飞机`}
      >
        <div className="plane-viewer-head">
          <div className="plane-viewer-sender">
            <Avatar name={plane.senderName} avatar={plane.senderAvatar} size={40} />
            <div className="plane-viewer-sender-meta">
              <div className="plane-viewer-sender-row">
                <span className="plane-viewer-sender-name">{plane.senderName}</span>
                <span className="pill pill-neutral mono plane-code-pill">#{plane.roomCode}</span>
              </div>
              <div className="plane-viewer-time">
                放飞于 {formatRelativeTime(plane.createdAt)} · 仅含公开文件清单与留言
              </div>
            </div>
          </div>

          <div className="plane-viewer-head-actions">
            {plane.allowEntry ? (
              plane.hasPassword ? (
                <span className="pill pill-accent" title="房主已授权免密通过纸飞机直通进入">
                  <KeyRound size={12} aria-hidden="true" /> 免密进入
                </span>
              ) : (
                <span className="pill pill-emerald">可直接加入</span>
              )
            ) : (
              <span className="pill pill-neutral">仅展示</span>
            )}
            <button
              type="button"
              className="btn btn-ghost btn-icon modal-close-btn"
              style={{ width: 32, height: 32 }}
              onClick={handleReportClick}
              disabled={reporting}
              title="举报不当内容"
              aria-label="举报纸飞机"
            >
              <Flag size={14} aria-hidden="true" />
            </button>
            <button
              type="button"
              className="btn btn-ghost btn-icon modal-close-btn"
              style={{ width: 32, height: 32 }}
              onClick={onClose}
              aria-label="关闭纸飞机窗口"
              title="关闭"
            >
              <X size={16} aria-hidden="true" />
            </button>
          </div>
        </div>

        <div className="plane-session-box">
          {plane.files.length > 0 && (
            <div className="plane-file-lock-list">
              <div className="plane-section-caption">
                房间内可下载文件清单（{plane.files.length} 个）
              </div>
              {plane.files.map((file, idx) => (
                <div key={`${file.name}-${idx}`} className="plane-locked-file-row">
                  <div className="plane-locked-file-icon">
                    {renderPlaneKindIcon(file.kind, 17)}
                  </div>
                  <div className="plane-locked-file-info">
                    <div className="plane-locked-file-name" title={file.name}>
                      {file.name}
                    </div>
                    <div className="plane-locked-file-sub">
                      <span>{formatPlaneKindLabel(file.kind)}</span>
                      {file.size > 0 && (
                        <>
                          <span>·</span>
                          <span className="mono">{formatBytes(file.size)}</span>
                        </>
                      )}
                    </div>
                  </div>
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm plane-locked-btn"
                    disabled
                    title="加入房间后可下载"
                  >
                    <Lock size={12} aria-hidden="true" /> 待进房下载
                  </button>
                </div>
              ))}
            </div>
          )}

          {plane.message && (
            <div className="plane-chat-preview-row">
              <Avatar name={plane.senderName} avatar={plane.senderAvatar} size={28} />
              <div className="plane-chat-bubble">{plane.message}</div>
            </div>
          )}

          <div className="plane-system-hint">
            <Lock size={12} aria-hidden="true" />
            <span>
              {plane.allowEntry
                ? '加入房间即可下载文件和聊天'
                : '放飞者仅公开文件清单，未开放从纸飞机直接进房'}
            </span>
          </div>
        </div>

        <div className="plane-viewer-footer">
          <button type="button" className="btn btn-glass" onClick={onClose}>
            稍后再说
          </button>
          {plane.allowEntry ? (
            <button
              type="button"
              className="btn btn-jelly plane-join-cta"
              disabled={joining}
              onClick={() => onJoinPlane(plane)}
            >
              <span>{joining ? '正在加入房间...' : '加入房间下载并聊天'}</span>
              <ArrowRight size={15} aria-hidden="true" />
            </button>
          ) : (
            <button type="button" className="btn btn-soft" disabled>
              放飞者未开放进入
            </button>
          )}
        </div>
      </div>
    </div>
  );
};
