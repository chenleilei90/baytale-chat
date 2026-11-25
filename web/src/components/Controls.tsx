import React from 'react';
import type { ThemeMode } from '../types';
import { THEME_OPTIONS } from '../lib/effects';

interface SwitchProps {
  checked: boolean;
  onChange: (next: boolean) => void;
  label: string;
  description?: string;
}

// Q弹拨动开关组件
export const Switch: React.FC<SwitchProps> = ({ checked, onChange, label, description }) => {
  return (
    <div
      className="switch-row"
      role="switch"
      aria-checked={checked}
      tabIndex={0}
      onClick={() => onChange(!checked)}
      onKeyDown={(e) => {
        if (e.key === ' ' || e.key === 'Enter') {
          e.preventDefault();
          onChange(!checked);
        }
      }}
    >
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--text-main)' }}>{label}</div>
        {description && (
          <div style={{ fontSize: 11.5, color: 'var(--text-muted)', marginTop: 1 }}>
            {description}
          </div>
        )}
      </div>
      <button
        type="button"
        className={`switch-track ${checked ? 'checked' : ''}`}
        tabIndex={-1}
        aria-hidden="true"
      >
        <span className="switch-thumb" />
      </button>
    </div>
  );
};

interface PalettePickerProps {
  theme: ThemeMode;
  onSelect: (theme: ThemeMode) => void;
}

// 5 款年轻灵感配色流光球切换器
export const PalettePicker: React.FC<PalettePickerProps> = ({ theme, onSelect }) => {
  return (
    <div className="palette-bar" title="切换多巴胺主题配色">
      {THEME_OPTIONS.map((item) => (
        <button
          key={item.id}
          type="button"
          className={`theme-orb ${theme === item.id ? 'active' : ''}`}
          style={{ background: item.orb }}
          onClick={() => onSelect(item.id)}
          title={`主题：${item.name}`}
          aria-label={`切换主题为${item.name}`}
        />
      ))}
    </div>
  );
};
