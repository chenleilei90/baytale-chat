import type { PlaneFileKind } from '../types';

// 根据文件名与 MIME 归类为语义文件类型，用于纸飞机卡片图标与分组展示
export function classifyPlaneFileKind(fileName: string, mime = ''): PlaneFileKind {
  const lowerName = fileName.trim().toLowerCase();
  const lowerMime = mime.trim().toLowerCase();

  if (lowerName.endsWith('.apk') || lowerMime === 'application/vnd.android.package-archive') {
    return 'apk';
  }
  if (
    lowerMime.startsWith('image/') ||
    /\.(png|jpe?g|gif|webp|bmp|svg|heic|avif)$/i.test(lowerName)
  ) {
    return 'image';
  }
  if (
    lowerMime.startsWith('video/') ||
    /\.(mp4|mov|mkv|avi|webm|flv|m4v)$/i.test(lowerName)
  ) {
    return 'video';
  }
  if (
    lowerMime.startsWith('audio/') ||
    /\.(mp3|wav|ogg|m4a|flac|aac|opus)$/i.test(lowerName)
  ) {
    return 'audio';
  }
  if (/\.(zip|rar|7z|tar|gz|bz2|xz|tgz|iso)$/i.test(lowerName)) {
    return 'archive';
  }
  if (
    /\.(pdf|docx?|xlsx?|pptx?|txt|md|csv|json|xml|rtf|epub)$/i.test(lowerName) ||
    lowerMime.includes('pdf') ||
    lowerMime.startsWith('text/')
  ) {
    return 'doc';
  }
  return 'file';
}

export function formatPlaneKindLabel(kind: PlaneFileKind): string {
  switch (kind) {
    case 'apk':
      return '安装包';
    case 'image':
      return '图片';
    case 'video':
      return '视频';
    case 'audio':
      return '音频';
    case 'archive':
      return '压缩包';
    case 'doc':
      return '文档';
    default:
      return '文件';
  }
}
