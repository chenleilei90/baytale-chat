import React, { useCallback, useEffect, useState } from 'react';
import {
  ArrowLeft,
  Download,
  File as FileIcon,
  Flame,
  Lock,
  Package,
  RefreshCw,
} from 'lucide-react';
import type { PickupInfo, ThemeMode } from '../types';
import {
  ApiError,
  buildPickupFileDownloadUrl,
  fetchPickupInfo,
} from '../lib/signaling';
import { formatBytes, formatExpiryTime } from '../lib/platform';
import { PalettePicker } from './Controls';

interface PickupPageProps {
  code: string;
  theme: ThemeMode;
  onSelectTheme: (t: ThemeMode) => void;
  onBackToLobby: () => void;
}

// /p/{code} 取件码提取页：显示文件列表、按需输入密码解锁、支持每文件断点下载
export const PickupPage: React.FC<PickupPageProps> = ({
  code,
  theme,
  onSelectTheme,
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
    <div className="aurora-canvas">
      <header className="top-glass-nav">
        <button type="button" className="btn btn-glass btn-sm" onClick={onBackToLobby}>
          <ArrowLeft size={14} /> 返回大厅
        </button>
        <div className="mono" style={{ fontSize: 15, fontWeight: 900 }}>
          取件码 #{code}
        </div>
        <PalettePicker theme={theme} onSelect={onSelectTheme} />
      </header>

      <main className="bento-scroll">
        <div style={{ maxWidth: 560, margin: '24px auto', width: '100%' }}>
          <section className="glass-bento" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {loading ? (
              <div style={{ padding: '28px 0', textAlign: 'center', color: 'var(--text-sub)' }}>
                正在查询取件码 #{code}...
              </div>
            ) : needPassword ? (
              <form onSubmit={handleUnlock} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 16, fontWeight: 800 }}>
                  <Lock size={18} style={{ color: 'var(--accent)' }} />
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
              <div style={{ display: 'flex', flexDirection: 'column', gap: 14, alignItems: 'center', padding: '18px 0' }}>
                <Package size={36} style={{ color: 'var(--text-muted)' }} />
                <div style={{ fontSize: 15, fontWeight: 700, textAlign: 'center' }}>
                  {errorText || '取件码不存在或已失效'}
                </div>
                <button type="button" className="btn btn-soft" onClick={onBackToLobby}>
                  回到湾聊首页
                </button>
              </div>
            ) : (
              <>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 8 }}>
                  <div>
                    <div className="mono" style={{ fontSize: 26, fontWeight: 900, letterSpacing: '0.12em' }}>
                      {info.code}
                    </div>
                    <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>
                      {formatExpiryTime(info.expiresAt)} · 已下载 {info.downloads} 次
                    </div>
                  </div>
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                    {info.burnAfterRead && (
                      <span className="pill pill-warm">
                        <Flame size={11} /> 取后即焚
                      </span>
                    )}
                    <button
                      type="button"
                      className="btn btn-ghost btn-icon"
                      style={{ width: 30, height: 30 }}
                      title="刷新状态"
                      onClick={() => void loadInfo(unlockedPassword)}
                    >
                      <RefreshCw size={14} />
                    </button>
                  </div>
                </div>

                {info.burnedAt ? (
                  <div className="alert-strip">
                    🔥 文件已全部领取，当前处于 10 分钟断点续传宽限期，到期后将自动销毁
                  </div>
                ) : null}

                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {info.files.map((f) => (
                    <div
                      key={f.fileId}
                      style={{
                        padding: '12px 14px',
                        borderRadius: 16,
                        background: 'var(--glass-bg-strong)',
                        border: '1px solid var(--line-border)',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        gap: 10,
                      }}
                    >
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
                        <div className="ext-jelly-box">
                          <FileIcon size={16} />
                        </div>
                        <div style={{ minWidth: 0 }}>
                          <div
                            style={{
                              fontSize: 14,
                              fontWeight: 700,
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap',
                            }}
                            title={f.name}
                          >
                            {f.name}
                          </div>
                          <div className="mono" style={{ fontSize: 11.5, color: 'var(--text-muted)' }}>
                            {formatBytes(f.size)} · {f.ready ? '可下载' : '发送方仍在上传...'}
                          </div>
                        </div>
                      </div>
                      {f.ready ? (
                        <a
                          href={buildPickupFileDownloadUrl(info.code, f.fileId, unlockedPassword)}
                          download={f.name}
                          className="btn btn-jelly btn-sm"
                          style={{ textDecoration: 'none', flexShrink: 0 }}
                        >
                          <Download size={13} /> 下载
                        </a>
                      ) : (
                        <span className="pill pill-neutral">等待就绪</span>
                      )}
                    </div>
                  ))}
                </div>
              </>
            )}
          </section>
        </div>
      </main>
    </div>
  );
};
