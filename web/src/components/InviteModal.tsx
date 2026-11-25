import React, { useEffect, useState } from 'react';
import { Copy, Check, X, RefreshCw, Flame, Hash, Lock } from 'lucide-react';
import QRCode from 'qrcode';
import type { RoomSummary } from '../types';
import { copyTextToClipboard } from '../lib/platform';

interface InviteModalProps {
  room: RoomSummary;
  onClose: () => void;
  onRotateCode?: () => void;
  rotating?: boolean;
}

// 房间邀请弹窗：离线高清二维码 + 大字距房间码 + 一键复制链接
export const InviteModal: React.FC<InviteModalProps> = ({
  room,
  onClose,
  onRotateCode,
  rotating,
}) => {
  const [qrDataUrl, setQrDataUrl] = useState('');
  const [copiedType, setCopiedType] = useState<'code' | 'link' | null>(null);

  const inviteUrl = `${window.location.origin}/?room=${encodeURIComponent(room.code)}`;

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  useEffect(() => {
    let active = true;
    QRCode.toDataURL(inviteUrl, {
      width: 220,
      margin: 1,
      color: { dark: '#000000', light: '#ffffff' },
    })
      .then((url) => {
        if (active) setQrDataUrl(url);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [inviteUrl]);

  const handleCopy = async (text: string, type: 'code' | 'link') => {
    if (await copyTextToClipboard(text)) {
      setCopiedType(type);
      setTimeout(() => setCopiedType(null), 1600);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal-sheet modal-sheet-md"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label="分享房间邀请"
      >
        <div className="modal-head">
          <h3>分享房间邀请</h3>
          <button
            type="button"
            className="btn btn-ghost btn-icon modal-close-btn"
            aria-label="关闭弹窗"
            onClick={onClose}
          >
            <X size={16} aria-hidden="true" />
          </button>
        </div>

        <div className="modal-card-panel">
          <div className="modal-pills-row">
            {room.mode === 'fixed' ? (
              <span className="pill pill-accent">
                <Hash size={12} aria-hidden="true" /> 常驻固定房间
              </span>
            ) : (
              <span className="pill pill-warm">
                <Flame size={12} aria-hidden="true" /> 临时房间 · 离开后销毁
              </span>
            )}
            {room.hasPassword && (
              <span className="pill pill-neutral">
                <Lock size={11} aria-hidden="true" /> 需访问密码
              </span>
            )}
          </div>

          {qrDataUrl && (
            <div className="qr-quiet-frame">
              <img src={qrDataUrl} alt={`房间 ${room.code} 的邀请二维码`} className="qr-img-invite" />
            </div>
          )}

          <div className="modal-code-box">
            <div className="radar-code-display mono modal-code-md">
              {room.code}
            </div>
            <div className="modal-sub-caption">
              扫码或输入上方房间码建立点对点直连
            </div>
          </div>
        </div>

        <div className="lobby-two-btn-grid">
          <button
            type="button"
            className="btn btn-secondary lobby-touch-pill"
            onClick={() => void handleCopy(room.code, 'code')}
          >
            {copiedType === 'code' ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
            {copiedType === 'code' ? '已复制' : '复制房间码'}
          </button>
          <button
            type="button"
            className="btn btn-primary lobby-touch-pill"
            onClick={() => void handleCopy(inviteUrl, 'link')}
          >
            {copiedType === 'link' ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
            {copiedType === 'link' ? '已复制' : '复制邀请链接'}
          </button>
        </div>

        {room.mode === 'ephemeral' && onRotateCode && (
          <button
            type="button"
            className="btn btn-ghost btn-sm lobby-touch-pill"
            onClick={onRotateCode}
            disabled={rotating}
          >
            <RefreshCw size={13} aria-hidden="true" />
            {rotating ? '正在切换新房间码...' : '作废当前码并换一个新房间码'}
          </button>
        )}
      </div>
    </div>
  );
};
