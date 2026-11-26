import React, { useEffect, useState } from 'react';
import { Check, KeyRound, Send, ShieldCheck, X } from 'lucide-react';
import type { PaperPlane, PlaneFileMeta, UserProfile } from '../types';
import { createPlane } from '../lib/signaling';
import { formatBytes } from '../lib/platform';
import { Switch } from './Controls';
import { renderPlaneKindIcon } from './PlaneViewerModal';

interface PlaneComposerModalProps {
  roomCode: string;
  hasPassword: boolean;
  isOwner: boolean;
  ownerToken?: string;
  roomPassword?: string;
  profile: UserProfile;
  recentFiles: PlaneFileMeta[];
  onClose: () => void;
  onSuccess: (plane: PaperPlane) => void;
  onToast: (msg: string) => void;
}

// 房间内「放飞纸飞机」对话框：默认勾选最近文件元数据，不上传文件内容
export const PlaneComposerModal: React.FC<PlaneComposerModalProps> = ({
  roomCode,
  hasPassword,
  isOwner,
  ownerToken,
  roomPassword,
  profile,
  recentFiles,
  onClose,
  onSuccess,
  onToast,
}) => {
  const [selectedIndices, setSelectedIndices] = useState<number[]>(() =>
    recentFiles.slice(0, 5).map((_, idx) => idx)
  );
  const [message, setMessage] = useState('');
  const canToggleBypass = !hasPassword || isOwner;
  const [allowEntry, setAllowEntry] = useState<boolean>(canToggleBypass);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !submitting) onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, submitting]);

  const toggleIndex = (idx: number) => {
    setSelectedIndices((prev) => {
      if (prev.includes(idx)) {
        return prev.filter((item) => item !== idx);
      }
      if (prev.length >= 5) {
        onToast('单架纸飞机最多附带 5 个文件信息');
        return prev;
      }
      return [...prev, idx];
    });
  };

  const chosenFiles = selectedIndices.map((idx) => recentFiles[idx]).filter(Boolean);
  const canSubmit = !submitting && (chosenFiles.length > 0 || message.trim().length > 0);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    try {
      const created = await createPlane({
        clientId: profile.clientId,
        roomCode,
        senderName: profile.name,
        senderAvatar: profile.avatar,
        message: message.trim(),
        files: chosenFiles,
        allowEntry,
        ownerToken: allowEntry && hasPassword ? ownerToken : undefined,
        password: allowEntry && hasPassword ? roomPassword : undefined,
      });
      onSuccess(created);
    } catch (err) {
      onToast(err instanceof Error ? err.message : '放飞纸飞机失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal-sheet plane-composer-sheet"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label="放飞纸飞机"
      >
        <div className="modal-head">
          <h3 className="modal-head-title">
            <Send size={17} className="lobby-card-title-icon" aria-hidden="true" />
            <span>放飞纸飞机（分享房间文件）</span>
          </h3>
          <button
            type="button"
            className="btn btn-ghost btn-icon modal-close-btn"
            style={{ width: 32, height: 32 }}
            onClick={onClose}
            aria-label="关闭弹窗"
            title="关闭"
          >
            <X size={16} aria-hidden="true" />
          </button>
        </div>

        <form onSubmit={(e) => void handleSubmit(e)} className="plane-composer-form">
          <div className="plane-composer-section">
            <div className="plane-composer-label-row">
              <span>选择公开展示的房间文件（仅清单信息，最多 5 个）</span>
              <span className="mono plane-composer-count">{chosenFiles.length}/5</span>
            </div>

            {recentFiles.length === 0 ? (
              <div className="plane-empty-files-note">
                当前房间暂无已传文件记录，可直接填写留言吸引伙伴加入。
              </div>
            ) : (
              <div className="plane-pick-list">
                {recentFiles.map((file, idx) => {
                  const selected = selectedIndices.includes(idx);
                  return (
                    <button
                      key={`${file.name}-${idx}`}
                      type="button"
                      className={`plane-pick-row ${selected ? 'selected' : ''}`}
                      onClick={() => toggleIndex(idx)}
                    >
                      <span className={`plane-check-box ${selected ? 'checked' : ''}`}>
                        {selected && <Check size={12} aria-hidden="true" />}
                      </span>
                      <span className="plane-locked-file-icon">
                        {renderPlaneKindIcon(file.kind, 15)}
                      </span>
                      <span className="plane-pick-name">{file.name}</span>
                      {file.size > 0 && (
                        <span className="mono plane-pick-size">{formatBytes(file.size)}</span>
                      )}
                    </button>
                  );
                })}
              </div>
            )}
          </div>

          <div className="plane-composer-section">
            <div className="plane-composer-label-row">
              <span>纸飞机留言（选填，最多 120 字）</span>
              <span className="mono plane-composer-count">{message.trim().length}/120</span>
            </div>
            <textarea
              className="field-input plane-message-input"
              rows={2}
              maxLength={120}
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              placeholder="例如：内含软件安装包与文档，点击可进房下载..."
            />
          </div>

          <div className="plane-composer-toggle-box">
            {hasPassword && !isOwner ? (
              <div className="plane-owner-lock-note">
                <ShieldCheck size={15} aria-hidden="true" />
                <span>加密房间仅房主可开启「允许通过纸飞机免密进入」，当前将作为「仅展示」放飞。</span>
              </div>
            ) : (
              <Switch
                checked={allowEntry}
                onChange={setAllowEntry}
                label="允许通过纸飞机进入房间"
                description={
                  hasPassword
                    ? '你是房主：开启后访客点开纸飞机可直接免密进房下载文件'
                    : '关闭后卡片仅作公开文件清单展示，不会自动带人进房'
                }
              />
            )}

            {hasPassword && isOwner && allowEntry && (
              <div className="plane-bypass-tip">
                <KeyRound size={13} aria-hidden="true" />
                <span>已启用房主免密入口，密码仅保存在服务端内存中，绝不公开展示。</span>
              </div>
            )}
          </div>

          <div className="modal-actions-end">
            <button type="button" className="btn btn-glass" onClick={onClose}>
              取消
            </button>
            <button type="submit" className="btn btn-jelly" disabled={!canSubmit}>
              <Send size={14} aria-hidden="true" />
              <span>{submitting ? '放飞中...' : '放飞到大厅广场'}</span>
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
