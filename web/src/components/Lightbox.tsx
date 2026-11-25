import React, { useEffect } from 'react';
import { X, Download } from 'lucide-react';
import { triggerBlobDownload } from '../lib/platform';

interface LightboxProps {
  src: string;
  fileName?: string;
  caption?: string;
  onClose: () => void;
}

// 沉浸式图片全屏预览灯箱（支持 Esc 快捷关闭与原图保存）
export const Lightbox: React.FC<LightboxProps> = ({ src, fileName, caption, onClose }) => {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="modal-overlay" onClick={onClose} style={{ zIndex: 160 }}>
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          maxWidth: '94vw',
          maxHeight: '92vh',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: 12,
        }}
      >
        <div style={{ display: 'flex', gap: 8, alignSelf: 'flex-end' }}>
          <button
            type="button"
            className="btn btn-primary btn-sm"
            onClick={() => triggerBlobDownload(src, fileName || `baychat-${Date.now()}.png`)}
          >
            <Download size={13} />
            下载原图
          </button>
          <button type="button" className="btn btn-secondary btn-sm" onClick={onClose}>
            <X size={14} />
            关闭
          </button>
        </div>

        <img
          src={src}
          alt={caption || fileName || '预览图片'}
          style={{
            maxWidth: '92vw',
            maxHeight: '80vh',
            objectFit: 'contain',
            borderRadius: 14,
            boxShadow: '0 28px 64px rgba(0, 0, 0, 0.65)',
            background: '#090a0f',
          }}
        />

        {caption && (
          <div
            style={{
              color: '#f3f4f6',
              fontSize: 13,
              background: 'rgba(17, 19, 28, 0.82)',
              border: '1px solid rgba(255, 255, 255, 0.1)',
              padding: '6px 14px',
              borderRadius: 99,
            }}
          >
            {caption}
          </div>
        )}
      </div>
    </div>
  );
};
