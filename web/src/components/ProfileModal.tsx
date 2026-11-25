import React, { useEffect, useRef, useState } from 'react';
import { Upload, X, Dices, Check } from 'lucide-react';
import type { UserProfile } from '../types';
import { compressAvatarFile, generateRandomName, PRESET_AVATARS } from '../lib/avatar';
import { Avatar } from './Avatar';

interface ProfileModalProps {
  profile: UserProfile;
  onSave: (next: UserProfile) => void;
  onClose: () => void;
}

// 个人名片定制弹窗：支持自定义昵称、上传/粘贴图片裁剪压缩、12 款纯色主题预设徽章
export const ProfileModal: React.FC<ProfileModalProps> = ({ profile, onSave, onClose }) => {
  const [name, setName] = useState(profile.name);
  const [avatar, setAvatar] = useState(profile.avatar);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const handleFileChange = async (file?: File | null) => {
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      setError('请选择 PNG / JPG / WebP / GIF 格式图片');
      return;
    }
    setError('');
    setUploading(true);
    try {
      const dataUrl = await compressAvatarFile(file);
      setAvatar(dataUrl);
    } catch (err) {
      setError(err instanceof Error ? err.message : '头像处理失败');
    } finally {
      setUploading(false);
    }
  };

  const handlePaste = (e: React.ClipboardEvent) => {
    const items = e.clipboardData?.items;
    if (!items) return;
    for (let i = 0; i < items.length; i++) {
      if (items[i].type.startsWith('image/')) {
        const file = items[i].getAsFile();
        if (file) {
          e.preventDefault();
          void handleFileChange(file);
          break;
        }
      }
    }
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) {
      setError('请输入显示昵称');
      return;
    }
    onSave({
      ...profile,
      name: trimmed.slice(0, 24),
      avatar,
    });
    onClose();
  };

  return (
    <div className="modal-overlay" onClick={onClose} onPaste={handlePaste}>
      <form
        className="modal-sheet"
        onClick={(e) => e.stopPropagation()}
        onSubmit={handleSubmit}
        role="dialog"
        aria-modal="true"
        aria-label="个人身份名片"
      >
        <div className="modal-head">
          <h3>个人身份名片</h3>
          <button
            type="button"
            className="btn btn-ghost btn-icon modal-close-btn"
            aria-label="关闭弹窗"
            onClick={onClose}
          >
            <X size={16} aria-hidden="true" />
          </button>
        </div>

        <div className="profile-preview-card">
          <Avatar name={name || '访客'} avatar={avatar} size={60} />
          <div className="profile-preview-actions">
            <div className="profile-btn-row">
              <button
                type="button"
                className="btn btn-secondary btn-sm lobby-touch-pill"
                onClick={() => fileInputRef.current?.click()}
                disabled={uploading}
              >
                <Upload size={13} aria-hidden="true" />
                {uploading ? '处理中...' : '上传图片'}
              </button>
              <button
                type="button"
                className="btn btn-ghost btn-sm lobby-touch-pill"
                onClick={() => setName(generateRandomName())}
              >
                <Dices size={13} aria-hidden="true" />
                随机昵称
              </button>
            </div>
            <span className="profile-hint-text">
              支持本地图片或直接 Ctrl+V 粘贴头像，自动居中裁剪并同步给房间成员
            </span>
          </div>
          <input
            ref={fileInputRef}
            type="file"
            accept="image/*"
            hidden
            onChange={(e) => {
              void handleFileChange(e.target.files?.[0]);
              e.target.value = '';
            }}
          />
        </div>

        <div className="config-block">
          <div className="config-label-row">
            <span>显示姓名</span>
            <span className="config-caption mono">{name.trim().length}/24</span>
          </div>
          <input
            className="field-input"
            value={name}
            maxLength={24}
            onChange={(e) => setName(e.target.value)}
            placeholder="输入你的昵称或姓名..."
            autoFocus
          />
        </div>

        <div className="config-block">
          <div className="config-label-row">
            <span>预设徽章头像</span>
          </div>
          <div className="avatar-picker-grid">
            {PRESET_AVATARS.map((item) => {
              const id = `preset:${item.id}`;
              return (
                <button
                  key={item.id}
                  type="button"
                  className={`avatar-cell ${avatar === id ? 'active' : ''}`}
                  onClick={() => setAvatar(id)}
                  title={item.label}
                  aria-label={`选择预设头像：${item.label}`}
                >
                  <Avatar name={item.label} avatar={id} size={36} />
                </button>
              );
            })}
          </div>
        </div>

        {error && <div className="alert-strip">{error}</div>}

        <div className="modal-actions-end">
          <button type="button" className="btn btn-secondary lobby-touch-pill" onClick={onClose}>
            取消
          </button>
          <button type="submit" className="btn btn-primary lobby-touch-pill">
            <Check size={15} aria-hidden="true" />
            保存修改
          </button>
        </div>
      </form>
    </div>
  );
};
