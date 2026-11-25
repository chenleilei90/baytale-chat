// 全屏礼花粒子、表情雨瀑布与趣味音效引擎（零外部依赖 Canvas 高帧率渲染）
import type { FunEffectType } from '../types';

export interface ThemeOption {
  id: 'peach' | 'mint' | 'berry' | 'lime' | 'neon';
  name: string;
  orb: string;
}

export const THEME_OPTIONS: ThemeOption[] = [
  { id: 'peach', name: '蜜桃汽水', orb: 'linear-gradient(135deg, #ff5e8e, #ff965a)' },
  { id: 'mint', name: '海盐薄荷', orb: 'linear-gradient(135deg, #06b6d4, #3b82f6)' },
  { id: 'berry', name: '葡萄苏打', orb: 'linear-gradient(135deg, #8b5cf6, #ec4899)' },
  { id: 'lime', name: '青柠假日', orb: 'linear-gradient(135deg, #10b981, #f59e0b)' },
  { id: 'neon', name: '赛博霓虹', orb: 'linear-gradient(135deg, #1e1b4b, #f43f5e, #22d3ee)' },
];

const SECRET_PREFIX = '::secret::';

export function encodeSecretText(text: string): string {
  return `${SECRET_PREFIX}${text}`;
}

export function decodeSecretText(raw?: string): { isSecret: boolean; text: string } {
  if (!raw) return { isSecret: false, text: '' };
  if (raw.startsWith(SECRET_PREFIX)) {
    return { isSecret: true, text: raw.slice(SECRET_PREFIX.length) };
  }
  return { isSecret: false, text: raw };
}

// 在屏幕上触发绚丽的礼花纸屑或表情气泡雨动画
export function launchScreenEffect(type: FunEffectType): void {
  if (typeof document === 'undefined') return;

  const canvas = document.createElement('canvas');
  canvas.style.position = 'fixed';
  canvas.style.inset = '0';
  canvas.style.width = '100vw';
  canvas.style.height = '100vh';
  canvas.style.pointerEvents = 'none';
  canvas.style.zIndex = '999';
  canvas.width = window.innerWidth;
  canvas.height = window.innerHeight;
  document.body.appendChild(canvas);

  const ctx = canvas.getContext('2d');
  if (!ctx) {
    canvas.remove();
    return;
  }

  const colors = ['#ff5e8e', '#ff965a', '#facc15', '#34d399', '#38bdf8', '#a855f7', '#f43f5e'];
  const emojiSet: Record<FunEffectType, string[]> = {
    confetti: ['🎉', '✨', '🎊', '⭐'],
    hearts: ['❤️', '💖', '🥰', '💕', '🌸'],
    fire: ['🔥', '⚡', '💥', '🌟'],
    rocket: ['🚀', '🛸', '🌈', '✨'],
    poke: ['⚡', '👉', '💢', '😜'],
    tomato: ['🍅', '🍅', '💥', '😝'],
    flower: ['💐', '🌹', '🌸', '🌺', '✨'],
    freeze: ['❄️', '🧊', '⛄', '💠'],
    bomb: ['💣', '💥', '🔥', '💨'],
  };

  interface Particle {
    x: number;
    y: number;
    vx: number;
    vy: number;
    rot: number;
    vRot: number;
    size: number;
    color: string;
    emoji?: string;
    alpha: number;
  }

  const particles: Particle[] = [];
  const count = type === 'confetti' ? 75 : type === 'bomb' ? 52 : 42;

  for (let i = 0; i < count; i++) {
    const fromBottom = type === 'rocket' || type === 'hearts' || type === 'flower';
    const isCenterBurst = type === 'bomb' || type === 'tomato';
    const isRibbon = type === 'confetti' && i % 4 !== 0;
    const pool = emojiSet[type];
    const angle = Math.random() * Math.PI * 2;
    const burstSpeed = 4 + Math.random() * 11;

    particles.push({
      x: isCenterBurst ? canvas.width * (0.3 + Math.random() * 0.4) : Math.random() * canvas.width,
      y: isCenterBurst
        ? canvas.height * (0.25 + Math.random() * 0.4)
        : fromBottom
          ? canvas.height + Math.random() * 120
          : -20 - Math.random() * 160,
      vx: isCenterBurst ? Math.cos(angle) * burstSpeed : (Math.random() - 0.5) * 6,
      vy: isCenterBurst
        ? Math.sin(angle) * burstSpeed - 2
        : fromBottom
          ? -(5.5 + Math.random() * 7)
          : 2.8 + Math.random() * 5.2,
      rot: Math.random() * Math.PI * 2,
      vRot: (Math.random() - 0.5) * 0.18,
      size: isRibbon ? 8 + Math.random() * 7 : type === 'tomato' ? 28 + Math.random() * 20 : 22 + Math.random() * 16,
      color: colors[i % colors.length],
      emoji: isRibbon ? undefined : pool[i % pool.length],
      alpha: 1,
    });
  }

  playFunPopSound(type);

  const start = performance.now();
  const duration = type === 'freeze' ? 3000 : 2600;

  const tick = (now: number) => {
    const elapsed = now - start;
    if (elapsed > duration) {
      canvas.remove();
      return;
    }

    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // 冰冻道具附带晶莹四周冷霜光晕
    if (type === 'freeze') {
      const frostAlpha = Math.sin((elapsed / duration) * Math.PI) * 0.28;
      const grad = ctx.createRadialGradient(
        canvas.width / 2,
        canvas.height / 2,
        Math.min(canvas.width, canvas.height) * 0.25,
        canvas.width / 2,
        canvas.height / 2,
        Math.max(canvas.width, canvas.height) * 0.65
      );
      grad.addColorStop(0, 'rgba(56, 189, 248, 0)');
      grad.addColorStop(1, `rgba(56, 189, 248, ${frostAlpha.toFixed(3)})`);
      ctx.fillStyle = grad;
      ctx.fillRect(0, 0, canvas.width, canvas.height);
    }

    const fadeStart = duration * 0.68;

    for (const p of particles) {
      p.x += p.vx;
      p.y += p.vy;
      if (type === 'confetti' || type === 'tomato' || type === 'bomb') {
        p.vy += type === 'tomato' ? 0.22 : 0.12;
        p.vx *= 0.985;
      }
      p.rot += p.vRot;
      if (elapsed > fadeStart) {
        p.alpha = Math.max(0, 1 - (elapsed - fadeStart) / (duration - fadeStart));
      }

      ctx.save();
      ctx.globalAlpha = p.alpha;
      ctx.translate(p.x, p.y);
      ctx.rotate(p.rot);

      if (p.emoji) {
        ctx.font = `${Math.round(p.size)}px "Apple Color Emoji", "Segoe UI Emoji", sans-serif`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(p.emoji, 0, 0);
      } else {
        ctx.fillStyle = p.color;
        ctx.fillRect(-p.size / 2, -p.size * 0.35, p.size, p.size * 0.7);
      }
      ctx.restore();
    }

    requestAnimationFrame(tick);
  };

  requestAnimationFrame(tick);
}

// 复用单例 AudioContext，避免 Chrome 超过 6 个实例上限后音效失效
let sharedFxAudioCtx: AudioContext | null = null;

// 播放清脆欢快的合成音效
function playFunPopSound(type: FunEffectType): void {
  try {
    const Ctor =
      window.AudioContext ||
      (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    if (!Ctor) return;
    if (!sharedFxAudioCtx) {
      sharedFxAudioCtx = new Ctor();
    }
    if (sharedFxAudioCtx.state === 'suspended') {
      void sharedFxAudioCtx.resume();
    }
    const ctx = sharedFxAudioCtx;
    const notes =
      type === 'poke' || type === 'tomato'
        ? [520, 340, 260]
        : type === 'bomb'
          ? [220, 140, 95]
          : type === 'freeze'
            ? [880, 1046, 1318]
            : type === 'rocket' || type === 'flower'
              ? [440, 554, 659, 880]
              : [523, 659, 784];

    notes.forEach((freq, idx) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = type === 'poke' || type === 'bomb' || type === 'tomato' ? 'triangle' : 'sine';
      const t0 = ctx.currentTime + idx * 0.065;
      osc.frequency.setValueAtTime(freq, t0);
      gain.gain.setValueAtTime(0.045, t0);
      gain.gain.exponentialRampToValueAtTime(0.001, t0 + 0.16);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(t0);
      osc.stop(t0 + 0.16);
    });
  } catch {
    // 忽略浏览器静音限制
  }
}
