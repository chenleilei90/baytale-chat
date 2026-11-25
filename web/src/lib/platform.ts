// 平台能力抽象层（集中封装磁盘流式写盘、剪贴板、屏幕捕获、提示音）
// 后续打包为 Windows Tauri EXE 时，仅需在此文件中接入原生桌面 API，业务与协议层零改动

export interface DiskWriteStream {
  write: (chunk: Uint8Array) => Promise<void>;
  close: () => Promise<void>;
  abort: () => Promise<void>;
}

// 检查当前浏览器是否支持 File System Access API（支持 GB 级大文件流式直写硬盘）
export function supportsDirectDiskSave(): boolean {
  return typeof window !== 'undefined' && 'showSaveFilePicker' in window;
}

// 弹出系统保存对话框并打开流式写入句柄
export async function openDiskWriteStream(suggestedName: string): Promise<DiskWriteStream | null> {
  if (!supportsDirectDiskSave()) {
    return null;
  }
  try {
    const picker = (
      window as unknown as {
        showSaveFilePicker: (opts: { suggestedName: string }) => Promise<{
          createWritable: () => Promise<{
            write: (data: BufferSource) => Promise<void>;
            close: () => Promise<void>;
            abort: () => Promise<void>;
          }>;
        }>;
      }
    ).showSaveFilePicker;

    const handle = await picker({ suggestedName });
    const writable = await handle.createWritable();
    return {
      write: async (chunk: Uint8Array) => {
        // 复制出干净的 ArrayBuffer 视图以满足 TypeScript BufferSource 约束
        const copy = new Uint8Array(chunk.byteLength);
        copy.set(chunk);
        await writable.write(copy);
      },
      close: () => writable.close(),
      abort: () => writable.abort(),
    };
  } catch (err) {
    // 用户取消弹窗则返回 null
    if ((err as DOMException)?.name === 'AbortError') {
      return null;
    }
    throw err;
  }
}

// 触发浏览器文件下载
export function triggerBlobDownload(urlOrBlob: string | Blob, fileName: string): void {
  const isBlob = typeof urlOrBlob !== 'string';
  const url = isBlob ? URL.createObjectURL(urlOrBlob) : urlOrBlob;
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  if (isBlob) {
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  }
}

// 复制纯文本到剪贴板（兼容 HTTP 降级环境）
export async function copyTextToClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // 回退到 execCommand
  }
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch {
    return false;
  }
}

// 检查当前环境是否支持浏览器内屏幕/窗口捕获截图
export function supportsScreenCapture(): boolean {
  return (
    typeof navigator !== 'undefined' &&
    Boolean(navigator.mediaDevices && typeof navigator.mediaDevices.getDisplayMedia === 'function')
  );
}

// 捕获屏幕或指定应用窗口的一帧高清画面返回 Canvas
export async function captureScreenFrame(): Promise<HTMLCanvasElement> {
  if (!supportsScreenCapture()) {
    throw new Error('当前环境不支持网页直接截屏（需在 HTTPS 或 localhost 下使用桌面浏览器），可使用系统截图后 Ctrl+V 粘贴发送');
  }

  const stream = await navigator.mediaDevices.getDisplayMedia({
    video: {
      cursor: 'never',
    } as MediaTrackConstraints,
    audio: false,
  });

  try {
    const video = document.createElement('video');
    video.srcObject = stream;
    video.muted = true;
    video.playsInline = true;

    await new Promise<void>((resolve, reject) => {
      video.onloadedmetadata = () => {
        video.play().then(() => resolve()).catch(reject);
      };
      video.onerror = () => reject(new Error('屏幕画面读取失败'));
    });

    // 等待两帧确保捕获画面完全渲染
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));

    const canvas = document.createElement('canvas');
    canvas.width = video.videoWidth || 1280;
    canvas.height = video.videoHeight || 720;
    const ctx = canvas.getContext('2d');
    if (!ctx) {
      throw new Error('画布初始化失败');
    }
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    return canvas;
  } finally {
    stream.getTracks().forEach((t) => t.stop());
  }
}

// 使用 Web Audio API 播放轻柔提示音，无需额外音频文件
let audioCtx: AudioContext | null = null;
export function playNoticeTone(): void {
  try {
    const Ctor = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    if (!Ctor) return;
    if (!audioCtx) {
      audioCtx = new Ctor();
    }
    if (audioCtx.state === 'suspended') {
      void audioCtx.resume();
    }
    const osc = audioCtx.createOscillator();
    const gain = audioCtx.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(660, audioCtx.currentTime);
    osc.frequency.exponentialRampToValueAtTime(880, audioCtx.currentTime + 0.09);
    gain.gain.setValueAtTime(0.05, audioCtx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + 0.16);
    osc.connect(gain);
    gain.connect(audioCtx.destination);
    osc.start();
    osc.stop(audioCtx.currentTime + 0.16);
  } catch {
    // 忽略浏览器自动播放策略限制
  }
}

// 格式化文件大小
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const exp = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  const val = bytes / Math.pow(1024, exp);
  return `${val >= 100 || exp === 0 ? val.toFixed(0) : val.toFixed(1)} ${units[exp]}`;
}

// 格式化预计剩余时间
export function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return '即将完成';
  if (seconds < 60) return `${Math.ceil(seconds)} 秒`;
  const m = Math.floor(seconds / 60);
  const s = Math.ceil(seconds % 60);
  if (m < 60) return `${m} 分 ${s} 秒`;
  const h = Math.floor(m / 60);
  return `${h} 小时 ${m % 60} 分`;
}

// 格式化过期倒计时文案
export function formatExpiryTime(expiresAt?: number): string {
  if (!expiresAt) return '';
  const diffMs = expiresAt - Date.now();
  if (diffMs <= 0) return '即将自动清空';
  const hours = Math.floor(diffMs / 3600000);
  const mins = Math.floor((diffMs % 3600000) / 60000);
  if (hours >= 24) {
    const days = Math.floor(hours / 24);
    const remH = hours % 24;
    return `${days} 天 ${remH} 小时后自动清空`;
  }
  if (hours > 0) {
    return `${hours} 小时 ${mins} 分钟后自动清空`;
  }
  return `${Math.max(1, mins)} 分钟后自动清空`;
}
