import React, { useState } from 'react';
import { Play, Pause, RotateCcw, Volume2, ChevronDown, ChevronUp } from 'lucide-react';
import type { PomodoroMode, PomodoroState } from '../types';
import { AMBIENT_OPTIONS, type AmbientSoundType } from '../lib/ambient';

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
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 6,
          cursor: 'pointer',
        }}
        onClick={() => setExpanded((v) => !v)}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
          <span style={{ fontSize: 13 }}>🍅</span>
          <span style={{ fontSize: 12.5, fontWeight: 800 }}>一起自习室</span>
          {pomodoro.running && <span className="status-dot emerald" />}
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
          <span
            className="mono"
            style={{
              fontSize: 14,
              fontWeight: 900,
              color: pomodoro.running ? 'var(--accent)' : 'var(--text-main)',
            }}
          >
            {formatTimerClock(displaySeconds)}
          </span>
          <button
            type="button"
            className={`btn btn-icon ${pomodoro.running ? 'btn-soft' : 'btn-jelly'}`}
            style={{ width: 25, height: 25, borderRadius: 8 }}
            onClick={(e) => {
              e.stopPropagation();
              onStartToggle();
            }}
            title={pomodoro.running ? '暂停同步番茄钟' : '启动全房同步番茄钟'}
          >
            {pomodoro.running ? <Pause size={11} /> : <Play size={11} />}
          </button>
          {expanded ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
        </div>
      </div>

      {pomodoro.running && pomodoro.startedBy && !expanded && (
        <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>
          ✨ {pomodoro.startedBy} 发起了全房专注 · 完成后基地 +1🍅
        </div>
      )}

      {expanded && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 9, paddingTop: 4 }}>
          {/* 番茄模式切换 */}
          <div style={{ display: 'flex', gap: 4 }}>
            {MODE_OPTIONS.map((m) => (
              <button
                key={m.id}
                type="button"
                className={`btn btn-sm ${pomodoro.mode === m.id ? 'btn-jelly' : 'btn-glass'}`}
                style={{ flex: 1, height: 25, fontSize: 11, padding: '0 4px' }}
                onClick={() => onSwitchMode(m.id)}
              >
                {m.label}
              </button>
            ))}
            <button
              type="button"
              className="btn btn-ghost btn-icon"
              style={{ width: 25, height: 25 }}
              onClick={onReset}
              title="重置倒计时"
            >
              <RotateCcw size={12} />
            </button>
          </div>

          {/* 零依赖环境白噪音 */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
            <div style={{ fontSize: 11.5, fontWeight: 700, color: 'var(--text-sub)', display: 'flex', alignItems: 'center', gap: 4 }}>
              <Volume2 size={12} style={{ color: 'var(--accent)' }} /> 治愈白噪音（本机播放）
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 4 }}>
              {AMBIENT_OPTIONS.map((opt) => (
                <button
                  key={opt.id}
                  type="button"
                  className={`btn btn-sm ${ambientSound === opt.id ? 'btn-jelly' : 'btn-glass'}`}
                  style={{ height: 26, padding: 0, fontSize: 11, flexDirection: 'column', gap: 0 }}
                  onClick={() => onSelectAmbient(opt.id)}
                  title={opt.label}
                >
                  <span>{opt.icon}</span>
                </button>
              ))}
            </div>
            {ambientSound !== 'off' && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>音量</span>
                <input
                  type="range"
                  min={0.05}
                  max={0.85}
                  step={0.05}
                  value={ambientVolume}
                  onChange={(e) => onChangeVolume(Number(e.target.value))}
                  style={{ flex: 1, accentColor: 'var(--accent)' }}
                />
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
