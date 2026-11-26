import React, { useEffect, useRef, useState } from 'react';
import {
  Check,
  Copy,
  Flame,
  KeyRound,
  Lock,
  Package,
  Plus,
  QrCode,
  Trash2,
  UploadCloud,
  X,
} from 'lucide-react';
import QRCode from 'qrcode';
import type { MyPickupRecord, PickupCreateResponse, ServerConfig } from '../types';
import {
  createPickup,
  deletePickup,
  headPickupFile,
  uploadPickupFile,
} from '../lib/signaling';
import { copyTextToClipboard, formatBytes, formatExpiryTime } from '../lib/platform';
import { loadMyPickups, removeMyPickup, upsertMyPickup } from '../lib/storage';
import { Switch } from './Controls';

// 将长文件名拆分为可省略的主干与始终完整显示的扩展名
function renderSplitFilename(name: string) {
  const dotIdx = name.lastIndexOf('.');
  const hasExt = dotIdx > 0 && dotIdx < name.length - 1 && name.length - dotIdx <= 12;
  if (!hasExt) {
    return (
      <span className="filename-split filename-split-sm" title={name}>
        <span className="filename-stem">{name}</span>
      </span>
    );
  }
  return (
    <span className="filename-split filename-split-sm" title={name}>
      <span className="filename-stem">{name.slice(0, dotIdx)}</span>
      <span className="filename-ext">{name.slice(dotIdx)}</span>
    </span>
  );
}

interface PickupSendModalProps {
  serverConfig: ServerConfig | null;
  onClose: () => void;
  onOpenCodePage: (code: string) => void;
  onToast: (msg: string) => void;
  onPickupsChange?: (records: MyPickupRecord[]) => void;
}

// 取件码发送与管理弹窗：选文件 -> 配有效期/即焚/密码 -> 流式上传（支持断点重试） -> 生成取件码与二维码
export const PickupSendModal: React.FC<PickupSendModalProps> = ({
  serverConfig,
  onClose,
  onOpenCodePage,
  onToast,
  onPickupsChange,
}) => {
  const [files, setFiles] = useState<File[]>([]);
  const [expireHours, setExpireHours] = useState<1 | 24 | 72>(24);
  const [burnAfterRead, setBurnAfterRead] = useState(false);
  const [password, setPassword] = useState('');
  const [uploading, setUploading] = useState(false);
  const [progressMap, setProgressMap] = useState<Record<number, number>>({});
  const [errorText, setErrorText] = useState('');
  const [activeSession, setActiveSession] = useState<PickupCreateResponse | null>(null);
  const [result, setResult] = useState<PickupCreateResponse | null>(null);
  const [qrDataUrl, setQrDataUrl] = useState('');
  const [copiedType, setCopiedType] = useState<'code' | 'link' | null>(null);
  const [myPickups, setMyPickups] = useState<MyPickupRecord[]>(() => loadMyPickups());
  const inputRef = useRef<HTMLInputElement>(null);

  const maxFileMB = serverConfig?.pickupMaxFileMB || 200;
  const maxTotalMB = serverConfig?.pickupMaxTotalMB || 500;
  const totalBytes = files.reduce((sum, f) => sum + f.size, 0);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !uploading) onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, uploading]);

  useEffect(() => {
    if (!result) return;
    const url = `${window.location.origin}/p/${result.code}`;
    let alive = true;
    QRCode.toDataURL(url, {
      width: 210,
      margin: 1,
      color: { dark: '#000000', light: '#ffffff' },
    })
      .then((dataUrl) => {
        if (alive) setQrDataUrl(dataUrl);
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [result]);

  const appendFiles = (incoming: FileList | File[]) => {
    const next = [...files];
    for (const f of Array.from(incoming)) {
      if (f.size <= 0) continue;
      if (f.size > maxFileMB * 1024 * 1024) {
        setErrorText(`「${f.name}」超过单文件上限 ${maxFileMB}MB`);
        continue;
      }
      if (!next.some((x) => x.name === f.name && x.size === f.size)) {
        next.push(f);
      }
    }
    setActiveSession(null);
    setProgressMap({});
    setFiles(next.slice(0, 50));
  };

  const handleUpload = async () => {
    setErrorText('');
    if (files.length === 0) {
      setErrorText('请先选择至少一个文件');
      return;
    }
    if (totalBytes > maxTotalMB * 1024 * 1024) {
      setErrorText(`总大小超过单次取件码上限 ${maxTotalMB}MB`);
      return;
    }

    setUploading(true);
    try {
      // 若之前上传中途失败，复用同一个取件码会话实现真正的服务端断点续传
      const created =
        activeSession ??
        (await createPickup({
          files: files.map((f) => ({
            name: f.name,
            size: f.size,
            mime: f.type || 'application/octet-stream',
          })),
          expireHours,
          burnAfterRead,
          password: password.trim() || undefined,
        }));
      if (!activeSession) {
        setActiveSession(created);
      }

      for (let i = 0; i < files.length; i++) {
        const file = files[i];
        const meta = created.files[i];
        if (!meta) continue;
        let offset = await headPickupFile(created.code, meta.fileId, created.ownerToken).catch(
          () => 0
        );
        if (offset >= file.size) {
          setProgressMap((prev) => ({ ...prev, [i]: file.size }));
          continue;
        }
        await uploadPickupFile(
          created.code,
          meta.fileId,
          created.ownerToken,
          file,
          offset,
          (bytes) => {
            setProgressMap((prev) => ({ ...prev, [i]: bytes }));
          }
        );
      }

      const nextList = upsertMyPickup({
        code: created.code,
        ownerToken: created.ownerToken,
        expiresAt: created.expiresAt,
        fileCount: files.length,
        totalSize: totalBytes,
        burnAfterRead,
        hasPassword: Boolean(password.trim()),
        createdAt: Date.now(),
      });
      setMyPickups(nextList);
      onPickupsChange?.(nextList);
      setActiveSession(null);
      setResult(created);
      onToast(`取件码 #${created.code} 已生成`);
    } catch (err) {
      setErrorText(err instanceof Error ? err.message : '取件码上传失败（保留进度，点击可断点续传）');
    } finally {
      setUploading(false);
    }
  };

  const handleRevoke = async (item: MyPickupRecord) => {
    try {
      await deletePickup(item.code, item.ownerToken);
      const nextPickups = removeMyPickup(item.code);
      setMyPickups(nextPickups);
      onPickupsChange?.(nextPickups);
      if (result?.code === item.code) {
        setResult(null);
        setFiles([]);
      }
      onToast(`已撤销取件码 #${item.code}`);
    } catch (err) {
      onToast(err instanceof Error ? err.message : '撤销失败');
    }
  };

  const handleCopy = async (text: string, type: 'code' | 'link') => {
    if (await copyTextToClipboard(text)) {
      setCopiedType(type);
      setTimeout(() => setCopiedType(null), 1600);
    }
  };

  return (
    <div className="modal-overlay" onClick={() => !uploading && onClose()}>
      <div
        className="modal-sheet modal-sheet-lg"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label="取件码离线文件投递"
      >
        <div className="modal-head">
          <h3 className="modal-head-title">
            <Package size={18} className="lobby-card-title-icon" aria-hidden="true" />
            <span>取件码 · 离线文件投递</span>
          </h3>
          <button
            type="button"
            className="btn btn-ghost btn-icon modal-close-btn"
            disabled={uploading}
            aria-label="关闭弹窗"
            onClick={onClose}
          >
            <X size={16} aria-hidden="true" />
          </button>
        </div>

        {result ? (
          <div className="modal-stack">
            <div className="modal-card-panel">
              <div className="modal-pills-row">
                <span className="pill pill-emerald">已就绪 · {result.files.length} 个文件</span>
                {burnAfterRead && (
                  <span className="pill pill-warm">
                    <Flame size={11} aria-hidden="true" /> 取后即焚
                  </span>
                )}
                {password.trim() && (
                  <span className="pill pill-neutral">
                    <Lock size={11} aria-hidden="true" /> 加密取件
                  </span>
                )}
              </div>
              {qrDataUrl && (
                <div className="qr-quiet-frame">
                  <img src={qrDataUrl} alt={`取件码 ${result.code} 的二维码`} className="qr-img-pickup" />
                </div>
              )}
              <div className="modal-code-box">
                <div className="radar-code-display mono modal-code-lg">
                  {result.code}
                </div>
                <div className="modal-sub-caption">
                  {formatExpiryTime(result.expiresAt)}
                </div>
              </div>
            </div>

            <div className="lobby-two-btn-grid">
              <button
                type="button"
                className="btn btn-soft lobby-touch-pill"
                onClick={() => void handleCopy(result.code, 'code')}
              >
                {copiedType === 'code' ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
                {copiedType === 'code' ? '已复制' : '复制取件码'}
              </button>
              <button
                type="button"
                className="btn btn-jelly lobby-touch-pill"
                onClick={() =>
                  void handleCopy(`${window.location.origin}/p/${result.code}`, 'link')
                }
              >
                {copiedType === 'link' ? <Check size={14} aria-hidden="true" /> : <QrCode size={14} aria-hidden="true" />}
                {copiedType === 'link' ? '已复制链接' : '复制提取链接'}
              </button>
            </div>

            <div className="pickup-action-row">
              <button
                type="button"
                className="btn btn-glass btn-sm pickup-action-flex lobby-touch-pill"
                onClick={() => {
                  onClose();
                  onOpenCodePage(result.code);
                }}
              >
                打开取件页预览
              </button>
              <button
                type="button"
                className="btn btn-glass btn-sm pickup-action-flex lobby-touch-pill"
                onClick={() => {
                  setResult(null);
                  setFiles([]);
                  setProgressMap({});
                  setPassword('');
                }}
              >
                <Plus size={13} aria-hidden="true" /> 再投递一批
              </button>
            </div>
          </div>
        ) : (
          <div className="modal-stack">
            <div
              role="button"
              tabIndex={uploading ? -1 : 0}
              aria-label="选择或拖拽要上传的文件"
              className={`pickup-dropzone ${uploading ? 'disabled' : ''}`}
              onClick={() => !uploading && inputRef.current?.click()}
              onKeyDown={(e) => {
                if (!uploading && (e.key === 'Enter' || e.key === ' ')) {
                  e.preventDefault();
                  inputRef.current?.click();
                }
              }}
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => {
                e.preventDefault();
                if (!uploading && e.dataTransfer.files.length > 0) {
                  appendFiles(e.dataTransfer.files);
                }
              }}
            >
              <UploadCloud size={26} className="pickup-dropzone-icon" aria-hidden="true" />
              <div className="pickup-dropzone-title">
                点击或拖拽添加文件（单文件 ≤ {maxFileMB}MB，总计 ≤ {maxTotalMB}MB）
              </div>
              <div className="pickup-dropzone-sub">
                生成 6 位数字取件码，跨设备随时提取
              </div>
              <input
                ref={inputRef}
                type="file"
                multiple
                hidden
                onChange={(e) => {
                  if (e.target.files) appendFiles(e.target.files);
                  e.target.value = '';
                }}
              />
            </div>

            {files.length > 0 && (
              <div className="pickup-file-list">
                {files.map((f, idx) => {
                  const uploaded = progressMap[idx] || 0;
                  const pct = f.size > 0 ? Math.min(100, Math.round((uploaded / f.size) * 100)) : 0;
                  return (
                    <div key={`${f.name}-${f.size}`} className="pickup-file-row">
                      <div className="pickup-file-top">
                        {renderSplitFilename(f.name)}
                        <div className="pickup-file-meta">
                          <span className="mono pickup-file-size">
                            {formatBytes(f.size)}
                          </span>
                          {!uploading && (
                            <button
                              type="button"
                              className="btn btn-ghost btn-icon pickup-file-remove"
                              aria-label={`移除文件 ${f.name}`}
                              onClick={() => {
                                setActiveSession(null);
                                setProgressMap({});
                                setFiles((prev) => prev.filter((_, i) => i !== idx));
                              }}
                            >
                              <X size={12} aria-hidden="true" />
                            </button>
                          )}
                        </div>
                      </div>
                      {uploading && (
                        <div
                          className="progress-rail"
                          role="progressbar"
                          aria-valuenow={pct}
                          aria-valuemin={0}
                          aria-valuemax={100}
                        >
                          <div className="progress-bar" style={{ width: `${pct}%` }} />
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}

            <div className="lobby-field-group">
              <span className="lobby-field-label">取件码有效期</span>
              <div className="segmented full">
                {([1, 24, 72] as const).map((h) => (
                  <button
                    key={h}
                    type="button"
                    className={`seg-item ${expireHours === h ? 'active' : ''}`}
                    disabled={uploading}
                    onClick={() => setExpireHours(h)}
                  >
                    {h === 1 ? '1 小时' : h === 24 ? '24 小时' : '72 小时'}
                  </button>
                ))}
              </div>
            </div>

            <Switch
              checked={burnAfterRead}
              onChange={(v) => !uploading && setBurnAfterRead(v)}
              label="取后即焚"
              description="全部文件被完整下载一次后，宽限 10 分钟自动物理销毁"
            />

            <div className="input-group">
              <span className="input-prefix-icon">
                <Lock size={14} aria-hidden="true" />
              </span>
              <input
                className="field-input with-prefix"
                type="password"
                disabled={uploading}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="可选：设置取件密码（留空免密）"
              />
            </div>

            {errorText && <div className="alert-strip">{errorText}</div>}

            <button
              type="button"
              className="btn btn-jelly btn-xl"
              disabled={uploading || files.length === 0}
              onClick={() => void handleUpload()}
            >
              <Package size={16} aria-hidden="true" />
              <span>
                {uploading
                  ? '正在上传生成取件码...'
                  : `生成取件码（${files.length} 个文件 · ${formatBytes(totalBytes)}）`}
              </span>
            </button>
          </div>
        )}

        {myPickups.length > 0 && (
          <div className="pickup-my-section">
            <div className="pickup-my-title">
              我的取件码（{myPickups.length}）
            </div>
            {myPickups.slice(0, 6).map((item) => (
              <div key={item.code} className="pickup-my-row">
                <div className="lobby-room-meta">
                  <div className="mono lobby-room-code">
                    <span>#{item.code}</span>
                    {item.burnAfterRead && <Flame size={11} className="lobby-room-lock" aria-hidden="true" />}
                    {item.hasPassword && <Lock size={11} className="lobby-room-lock" aria-hidden="true" />}
                  </div>
                  <div className="lobby-room-sub mono">
                    {item.fileCount} 文件 · {formatBytes(item.totalSize)} · {formatExpiryTime(item.expiresAt)}
                  </div>
                </div>
                <div className="lobby-room-actions">
                  <button
                    type="button"
                    className="btn btn-soft btn-sm lobby-touch-pill"
                    onClick={() => {
                      onClose();
                      onOpenCodePage(item.code);
                    }}
                  >
                    查看
                  </button>
                  <button
                    type="button"
                    className="btn btn-ghost btn-icon lobby-touch-icon"
                    title="复制取件链接"
                    aria-label={`复制取件码 ${item.code} 的链接`}
                    onClick={async () => {
                      const url = `${window.location.origin}/p/${item.code}`;
                      if (await copyTextToClipboard(url)) onToast(`已复制 #${item.code} 取件链接`);
                    }}
                  >
                    <Copy size={13} aria-hidden="true" />
                  </button>
                  <button
                    type="button"
                    className="btn btn-danger-soft btn-icon lobby-touch-icon"
                    title="撤销并销毁"
                    aria-label={`撤销取件码 ${item.code}`}
                    onClick={() => void handleRevoke(item)}
                  >
                    <Trash2 size={13} aria-hidden="true" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

interface PickupLookupModalProps {
  onClose: () => void;
  onSubmitCode: (code: string) => void;
}

// 输入取件码快捷弹窗
export const PickupLookupModal: React.FC<PickupLookupModalProps> = ({
  onClose,
  onSubmitCode,
}) => {
  const [codeInput, setCodeInput] = useState('');
  const [err, setErr] = useState('');

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const cleaned = codeInput.trim().replace(/^.*\/p\//i, '').replace(/\D/g, '');
    if (!/^\d{6}$/.test(cleaned)) {
      setErr('请输入 6 位数字取件码');
      return;
    }
    onSubmitCode(cleaned);
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal-sheet modal-sheet-sm"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label="输入取件码提取文件"
      >
        <div className="modal-head">
          <h3 className="modal-head-title">
            <KeyRound size={18} className="lobby-card-title-icon" aria-hidden="true" />
            <span>输入取件码提取文件</span>
          </h3>
          <button
            type="button"
            className="btn btn-ghost btn-icon modal-close-btn"
            aria-label="关闭弹窗"
            onClick={onClose}
          >
            <X size={16} aria-hidden="true" />
          </button>
        </div>
        <form onSubmit={handleSubmit} className="modal-stack">
          <input
            className="field-input mono pickup-code-input"
            value={codeInput}
            onChange={(e) => {
              setCodeInput(e.target.value);
              setErr('');
            }}
            placeholder="000000"
            maxLength={32}
            autoFocus
            aria-label="6 位数字取件码"
          />
          {err && <div className="alert-strip">{err}</div>}
          <button type="submit" className="btn btn-jelly btn-xl">
            立即提取文件
          </button>
        </form>
      </div>
    </div>
  );
};
