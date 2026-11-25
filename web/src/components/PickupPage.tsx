import React, { useCallback, useEffect, useState } from 'react';
import {
  ArrowLeft,
  Download,
  File as FileIcon,
  Flame,
  Loader2,
  Lock,
  Package,
  RefreshCw,
} from 'lucide-react';
import type { AppearanceMode, PickupInfo, ThemeMode } from '../types';
import {
  ApiError,
  buildPickupFileDownloadUrl,
  fetchPickupInfo,
} from '../lib/signaling';
import { formatBytes, formatExpiryTime } from '../lib/platform';
import { AppearancePicker, PalettePicker } from './Controls';

// 将长文件名拆分为可省略的主干与始终完整显示的扩展名
function renderSplitFilename(name: string) {
  const dotIdx = name.lastIndexOf('.');
  const hasExt = dotIdx > 0 && dotIdx < name.length - 1 && name.length - dotIdx <= 12;
  if (!hasExt) {
    return (
      <span className="filename-split filename-split-md" title={name}>
        <span className="filename-stem">{name}</span>
      </span>
    );
  }
  return (
    <span className="filename-split filename-split-md" title={name}>
      <span className="filename-stem">{name.slice(0, dotIdx)}</span>
      <span className="filename-ext">{name.slice(dotIdx)}</span>
    </span>
  );
}

interface PickupPageProps {
  code: string;
  theme: ThemeMode;
  appearance: AppearanceMode;
  onSelectTheme: (t: ThemeMode) => void;
  onSelectAppearance: (mode: AppearanceMode) => void;
  onBackToLobby: () => void;
}

// /p/{code} 取件码提取页：显示文件列表、按需输入密码解锁、支持每文件断点下载
export const PickupPage: React.FC<PickupPageProps> = ({
  code,
  theme,
  appearance,
  onSelectTheme,
  onSelectAppearance,
  onBackToLobby,
}) => {
  const [info, setInfo] = useState<PickupInfo | null>(null);
  const [passwordInput, setPasswordInput] = useState('');
  const [unlockedPassword, setUnlockedPassword] = useState('');
  const [needPassword, setNeedPassword] = useState(false);
  const [loading, setLoading] = useState(true);
  const [errorText, setErrorText] = useState('');

  const loadInfo = useCallback(
    async (pwd: string) => {
      setLoading(true);
      setErrorText('');
      try {
        const res = await fetchPickupInfo(code, pwd || undefined);
        setInfo(res);
        setUnlockedPassword(pwd);
        setNeedPassword(false);
      } catch (err) {
        if (
          err instanceof ApiError &&
          (err.errorCode === 'PICKUP_PASSWORD_REQUIRED' || err.errorCode === 'WRONG_PASSWORD')
        ) {
          setNeedPassword(true);
          if (err.errorCode === 'WRONG_PASSWORD') {
            setErrorText('取件密码错误，请重试');
          }
        } else {
          setInfo(null);
          setNeedPassword(false);
          setErrorText(err instanceof Error ? err.message : '取件码不存在或已失效');
        }
      } finally {
        setLoading(false);
      }
    },
    [code]
  );

  useEffect(() => {
    void loadInfo('');
  }, [loadInfo]);

  const handleUnlock = (e: React.FormEvent) => {
    e.preventDefault();
    void loadInfo(passwordInput.trim());
  };

  return (
    <div className="lobby-shell">
      <header className="top-glass-nav lobby-top-nav">
        <button type="button" className="btn btn-glass btn-sm lobby-touch-pill" onClick={onBackToLobby}>
          <ArrowLeft size={14} aria-hidden="true" /> 返回大厅
        </button>
        <div className="mono pickup-nav-title">
          取件码 #{code}
        </div>
        <div className="lobby-theme-controls">
          <AppearancePicker appearance={appearance} onSelect={onSelectAppearance} />
          <PalettePicker theme={theme} onSelect={onSelectTheme} />
        </div>
      </header>

      <main className="bento-scroll">
        <div className="pickup-page-wrap">
          <section className="glass-bento pickup-page-card">
            {loading ? (
              <div className="pickup-state-box">
                <Loader2 size={26} className="bay-spin pickup-state-icon-accent" aria-hidden="true" />
                <div className="pickup-state-title">正在查询取件码 #{code}...</div>
                <div className="pickup-state-desc">正在核验文件元数据与可用状态</div>
              </div>
            ) : needPassword ? (
              <form onSubmit={handleUnlock} className="modal-stack">
                <div className="pickup-unlock-title">
                  <Lock size={18} className="lobby-card-title-icon" aria-hidden="true" />
                  <span>请输入提取密码解锁 #{code}</span>
                </div>
                <input
                  className="field-input"
                  type="password"
                  value={passwordInput}
                  onChange={(e) => setPasswordInput(e.target.value)}
                  placeholder="输入发送方告知的提取密码..."
                  autoFocus
                />
                {errorText && <div className="alert-strip">{errorText}</div>}
                <button type="submit" className="btn btn-jelly btn-xl">
                  解锁文件列表
                </button>
              </form>
            ) : errorText || !info ? (
              <div className="pickup-state-box">
                <Package size={36} className="pickup-state-icon" aria-hidden="true" />
                <div className="pickup-state-title">
                  {errorText || '取件码不存在或已失效'}
                </div>
                <div className="pickup-state-desc">
                  请核对 6 位数字取件码是否输入正确，或联系发送方重新生成。
                </div>
                <div className="pickup-action-row">
                  <button
                    type="button"
                    className="btn btn-secondary lobby-touch-pill"
                    onClick={() => void loadInfo(unlockedPassword)}
                  >
                    <RefreshCw size={14} aria-hidden="true" /> 重新查询
                  </button>
                  <button
                    type="button"
                    className="btn btn-soft lobby-touch-pill"
                    onClick={onBackToLobby}
                  >
                    回到湾聊首页
                  </button>
                </div>
              </div>
            ) : (
              <>
                <div className="pickup-info-head">
                  <div>
                    <div className="mono pickup-code-display">
                      {info.code}
                    </div>
                    <div className="modal-sub-caption mono">
                      {formatExpiryTime(info.expiresAt)} · 已下载 {info.downloads} 次
                    </div>
                  </div>
                  <div className="lobby-card-actions">
                    {info.burnAfterRead && (
                      <span className="pill pill-warm">
                        <Flame size={11} aria-hidden="true" /> 取后即焚
                      </span>
                    )}
                    <button
                      type="button"
                      className="btn btn-ghost btn-icon lobby-touch-icon"
                      title="刷新状态"
                      aria-label="刷新取件码状态"
                      onClick={() => void loadInfo(unlockedPassword)}
                    >
                      <RefreshCw size={14} aria-hidden="true" />
                    </button>
                  </div>
                </div>

                {info.burnedAt ? (
                  <div className="alert-strip">
                    <Flame size={15} aria-hidden="true" />
                    <span>文件已全部领取，当前处于 10 分钟断点续传宽限期，到期后将自动销毁</span>
                  </div>
                ) : null}

                {info.files.length === 0 ? (
                  <div className="lobby-empty-card">
                    <div className="lobby-empty-title">
                      <Package size={15} className="lobby-card-title-icon" aria-hidden="true" />
                      <span>暂无可下载文件</span>
                    </div>
                    <div className="lobby-empty-desc">
                      当前取件码下尚未包含任何文件记录。
                    </div>
                  </div>
                ) : (
                  <div className="lobby-room-list">
                    {info.files.map((f) => (
                      <div key={f.fileId} className="pickup-download-row">
                        <div className="pickup-download-left">
                          <div className="ext-jelly-box">
                            <FileIcon size={16} aria-hidden="true" />
                          </div>
                          <div className="lobby-room-meta">
                            {renderSplitFilename(f.name)}
                            <div className="mono pickup-download-meta">
                              {formatBytes(f.size)} · {f.ready ? '可下载' : '发送方仍在上传...'}
                            </div>
                          </div>
                        </div>
                        {f.ready ? (
                          <a
                            href={buildPickupFileDownloadUrl(info.code, f.fileId, unlockedPassword)}
                            download={f.name}
                            className="btn btn-jelly btn-sm pickup-download-btn lobby-touch-pill"
                          >
                            <Download size={13} aria-hidden="true" /> 下载
                          </a>
                        ) : (
                          <span className="pill pill-neutral">等待就绪</span>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </>
            )}
          </section>
        </div>
      </main>
    </div>
  );
};
