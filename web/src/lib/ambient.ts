// 纯前端零依赖 Web Audio 环境白噪音合成引擎（支持细雨 / 海浪 / 壁炉 / 深夜书房）
// 无需加载任何外部 MP3，离线永久秒开且零版权风险

export type AmbientSoundType = 'off' | 'rain' | 'ocean' | 'fire' | 'study';

export const AMBIENT_OPTIONS: { id: AmbientSoundType; icon: string; label: string }[] = [
  { id: 'off', icon: 'off', label: '静音' },
  { id: 'rain', icon: 'rain', label: '细雨' },
  { id: 'ocean', icon: 'ocean', label: '海浪' },
  { id: 'fire', icon: 'fire', label: '壁炉' },
  { id: 'study', icon: 'study', label: '书房' },
];

class AmbientNoiseEngine {
  private ctx: AudioContext | null = null;
  private masterGain: GainNode | null = null;
  private activeNodes: AudioNode[] = [];
  private activeTimer: number | null = null;
  private current: AmbientSoundType = 'off';
  private volume = 0.32;

  private ensureContext(): AudioContext | null {
    if (typeof window === 'undefined') return null;
    const Ctor =
      window.AudioContext ||
      (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    if (!Ctor) return null;
    if (!this.ctx) {
      this.ctx = new Ctor();
    }
    if (this.ctx.state === 'suspended') {
      void this.ctx.resume();
    }
    return this.ctx;
  }

  getCurrent(): AmbientSoundType {
    return this.current;
  }

  setVolume(vol: number): void {
    this.volume = Math.max(0, Math.min(1, vol));
    if (this.masterGain && this.ctx) {
      this.masterGain.gain.setTargetAtTime(this.volume, this.ctx.currentTime, 0.08);
    }
  }

  stop(): void {
    this.current = 'off';
    if (this.activeTimer !== null) {
      clearInterval(this.activeTimer);
      this.activeTimer = null;
    }
    for (const node of this.activeNodes) {
      try {
        if ('stop' in node && typeof (node as AudioScheduledSourceNode).stop === 'function') {
          (node as AudioScheduledSourceNode).stop();
        }
        node.disconnect();
      } catch {
        // 忽略已停止节点
      }
    }
    this.activeNodes = [];
    this.masterGain = null;
  }

  start(type: AmbientSoundType, volume?: number): void {
    if (typeof volume === 'number') {
      this.volume = Math.max(0, Math.min(1, volume));
    }
    this.stop();
    if (type === 'off') return;

    const ctx = this.ensureContext();
    if (!ctx) return;

    this.current = type;
    const master = ctx.createGain();
    master.gain.setValueAtTime(0.001, ctx.currentTime);
    master.gain.exponentialRampToValueAtTime(Math.max(0.01, this.volume), ctx.currentTime + 0.5);
    master.connect(ctx.destination);
    this.masterGain = master;
    this.activeNodes.push(master);

    // 生成 3 秒无缝循环噪声缓冲区
    const sampleRate = ctx.sampleRate;
    const length = sampleRate * 3;
    const buffer = ctx.createBuffer(1, length, sampleRate);
    const data = buffer.getChannelData(0);

    if (type === 'rain') {
      // 粉红噪声模拟柔和雨帘
      let b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
      for (let i = 0; i < length; i++) {
        const white = Math.random() * 2 - 1;
        b0 = 0.99886 * b0 + white * 0.0555179;
        b1 = 0.99332 * b1 + white * 0.0750759;
        b2 = 0.969 * b2 + white * 0.153852;
        b3 = 0.8665 * b3 + white * 0.3104856;
        b4 = 0.55 * b4 + white * 0.5329522;
        b5 = -0.7616 * b5 - white * 0.016898;
        data[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.045;
        b6 = white * 0.115926;
      }
    } else {
      // 褐色/棕噪声模拟温暖低频声底
      let lastOut = 0;
      for (let i = 0; i < length; i++) {
        const white = Math.random() * 2 - 1;
        data[i] = (lastOut + 0.022 * white) / 1.022;
        lastOut = data[i];
        data[i] *= type === 'study' ? 1.8 : 2.6;
      }
    }

    const src = ctx.createBufferSource();
    src.buffer = buffer;
    src.loop = true;

    const filter = ctx.createBiquadFilter();
    if (type === 'rain') {
      filter.type = 'bandpass';
      filter.frequency.value = 1150;
      filter.Q.value = 0.65;
    } else if (type === 'ocean') {
      filter.type = 'lowpass';
      filter.frequency.value = 480;
    } else if (type === 'fire') {
      filter.type = 'lowpass';
      filter.frequency.value = 340;
    } else {
      filter.type = 'lowpass';
      filter.frequency.value = 620;
    }

    src.connect(filter);

    if (type === 'ocean') {
      // 用慢速 LFO 模拟海浪潮汐起伏
      const waveGain = ctx.createGain();
      waveGain.gain.value = 0.65;
      const lfo = ctx.createOscillator();
      const lfoGain = ctx.createGain();
      lfo.type = 'sine';
      lfo.frequency.value = 0.13;
      lfoGain.gain.value = 0.32;
      lfo.connect(lfoGain);
      lfoGain.connect(waveGain.gain);
      filter.connect(waveGain);
      waveGain.connect(master);
      lfo.start();
      this.activeNodes.push(waveGain, lfo, lfoGain);
    } else {
      filter.connect(master);
    }

    src.start();
    this.activeNodes.push(src, filter);

    // 壁炉模式周期加入轻柔柴火噼啪星火声
    if (type === 'fire') {
      this.activeTimer = window.setInterval(() => {
        if (!this.ctx || !this.masterGain || this.current !== 'fire') return;
        if (Math.random() > 0.55) return;
        try {
          const osc = this.ctx.createOscillator();
          const g = this.ctx.createGain();
          osc.type = 'triangle';
          osc.frequency.setValueAtTime(900 + Math.random() * 1400, this.ctx.currentTime);
          g.gain.setValueAtTime(0.06, this.ctx.currentTime);
          g.gain.exponentialRampToValueAtTime(0.001, this.ctx.currentTime + 0.035);
          osc.connect(g);
          g.connect(this.masterGain);
          osc.start();
          osc.stop(this.ctx.currentTime + 0.038);
        } catch {
          // 忽略瞬态异常
        }
      }, 260);
    }
  }
}

export const ambientEngine = new AmbientNoiseEngine();

// 番茄钟专注结束时的清亮治愈三和弦钟声
export function playPomodoroChime(): void {
  try {
    const Ctor =
      window.AudioContext ||
      (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    if (!Ctor) return;
    const ctx = new Ctor();
    const notes = [523.25, 659.25, 783.99, 1046.5];
    notes.forEach((freq, idx) => {
      const osc = ctx.createOscillator();
      const g = ctx.createGain();
      osc.type = 'sine';
      const t0 = ctx.currentTime + idx * 0.14;
      osc.frequency.setValueAtTime(freq, t0);
      g.gain.setValueAtTime(0.06, t0);
      g.gain.exponentialRampToValueAtTime(0.0008, t0 + 0.55);
      osc.connect(g);
      g.connect(ctx.destination);
      osc.start(t0);
      osc.stop(t0 + 0.58);
    });
    window.setTimeout(() => void ctx.close().catch(() => {}), 1500);
  } catch {
    // 忽略静音限制
  }
}
