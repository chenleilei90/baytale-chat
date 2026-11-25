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
    <div className="modal-overlay lightbox-overlay" onClick={onClose}>
      <div className="lightbox-panel" onClick={(e) => e.stopPropagation()}>
        <div className="lightbox-actions">
          <button
            type="button"
            className="btn btn-primary btn-sm"
            aria-label="下载原图"
            onClick={() => triggerBlobDownload(src, fileName || `baychat-${Date.now()}.png`)}
          >
            <Download size={13} aria-hidden="true" />
            下载原图
          </button>
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            aria-label="关闭预览"
            onClick={onClose}
          >
            <X size={14} aria-hidden="true" />
            关闭
          </button>
        </div>

        <img
          src={src}
          alt={caption || fileName || '预览图片'}
          className="lightbox-img"
        />

        {caption && (
          <div className="lightbox-caption">
            {caption}
          </div>
        )}
      </div>
    </div>
  );
};
