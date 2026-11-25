import React, { useEffect, useRef, useState } from 'react';
import {
  Check,
  Copy,
  Flame,
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

interface PickupSendModalProps {
  serverConfig: ServerConfig | null;
  onClose: () => void;
  onOpenCodePage: (code: string) => void;
  onToast: (msg: string) => void;
}

// 取件码发送与管理弹窗：选文件 -> 配有效期/即焚/密码 -> 流式上传（支持断点重试） -> 生成取件码与二维码
export const PickupSendModal: React.FC<PickupSendModalProps> = ({
  serverConfig,
  onClose,
  onOpenCodePage,
  onToast,
}) => {
  const [files, setFiles] = useState<File[]>([]);
  const [expireHours, setExpireHours] = useState<1 | 24 | 72>(24);
  const [burnAfterRead, setBurnAfterRead] = useState(false);
  const [password, setPassword] = useState('');
  const [uploading, setUploading] = useState(false);
  const [progressMap, setProgressMap] = useState<Record<number, number>>({});
  const [errorText, setErrorText] = useState('');
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
      color: { dark: '#111318', light: '#ffffff' },
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
      const created = await createPickup({
        files: files.map((f) => ({
          name: f.name,
          size: f.size,
          mime: f.type || 'application/octet-stream',
        })),
        expireHours,
        burnAfterRead,
        password: password.trim() || undefined,
      });

      for (let i = 0; i < files.length; i++) {
        const file = files[i];
        const meta = created.files[i];
        if (!meta) continue;
        let offset = await headPickupFile(created.code, meta.fileId, created.ownerToken).catch(
          () => 0
        );
        if (offset >= file.size) offset = 0;
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
      setResult(created);
      onToast(`📦 取件码 #${created.code} 已生成`);
    } catch (err) {
      setErrorText(err instanceof Error ? err.message : '取件码上传失败');
    } finally {
      setUploading(false);
    }
  };

  const handleRevoke = async (item: MyPickupRecord) => {
    try {
      await deletePickup(item.code, item.ownerToken);
      setMyPickups(removeMyPickup(item.code));
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
        className="modal-sheet"
        onClick={(e) => e.stopPropagation()}
        style={{ maxWidth: 480, maxHeight: '88vh', overflowY: 'auto' }}
      >
        <div className="modal-head">
          <h3>📦 取件码 · 离线文件投递</h3>
          <button
            type="button"
            className="btn btn-ghost btn-icon"
            disabled={uploading}
            onClick={onClose}
          >
            <X size={16} />
          </button>
        </div>

        {result ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div
              style={{
                padding: '18px 16px',
                borderRadius: 20,
                background: 'var(--glass-subtle)',
                border: '1px solid var(--line-border)',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: 12,
              }}
            >
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', justifyContent: 'center' }}>
                <span className="pill pill-emerald">已就绪 · {result.files.length} 个文件</span>
                {burnAfterRead && (
                  <span className="pill pill-warm">
                    <Flame size={11} /> 取后即焚
                  </span>
                )}
                {password.trim() && (
                  <span className="pill pill-neutral">
                    <Lock size={11} /> 加密取件
                  </span>
                )}
              </div>
              {qrDataUrl && (
                <div className="radar-qr-ring">
                  <img src={qrDataUrl} alt="取件码二维码" style={{ width: 164, height: 164 }} />
                </div>
              )}
              <div style={{ textAlign: 'center' }}>
                <div className="radar-code-display mono" style={{ fontSize: 30 }}>
                  {result.code}
                </div>
                <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 4 }}>
                  {formatExpiryTime(result.expiresAt)}
                </div>
              </div>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
              <button
                type="button"
                className="btn btn-soft"
                onClick={() => void handleCopy(result.code, 'code')}
              >
                {copiedType === 'code' ? <Check size={14} /> : <Copy size={14} />}
                {copiedType === 'code' ? '已复制' : '复制取件码'}
              </button>
              <button
                type="button"
                className="btn btn-jelly"
                onClick={() =>
                  void handleCopy(`${window.location.origin}/p/${result.code}`, 'link')
                }
              >
                {copiedType === 'link' ? <Check size={14} /> : <QrCode size={14} />}
                {copiedType === 'link' ? '已复制链接' : '复制提取链接'}
              </button>
            </div>

            <div style={{ display: 'flex', gap: 8 }}>
              <button
                type="button"
                className="btn btn-glass btn-sm"
                style={{ flex: 1 }}
                onClick={() => {
                  onClose();
                  onOpenCodePage(result.code);
                }}
              >
                打开取件页预览
              </button>
              <button
                type="button"
                className="btn btn-glass btn-sm"
                style={{ flex: 1 }}
                onClick={() => {
                  setResult(null);
                  setFiles([]);
                  setProgressMap({});
                  setPassword('');
                }}
              >
                <Plus size={13} /> 再投递一批
              </button>
            </div>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div
              onClick={() => !uploading && inputRef.current?.click()}
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => {
                e.preventDefault();
                if (!uploading && e.dataTransfer.files.length > 0) {
                  appendFiles(e.dataTransfer.files);
                }
              }}
              style={{
                padding: '18px 14px',
                borderRadius: 18,
                border: '1.5px dashed var(--line-strong)',
                background: 'var(--glass-subtle)',
                textAlign: 'center',
                cursor: uploading ? 'not-allowed' : 'pointer',
              }}
            >
              <UploadCloud size={26} style={{ color: 'var(--accent)', marginBottom: 6 }} />
              <div style={{ fontSize: 13.5, fontWeight: 700 }}>
                点击或拖拽添加文件（单文件 ≤ {maxFileMB}MB，总计 ≤ {maxTotalMB}MB）
              </div>
              <div style={{ fontSize: 11.5, color: 'var(--text-muted)', marginTop: 3 }}>
                生成 6 位数字取件码，跨设备随时提取
              </div>
              <input
                ref={inputRef}
                type="file"
                multiple
                style={{ display: 'none' }}
                onChange={(e) => {
                  if (e.target.files) appendFiles(e.target.files);
                  e.target.value = '';
                }}
              />
            </div>

            {files.length > 0 && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6, maxHeight: 160, overflowY: 'auto' }}>
                {files.map((f, idx) => {
                  const uploaded = progressMap[idx] || 0;
                  const pct = f.size > 0 ? Math.min(100, Math.round((uploaded / f.size) * 100)) : 0;
                  return (
                    <div
                      key={`${f.name}-${f.size}`}
                      style={{
                        padding: '7px 10px',
                        borderRadius: 12,
                        background: 'var(--glass-bg-strong)',
                        border: '1px solid var(--line-border)',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: 4,
                      }}
                    >
                      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                        <span style={{ fontSize: 12.5, fontWeight: 700, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {f.name}
                        </span>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
                          <span className="mono" style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                            {formatBytes(f.size)}
                          </span>
                          {!uploading && (
                            <button
                              type="button"
                              className="btn btn-ghost btn-icon"
                              style={{ width: 22, height: 22 }}
                              onClick={() => setFiles((prev) => prev.filter((_, i) => i !== idx))}
                            >
                              <X size={12} />
                            </button>
                          )}
                        </div>
                      </div>
                      {uploading && (
                        <div className="progress-rail">
                          <div className="progress-bar" style={{ width: `${pct}%` }} />
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}

            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-sub)' }}>
                取件码有效期
              </span>
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
                <Lock size={14} />
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
              <Package size={16} />
              <span>
                {uploading
                  ? '正在上传生成取件码...'
                  : `生成取件码（${files.length} 个文件 · ${formatBytes(totalBytes)}）`}
              </span>
            </button>
          </div>
        )}

        {myPickups.length > 0 && (
          <div style={{ marginTop: 14, paddingTop: 12, borderTop: '1px solid var(--line-border)', display: 'flex', flexDirection: 'column', gap: 8 }}>
            <div style={{ fontSize: 12.5, fontWeight: 800, color: 'var(--text-sub)' }}>
              我的取件码（{myPickups.length}）
            </div>
            {myPickups.slice(0, 6).map((item) => (
              <div
                key={item.code}
                style={{
                  padding: '8px 10px',
                  borderRadius: 14,
                  background: 'var(--glass-bg-strong)',
                  border: '1px solid var(--line-border)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 8,
                }}
              >
                <div style={{ minWidth: 0 }}>
                  <div className="mono" style={{ fontSize: 14, fontWeight: 800, display: 'flex', alignItems: 'center', gap: 5 }}>
                    <span>#{item.code}</span>
                    {item.burnAfterRead && <Flame size={11} style={{ color: 'var(--accent)' }} />}
                    {item.hasPassword && <Lock size={11} style={{ color: 'var(--accent)' }} />}
                  </div>
                  <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                    {item.fileCount} 文件 · {formatBytes(item.totalSize)} · {formatExpiryTime(item.expiresAt)}
                  </div>
                </div>
                <div style={{ display: 'flex', gap: 4 }}>
                  <button
                    type="button"
                    className="btn btn-soft btn-sm"
                    onClick={() => {
                      onClose();
                      onOpenCodePage(item.code);
                    }}
                  >
                    查看
                  </button>
                  <button
                    type="button"
                    className="btn btn-ghost btn-icon"
                    style={{ width: 28, height: 28 }}
                    title="复制取件链接"
                    onClick={async () => {
                      const url = `${window.location.origin}/p/${item.code}`;
                      if (await copyTextToClipboard(url)) onToast(`已复制 #${item.code} 取件链接`);
                    }}
                  >
                    <Copy size={13} />
                  </button>
                  <button
                    type="button"
                    className="btn btn-danger-soft btn-icon"
                    style={{ width: 28, height: 28 }}
                    title="撤销并销毁"
                    onClick={() => void handleRevoke(item)}
                  >
                    <Trash2 size={13} />
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
      <div className="modal-sheet" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 380 }}>
        <div className="modal-head">
          <h3>🔑 输入取件码提取文件</h3>
          <button type="button" className="btn btn-ghost btn-icon" onClick={onClose}>
            <X size={16} />
          </button>
        </div>
        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <input
            className="field-input mono"
            style={{ height: 50, fontSize: 20, fontWeight: 800, textAlign: 'center', letterSpacing: '0.2em' }}
            value={codeInput}
            onChange={(e) => {
              setCodeInput(e.target.value);
              setErr('');
            }}
            placeholder="000000"
            maxLength={32}
            autoFocus
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
