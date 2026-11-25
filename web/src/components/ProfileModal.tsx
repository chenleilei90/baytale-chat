import React, { useEffect, useRef, useState } from 'react';
import { Upload, X, Sparkles, Check } from 'lucide-react';
import type { UserProfile } from '../types';
import { compressAvatarFile, generateRandomName, PRESET_AVATARS } from '../lib/avatar';
import { Avatar } from './Avatar';

interface ProfileModalProps {
  profile: UserProfile;
  onSave: (next: UserProfile) => void;
  onClose: () => void;
}

// 精工个人名片定制弹窗：支持自定义昵称、上传/粘贴图片裁剪压缩、12 款渐变预设徽章
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
      <form className="modal-sheet" onClick={(e) => e.stopPropagation()} onSubmit={handleSubmit}>
        <div className="modal-head">
          <h3>个人身份名片</h3>
          <button type="button" className="btn btn-ghost btn-icon" onClick={onClose}>
            <X size={16} />
          </button>
        </div>

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 15,
            padding: 14,
            borderRadius: 16,
            background: 'var(--glass-subtle)',
            border: '1px solid var(--line-border)',
          }}
        >
          <Avatar name={name || '访客'} avatar={avatar} size={60} />
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 7 }}>
            <div style={{ display: 'flex', gap: 7, flexWrap: 'wrap' }}>
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={() => fileInputRef.current?.click()}
                disabled={uploading}
              >
                <Upload size={13} />
                {uploading ? '处理中...' : '上传图片'}
              </button>
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={() => setName(generateRandomName())}
              >
                <Sparkles size={13} />
                随机昵称
              </button>
            </div>
            <span style={{ fontSize: 11.5, color: 'var(--text-muted)', lineHeight: 1.4 }}>
              支持本地图片或直接 Ctrl+V 粘贴头像，自动居中裁剪并同步给房间成员
            </span>
          </div>
          <input
            ref={fileInputRef}
            type="file"
            accept="image/*"
            style={{ display: 'none' }}
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
                >
                  <Avatar name={item.label} avatar={id} size={36} />
                </button>
              );
            })}
          </div>
        </div>

        {error && <div className="alert-strip">{error}</div>}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <button type="button" className="btn btn-secondary" onClick={onClose}>
            取消
          </button>
          <button type="submit" className="btn btn-primary">
            <Check size={15} />
            保存修改
          </button>
        </div>
      </form>
    </div>
  );
};
