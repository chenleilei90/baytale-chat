import React from 'react';
import { parsePresetAvatar } from '../lib/avatar';

interface AvatarProps {
  name: string;
  avatar?: string;
  size?: number;
}

// 统一头像渲染组件：支持用户上传图片、预设渐变图标与昵称首字兜底
export const Avatar: React.FC<AvatarProps> = ({ name, avatar, size = 36 }) => {
  const isImage = Boolean(avatar && avatar.startsWith('data:image/'));
  const preset = !isImage ? parsePresetAvatar(avatar) : null;
  const initial = (name || '访').trim().slice(0, 1).toUpperCase();

  if (isImage && avatar) {
    return (
      <span className="avatar" style={{ width: size, height: size }}>
        <img src={avatar} alt={name} />
      </span>
    );
  }

  return (
    <span
      className="avatar"
      style={{
        width: size,
        height: size,
        fontSize: Math.round(size * 0.48),
        background: preset?.gradient || 'linear-gradient(135deg, #3b82f6, #06b6d4)',
      }}
    >
      {preset ? preset.emoji : initial}
    </span>
  );
};
