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

// 精工房间邀请卡弹窗：离线高清二维码 + 大字距房间码 + 一键复制链接
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
      color: { dark: '#111318', light: '#ffffff' },
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
        className="modal-sheet"
        onClick={(e) => e.stopPropagation()}
        style={{ maxWidth: 400 }}
      >
        <div className="modal-head">
          <h3>分享房间邀请</h3>
          <button type="button" className="btn btn-ghost btn-icon" onClick={onClose}>
            <X size={16} />
          </button>
        </div>

        <div
          style={{
            padding: '22px 18px',
            borderRadius: 20,
            background: 'var(--glass-subtle)',
            border: '1px solid var(--line-border)',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 14,
          }}
        >
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', justifyContent: 'center' }}>
            {room.mode === 'fixed' ? (
              <span className="pill pill-accent">
                <Hash size={12} /> 常驻固定房间
              </span>
            ) : (
              <span className="pill pill-warm">
                <Flame size={12} /> 闪传房间 · 聊完作废
              </span>
            )}
            {room.hasPassword && (
              <span className="pill pill-neutral">
                <Lock size={11} /> 需访问密码
              </span>
            )}
          </div>

          {qrDataUrl && (
            <div className="radar-qr-ring">
              <img src={qrDataUrl} alt="房间二维码" style={{ width: 172, height: 172 }} />
            </div>
          )}

          <div style={{ textAlign: 'center' }}>
            <div className="radar-code-display mono" style={{ fontSize: 28 }}>
              {room.code}
            </div>
            <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 4 }}>
              手机扫码或输入上方房间码建立点对点直连
            </div>
          </div>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => void handleCopy(room.code, 'code')}
          >
            {copiedType === 'code' ? <Check size={14} /> : <Copy size={14} />}
            {copiedType === 'code' ? '已复制' : '复制房间码'}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => void handleCopy(inviteUrl, 'link')}
          >
            {copiedType === 'link' ? <Check size={14} /> : <Copy size={14} />}
            {copiedType === 'link' ? '已复制' : '复制邀请链接'}
          </button>
        </div>

        {room.mode === 'ephemeral' && onRotateCode && (
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={onRotateCode}
            disabled={rotating}
          >
            <RefreshCw size={13} />
            {rotating ? '正在切换新房间码...' : '作废当前码并换一个新房间码'}
          </button>
        )}
      </div>
    </div>
  );
};
