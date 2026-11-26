import React from 'react';
import { X } from 'lucide-react';
import type { AppearanceMode, ThemeMode } from '../types';
import type { UserPrefs } from '../lib/storage';
import { AppearancePicker, PalettePicker, Switch } from './Controls';

interface SettingsPopoverProps {
  appearance: AppearanceMode;
  theme: ThemeMode;
  prefs: UserPrefs;
  onSelectAppearance: (mode: AppearanceMode) => void;
  onSelectTheme: (theme: ThemeMode) => void;
  onUpdatePrefs: (next: UserPrefs) => void;
  onClose: () => void;
  placement?: 'bottom-right' | 'top-left';
  extraSection?: React.ReactNode;
}

// 大厅与聊天室共用的外观与传输偏好设置浮层
export const SettingsPopover: React.FC<SettingsPopoverProps> = ({
  appearance,
  theme,
  prefs,
  onSelectAppearance,
  onSelectTheme,
  onUpdatePrefs,
  onClose,
  placement = 'bottom-right',
  extraSection,
}) => {
  const posStyle: React.CSSProperties =
    placement === 'top-left'
      ? { top: 'auto', bottom: 'calc(100% + 10px)', right: 'auto', left: 0 }
      : {};

  return (
    <div
      className="floating-popover"
      style={posStyle}
      role="dialog"
      aria-label="外观与偏好设置"
      onClick={(e) => e.stopPropagation()}
    >
      <div className="settings-pop-head">
        <span className="settings-pop-title">外观与偏好设置</span>
        <button
          type="button"
          className="btn btn-ghost btn-icon modal-close-btn"
          style={{ width: 28, height: 28 }}
          onClick={onClose}
          aria-label="关闭设置"
          title="关闭"
        >
          <X size={14} aria-hidden="true" />
        </button>
      </div>

      <div className="sidebar-appearance-block">
        <span className="sidebar-setting-label">外观模式</span>
        <AppearancePicker appearance={appearance} onSelect={onSelectAppearance} />
      </div>

      <div className="sidebar-theme-row">
        <span className="sidebar-setting-label">主题色</span>
        <PalettePicker theme={theme} onSelect={onSelectTheme} />
      </div>

      <div className="popover-divider settings-pop-switches">
        <Switch
          checked={prefs.autoAcceptSmallFiles}
          onChange={(v) => onUpdatePrefs({ ...prefs, autoAcceptSmallFiles: v })}
          label={`自动接收 ≤${prefs.autoAcceptMaxMB}MB 文件`}
        />
        <Switch
          checked={prefs.soundEnabled}
          onChange={(v) => onUpdatePrefs({ ...prefs, soundEnabled: v })}
          label="清脆提示音效"
        />
      </div>

      {extraSection && <div className="popover-divider">{extraSection}</div>}
    </div>
  );
};
