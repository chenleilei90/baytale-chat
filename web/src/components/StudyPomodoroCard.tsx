import React, { useState } from 'react';
import {
  Play,
  Pause,
  RotateCcw,
  Volume2,
  VolumeX,
  CloudRain,
  Waves,
  Flame,
  Coffee,
  Timer,
  ChevronDown,
  ChevronUp,
} from 'lucide-react';
import type { PomodoroMode, PomodoroState } from '../types';
import { AMBIENT_OPTIONS, type AmbientSoundType } from '../lib/ambient';

function renderAmbientIcon(id: AmbientSoundType): React.ReactNode {
  switch (id) {
    case 'off':
      return <VolumeX size={13} aria-hidden="true" />;
    case 'rain':
      return <CloudRain size={13} aria-hidden="true" />;
    case 'ocean':
      return <Waves size={13} aria-hidden="true" />;
    case 'fire':
      return <Flame size={13} aria-hidden="true" />;
    case 'study':
      return <Coffee size={13} aria-hidden="true" />;
  }
}

interface StudyPomodoroCardProps {
  pomodoro: PomodoroState;
  displaySeconds: number;
  ambientSound: AmbientSoundType;
  ambientVolume: number;
  onStartToggle: () => void;
  onSwitchMode: (mode: PomodoroMode) => void;
  onReset: () => void;
  onSelectAmbient: (sound: AmbientSoundType) => void;
  onChangeVolume: (vol: number) => void;
}

const MODE_OPTIONS: { id: PomodoroMode; label: string }[] = [
  { id: 'focus25', label: '25m 专注' },
  { id: 'deep45', label: '45m 深度' },
  { id: 'break5', label: '5m 小憩' },
];

function formatTimerClock(sec: number): string {
  const safe = Math.max(0, Math.floor(sec));
  const m = Math.floor(safe / 60);
  const s = safe % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

// 陪伴自习室卡片：全房同步番茄钟倒计时 + 纯前端合成环境白噪音（细雨/海浪/壁炉/书房）
export const StudyPomodoroCard: React.FC<StudyPomodoroCardProps> = ({
  pomodoro,
  displaySeconds,
  ambientSound,
  ambientVolume,
  onStartToggle,
  onSwitchMode,
  onReset,
  onSelectAmbient,
  onChangeVolume,
}) => {
  const [expanded, setExpanded] = useState(false);

  return (
    <div className="study-card">
      <div
        className="study-card-head"
        onClick={() => setExpanded((v) => !v)}
        role="button"
        tabIndex={0}
        aria-expanded={expanded}
        aria-label={expanded ? '收起自习室面板' : '展开自习室面板'}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            setExpanded((v) => !v);
          }
        }}
      >
        <div className="study-card-title-wrap">
          <Timer size={14} style={{ color: 'var(--accent)', flexShrink: 0 }} aria-hidden="true" />
          <span className="study-card-title">一起自习室</span>
          {pomodoro.running && <span className="status-dot emerald" />}
        </div>

        <div className="study-card-controls">
          <span className={`mono study-card-clock ${pomodoro.running ? 'running' : ''}`}>
            {formatTimerClock(displaySeconds)}
          </span>
          <button
            type="button"
            className={`btn btn-icon study-card-play-btn ${pomodoro.running ? 'btn-soft' : 'btn-primary'}`}
            onClick={(e) => {
              e.stopPropagation();
              onStartToggle();
            }}
            title={pomodoro.running ? '暂停同步番茄钟' : '启动全房同步番茄钟'}
            aria-label={pomodoro.running ? '暂停同步番茄钟' : '启动全房同步番茄钟'}
          >
            {pomodoro.running ? <Pause size={11} aria-hidden="true" /> : <Play size={11} aria-hidden="true" />}
          </button>
          {expanded ? <ChevronUp size={13} aria-hidden="true" /> : <ChevronDown size={13} aria-hidden="true" />}
        </div>
      </div>

      {pomodoro.running && pomodoro.startedBy && !expanded && (
        <div className="study-card-subtitle">
          {pomodoro.startedBy} 发起了全房专注，完成后基地计数 +1
        </div>
      )}

      {expanded && (
        <div className="study-card-body">
          {/* 番茄模式切换 */}
          <div className="study-mode-row">
            {MODE_OPTIONS.map((m) => (
              <button
                key={m.id}
                type="button"
                className={`btn btn-sm study-mode-btn ${pomodoro.mode === m.id ? 'btn-primary' : 'btn-secondary'}`}
                onClick={() => onSwitchMode(m.id)}
              >
                {m.label}
              </button>
            ))}
            <button
              type="button"
              className="btn btn-ghost btn-icon study-reset-btn"
              onClick={onReset}
              title="重置倒计时"
              aria-label="重置倒计时"
            >
              <RotateCcw size={12} aria-hidden="true" />
            </button>
          </div>

          {/* 零依赖环境白噪音 */}
          <div className="study-ambient-section">
            <div className="study-ambient-title">
              <Volume2 size={12} style={{ color: 'var(--accent)' }} aria-hidden="true" /> 环境白噪音（本机播放）
            </div>
            <div className="study-ambient-grid">
              {AMBIENT_OPTIONS.map((opt) => (
                <button
                  key={opt.id}
                  type="button"
                  className={`btn btn-sm study-ambient-btn ${ambientSound === opt.id ? 'btn-primary' : 'btn-secondary'}`}
                  onClick={() => onSelectAmbient(opt.id)}
                  title={opt.label}
                  aria-label={`白噪音：${opt.label}`}
                >
                  {renderAmbientIcon(opt.id)}
                </button>
              ))}
            </div>
            {ambientSound !== 'off' && (
              <div className="study-volume-row">
                <span className="study-volume-label">音量</span>
                <input
                  type="range"
                  min={0.05}
                  max={0.85}
                  step={0.05}
                  value={ambientVolume}
                  aria-label="白噪音音量"
                  onChange={(e) => onChangeVolume(Number(e.target.value))}
                  className="study-volume-slider"
                />
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
