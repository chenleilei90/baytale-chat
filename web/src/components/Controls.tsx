import React from 'react';
import type { AppearanceMode, ThemeMode } from '../types';
import { THEME_OPTIONS } from '../lib/effects';

interface SwitchProps {
  checked: boolean;
  onChange: (next: boolean) => void;
  label: string;
  description?: string;
  disabled?: boolean;
}

// iOS 风格 51×31 零描边开关组件
export const Switch: React.FC<SwitchProps> = ({
  checked,
  onChange,
  label,
  description,
  disabled = false,
}) => {
  return (
    <div
      className={`switch-row ${disabled ? 'disabled' : ''}`}
      role="switch"
      aria-checked={checked}
      aria-disabled={disabled}
      tabIndex={disabled ? -1 : 0}
      onClick={() => !disabled && onChange(!checked)}
      onKeyDown={(e) => {
        if (!disabled && (e.key === ' ' || e.key === 'Enter')) {
          e.preventDefault();
          onChange(!checked);
        }
      }}
    >
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--text-primary)' }}>
          {label}
        </div>
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

// 5 款纯色强调色切换器（双环阴影表达选中态）
export const PalettePicker: React.FC<PalettePickerProps> = ({ theme, onSelect }) => {
  return (
    <div className="palette-bar" title="切换强调色" role="radiogroup" aria-label="强调色">
      {THEME_OPTIONS.map((item) => {
        const isActive = theme === item.id;
        return (
          <button
            key={item.id}
            type="button"
            role="radio"
            aria-checked={isActive}
            className={`theme-orb ${isActive ? 'active' : ''}`}
            style={{ backgroundColor: item.orb, color: item.orb }}
            onClick={() => onSelect(item.id)}
            title={`主题：${item.name}`}
            aria-label={`切换强调色为${item.name}`}
          />
        );
      })}
    </div>
  );
};

interface AppearancePickerProps {
  appearance: AppearanceMode;
  onSelect: (mode: AppearanceMode) => void;
}

const APPEARANCE_OPTIONS: { id: AppearanceMode; label: string }[] = [
  { id: 'system', label: '跟随系统' },
  { id: 'light', label: '浅色' },
  { id: 'dark', label: '深色' },
];

// 外观模式分段切换器（跟随系统 / 浅色 / 深色）
export const AppearancePicker: React.FC<AppearancePickerProps> = ({
  appearance,
  onSelect,
}) => {
  return (
    <div className="appearance-picker" role="radiogroup" aria-label="外观模式">
      {APPEARANCE_OPTIONS.map((item) => {
        const isActive = appearance === item.id;
        return (
          <button
            key={item.id}
            type="button"
            role="radio"
            aria-checked={isActive}
            className={`appearance-item ${isActive ? 'active' : ''}`}
            onClick={() => onSelect(item.id)}
          >
            {item.label}
          </button>
        );
      })}
    </div>
  );
};
