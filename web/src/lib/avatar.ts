// 预设头像库与本地图片裁剪压缩工具（无需后端图床，轻量同步给房间所有成员）

export interface PresetAvatar {
  id: string;
  label: string;
  emoji: string;
  gradient: string;
}

export const PRESET_AVATARS: PresetAvatar[] = [
  { id: 'aurora', label: '极光蓝', emoji: '🐋', gradient: 'linear-gradient(135deg, #3b82f6, #06b6d4)' },
  { id: 'sunset', label: '晚霞橙', emoji: '🦊', gradient: 'linear-gradient(135deg, #f97316, #ec4899)' },
  { id: 'forest', label: '青翠绿', emoji: '🌿', gradient: 'linear-gradient(135deg, #10b981, #14b8a6)' },
  { id: 'violet', label: '星云紫', emoji: '🚀', gradient: 'linear-gradient(135deg, #8b5cf6, #6366f1)' },
  { id: 'amber', label: '琥珀金', emoji: '⚡', gradient: 'linear-gradient(135deg, #f59e0b, #ef4444)' },
  { id: 'sakura', label: '樱花粉', emoji: '🌸', gradient: 'linear-gradient(135deg, #f472b6, #a855f7)' },
  { id: 'ocean', label: '深海青', emoji: '🐬', gradient: 'linear-gradient(135deg, #0284c7, #2563eb)' },
  { id: 'mint', label: '薄荷冰', emoji: '🧊', gradient: 'linear-gradient(135deg, #22d3ee, #34d399)' },
  { id: 'cat', label: '橘猫', emoji: '🐱', gradient: 'linear-gradient(135deg, #fb923c, #facc15)' },
  { id: 'panda', label: '熊猫', emoji: '🐼', gradient: 'linear-gradient(135deg, #475569, #94a3b8)' },
  { id: 'owl', label: '夜猫子', emoji: '🦉', gradient: 'linear-gradient(135deg, #4f46e5, #0ea5e9)' },
  { id: 'robot', label: '机械师', emoji: '🤖', gradient: 'linear-gradient(135deg, #0f766e, #0284c7)' },
];

const ADJECTIVES = ['清风', '星海', '极光', '青云', '暮雨', '朝霞', '微光', '远航', '琥珀', '知秋'];
const NOUNS = ['旅人', '信使', '节点', '风铃', '海豚', '灯塔', '飞鸟', '云雀', '行者', '极客'];

// 生成随机初始昵称
export function generateRandomName(): string {
  const a = ADJECTIVES[Math.floor(Math.random() * ADJECTIVES.length)];
  const b = NOUNS[Math.floor(Math.random() * NOUNS.length)];
  const num = Math.floor(10 + Math.random() * 89);
  return `${a}${b}${num}`;
}

// 随机挑选一个预设头像标识
export function pickRandomPresetAvatar(): string {
  const item = PRESET_AVATARS[Math.floor(Math.random() * PRESET_AVATARS.length)];
  return `preset:${item.id}`;
}

// 解析预设头像配置
export function parsePresetAvatar(avatar?: string): PresetAvatar | null {
  if (!avatar || !avatar.startsWith('preset:')) {
    return null;
  }
  const id = avatar.slice('preset:'.length);
  return PRESET_AVATARS.find((item) => item.id === id) || PRESET_AVATARS[0];
}

// 将用户上传的图片居中裁剪为 160x160 轻量头像 DataURL（通常仅 8~20KB）
export async function compressAvatarFile(file: File | Blob): Promise<string> {
  const bitmap = await loadImageFromBlob(file);
  const size = 160;
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    throw new Error('无法创建画布上下文');
  }

  const minEdge = Math.min(bitmap.width, bitmap.height);
  const sx = (bitmap.width - minEdge) / 2;
  const sy = (bitmap.height - minEdge) / 2;

  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(bitmap, sx, sy, minEdge, minEdge, 0, 0, size, size);

  // 优先使用 WebP，不支持时回退 JPEG
  const webp = canvas.toDataURL('image/webp', 0.82);
  if (webp.startsWith('data:image/webp')) {
    return webp;
  }
  return canvas.toDataURL('image/jpeg', 0.84);
}

// 可选压缩发送的聊天图片（兼顾清晰度与快速传输/缓存体积）
export async function compressChatImage(
  file: File | Blob,
  maxDimension = 1920,
  quality = 0.86
): Promise<Blob> {
  const img = await loadImageFromBlob(file);
  let targetW = img.width;
  let targetH = img.height;

  if (targetW > maxDimension || targetH > maxDimension) {
    const ratio = Math.min(maxDimension / targetW, maxDimension / targetH);
    targetW = Math.round(targetW * ratio);
    targetH = Math.round(targetH * ratio);
  }

  const canvas = document.createElement('canvas');
  canvas.width = targetW;
  canvas.height = targetH;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    return file;
  }
  ctx.drawImage(img, 0, 0, targetW, targetH);

  return new Promise<Blob>((resolve) => {
    canvas.toBlob(
      (blob) => {
        resolve(blob || file);
      },
      'image/webp',
      quality
    );
  });
}

function loadImageFromBlob(blob: Blob): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      resolve(img);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('图片解析失败，请确认文件格式有效'));
    };
    img.src = url;
  });
}
