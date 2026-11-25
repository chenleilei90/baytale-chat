import React, { useEffect, useState } from 'react';
import {
  Flame,
  Pin,
  Lock,
  ArrowRight,
  Copy,
  Trash2,
  Send,
  Sparkles,
  Dices,
  ClipboardPaste,
  AlertCircle,
  Wand2,
  Clock,
  SlidersHorizontal,
  ChevronDown,
  ChevronUp,
  Globe,
  X,
} from 'lucide-react';
import type {
  RecentRoom,
  RoomMode,
  SavedFixedRoom,
  ServerConfig,
  ThemeMode,
  UserProfile,
} from '../types';
import {
  ApiError,
  createRoom,
  extractRoomCodeInput,
  fetchRoomInfo,
  pickRandomPublicRoom,
  verifyRoomPassword,
} from '../lib/signaling';
import { copyTextToClipboard, formatRelativeTime } from '../lib/platform';
import { generateRandomName, pickRandomPresetAvatar } from '../lib/avatar';
import { Avatar } from './Avatar';
import { PalettePicker, Switch } from './Controls';

interface LobbyProps {
  profile: UserProfile;
  theme: ThemeMode;
  serverConfig: ServerConfig | null;
  savedFixedRooms: SavedFixedRoom[];
  recentRooms: RecentRoom[];
  initialJoinCode?: string;
  initialNotice?: string;
  onSelectTheme: (theme: ThemeMode) => void;
  onQuickRandomProfile: (next: UserProfile) => void;
  onEditProfile: () => void;
  onEnterRoom: (code: string, password: string, mode: RoomMode, rememberFixed?: boolean) => void;
  onRemoveSavedRoom: (code: string) => void;
  onRemoveRecentRoom: (code: string) => void;
  onClearRecentRooms: () => void;
  onToast: (msg: string) => void;
}

const RETENTION_STEPS = [
  { days: 0, label: '阅后即焚' },
  { days: 1, label: '1天' },
  { days: 2, label: '2天' },
  { days: 3, label: '3天' },
  { days: 5, label: '5天' },
  { days: 7, label: '7天' },
];

const FILE_LIMIT_PRESETS = [
  { mb: 50, label: '50MB' },
  { mb: 200, label: '200MB' },
  { mb: 500, label: '500MB' },
  { mb: 1024, label: '1GB' },
  { mb: 0, label: '不限制' },
];

// 多巴胺流光 Bento 启动台：声波脉冲雷达 + 5色主题球 + 糖果模式卡 + 创意玩法工坊
export const Lobby: React.FC<LobbyProps> = ({
  profile,
  theme,
  serverConfig,
  savedFixedRooms,
  recentRooms,
  initialJoinCode,
  initialNotice,
  onSelectTheme,
  onQuickRandomProfile,
  onEditProfile,
  onEnterRoom,
  onRemoveSavedRoom,
  onRemoveRecentRoom,
  onClearRecentRooms,
  onToast,
}) => {
  const [roomInput, setRoomInput] = useState(initialJoinCode || '');
  const [roomPassword, setRoomPassword] = useState('');
  const [needPassword, setNeedPassword] = useState(false);
  const [showAdvanced, setShowAdvanced] = useState(false);

  const [mode, setMode] = useState<RoomMode>('ephemeral');
  const [allowRandom, setAllowRandom] = useState(true); // 默认允许陌生人随机串门（有密码时自动失效）
  const [privatePairOnly, setPrivatePairOnly] = useState(false);
  const [retentionDays, setRetentionDays] = useState(1);
  const [maxFileSizeMB, setMaxFileSizeMB] = useState<number>(
    serverConfig?.defaultRoomMaxFileMB ?? 500
  );
  const [saveToFavorites, setSaveToFavorites] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [randomJoining, setRandomJoining] = useState(false);
  const [errorText, setErrorText] = useState(initialNotice || '');

  useEffect(() => {
    if (serverConfig && typeof serverConfig.defaultRoomMaxFileMB === 'number') {
      setMaxFileSizeMB(serverConfig.defaultRoomMaxFileMB);
    }
  }, [serverConfig?.defaultRoomMaxFileMB]);

  useEffect(() => {
    if (initialJoinCode) {
      setRoomInput(initialJoinCode);
      void probeRoomInput(initialJoinCode);
    }
  }, [initialJoinCode]);

  useEffect(() => {
    if (initialNotice) setErrorText(initialNotice);
  }, [initialNotice]);

  const probeRoomInput = async (raw: string) => {
    const code = extractRoomCodeInput(raw);
    if (!code) {
      setNeedPassword(false);
      return;
    }
    try {
      const info = await fetchRoomInfo(code);
      setNeedPassword(info.hasPassword);
      setErrorText('');
    } catch (err) {
      if (err instanceof ApiError && err.errorCode === 'ROOM_NOT_FOUND') {
        setNeedPassword(false);
        setErrorText('');
      } else if (err instanceof ApiError) {
        setErrorText(err.message);
      }
    }
  };

  const handleModeChange = (nextMode: RoomMode) => {
    setMode(nextMode);
    setErrorText('');
    if (nextMode === 'ephemeral' && retentionDays === 3) setRetentionDays(1);
    else if (nextMode === 'fixed' && retentionDays === 1) setRetentionDays(3);
  };

  // 单入口智能直达：留空一键开房；填口令/链接时「已存在直接进，不存在自动以此号建常驻房并进」
  const handleUnifiedSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorText('');
    const code = extractRoomCodeInput(roomInput);
    const cleanPwd = roomPassword.trim();
    const maxLimit = serverConfig?.maxRoomPeers || 6;

    setSubmitting(true);
    try {
      if (!code) {
        // 留空：零配置秒开新房
        const summary = await createRoom({
          mode,
          password: cleanPwd || undefined,
          allowRandom: cleanPwd ? false : allowRandom,
          maxPeers: privatePairOnly ? 2 : maxLimit,
          retentionDays,
          maxFileSizeMB,
        });
        onEnterRoom(summary.code, cleanPwd, summary.mode, mode === 'fixed' && saveToFavorites);
        return;
      }

      // 输入了口令/链接：先探测是否已存在
      let existing = null;
      try {
        existing = await fetchRoomInfo(code);
      } catch (err) {
        if (err instanceof ApiError && err.errorCode === 'ROOM_NOT_FOUND') {
          existing = null;
        } else {
          throw err;
        }
      }

      if (existing) {
        if (existing.hasPassword) {
          if (!cleanPwd) {
            setNeedPassword(true);
            setErrorText(`房间 #${code} 设有访问暗号，请输入密码后进入`);
            return;
          }
          await verifyRoomPassword(code, cleanPwd);
        }
        onEnterRoom(existing.code, cleanPwd, existing.mode, existing.mode === 'fixed');
      } else {
        // 不存在则自动以此号创建常驻房间并进入
        const summary = await createRoom({
          mode: 'fixed',
          code,
          password: cleanPwd || undefined,
          allowRandom: cleanPwd ? false : allowRandom,
          maxPeers: privatePairOnly ? 2 : maxLimit,
          retentionDays,
          maxFileSizeMB,
        });
        onToast(`✨ 未找到 #${code}，已为你自动创建专属房间并进入`);
        onEnterRoom(summary.code, cleanPwd, summary.mode, saveToFavorites);
      }
    } catch (err) {
      if (err instanceof ApiError && err.errorCode === 'WRONG_PASSWORD') {
        setNeedPassword(true);
      }
      setErrorText(err instanceof Error ? err.message : '进入房间失败');
    } finally {
      setSubmitting(false);
    }
  };

  const handleRandomJoin = async () => {
    setErrorText('');
    setRandomJoining(true);
    try {
      const target = await pickRandomPublicRoom();
      onToast(`🎲 已为你空降免密房间 #${target.code}`);
      onEnterRoom(target.code, '', target.mode, false);
    } catch (err) {
      setErrorText(err instanceof Error ? err.message : '当前暂无可随机进入的免密公开房间');
    } finally {
      setRandomJoining(false);
    }
  };

  const handleQuickEnterSaved = async (item: SavedFixedRoom) => {
    setErrorText('');
    try {
      const info = await fetchRoomInfo(item.code);
      if (info.hasPassword && !item.password) {
        setRoomInput(item.code);
        setNeedPassword(true);
        setErrorText('请输入该常驻房间的密码');
        return;
      }
      if (info.hasPassword && item.password) {
        await verifyRoomPassword(item.code, item.password);
      }
      onEnterRoom(item.code, item.password || '', 'fixed', true);
    } catch (err) {
      setRoomInput(item.code);
      if (err instanceof ApiError && err.errorCode === 'WRONG_PASSWORD') {
        setNeedPassword(true);
      }
      setErrorText(err instanceof Error ? err.message : '无法进入房间');
    }
  };

  const handleQuickEnterRecent = async (item: RecentRoom) => {
    setErrorText('');
    try {
      const info = await fetchRoomInfo(item.code);
      if (info.hasPassword && !item.password) {
        setRoomInput(item.code);
        setNeedPassword(true);
        setErrorText('请输入该房间的访问密码');
        return;
      }
      if (info.hasPassword && item.password) {
        await verifyRoomPassword(item.code, item.password);
      }
      onEnterRoom(item.code, item.password || '', info.mode, false);
    } catch (err) {
      if (
        err instanceof ApiError &&
        (err.errorCode === 'ROOM_NOT_FOUND' || err.errorCode === 'ROOM_EXPIRED')
      ) {
        onRemoveRecentRoom(item.code);
        onToast(`临时房间 #${item.code} 已销毁或过期，已从最近记录移除`);
        return;
      }
      setRoomInput(item.code);
      if (err instanceof ApiError && err.errorCode === 'WRONG_PASSWORD') {
        setNeedPassword(true);
      }
      setErrorText(err instanceof Error ? err.message : '无法进入该房间');
    }
  };

  const handlePasteClipboard = async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (text) {
        setRoomInput(text.trim());
        void probeRoomInput(text);
      }
    } catch {
      onToast('请直接在输入框内按 Ctrl+V 粘贴');
    }
  };

  const hasInputCode = roomInput.trim().length > 0;
  const hasPasswordInput = roomPassword.trim().length > 0;
  const summaryTag = `${mode === 'fixed' || hasInputCode ? '常驻' : '临时'}·${retentionDays === 0 ? '即焚' : `${retentionDays}天`}·${maxFileSizeMB === 0 ? '不限' : maxFileSizeMB >= 1024 ? `${(maxFileSizeMB / 1024).toFixed(0)}GB` : `${maxFileSizeMB}MB`}`;

  const domainDisplay = serverConfig?.domain || window.location.host;

  return (
    <div className="aurora-canvas">
      {/* 顶部流光毛玻璃胶囊导航 */}
      <header className="top-glass-nav">
        <div className="brand-pill">
          <div className="brand-jelly-logo">
            <Send size={18} />
          </div>
          <div>
            <div className="brand-title-text">湾聊 · BayChat</div>
            <div className="brand-subtitle">
              {domainDisplay} · 跨端 P2P 直传
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <PalettePicker theme={theme} onSelect={onSelectTheme} />

          <button
            type="button"
            className="user-chip"
            onClick={onEditProfile}
            title="点击定制专属头像与昵称"
          >
            <Avatar name={profile.name} avatar={profile.avatar} size={32} />
            <span
              className="hide-mobile"
              style={{
                fontSize: 13.5,
                fontWeight: 700,
                maxWidth: 110,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {profile.name}
            </span>
          </button>
        </div>
      </header>

      {/* 主体多巴胺双栏 Bento 舞台 */}
      <main className="bento-scroll">
        <div className="bento-stage" style={{ alignItems: 'start' }}>
          {/* 左主卡：声波引力头图 + 房间发射台 */}
          <section className="glass-bento">
            <div className="sonic-hero">
              <div className="sonic-rings" onClick={onEditProfile} style={{ cursor: 'pointer' }}>
                <Avatar name={profile.name} avatar={profile.avatar} size={46} />
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                  <span style={{ fontSize: 18, fontWeight: 800, letterSpacing: '-0.02em' }}>
                    把文件与灵感，嗖一下传过去 ✨
                  </span>
                  <span className="pill pill-emerald">
                    <span className="status-dot emerald" /> P2P 满速直连
                  </span>
                </div>
                <div style={{ fontSize: 12.5, color: 'var(--text-sub)', marginTop: 4 }}>
                  跨公网终端直连 + TURN 智能兜底，支持 GB 级大文件直传、实时聊天、语音连麦与协作白板
                </div>
              </div>
            </div>

            <form
              onSubmit={handleUnifiedSubmit}
              style={{ display: 'flex', flexDirection: 'column', gap: 14 }}
            >
              {/* 单入口超级输入框：留空一键开房，填口令有就进/无就建 */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                <div className="lobby-field-head">
                  <span>⚡ 快速直达口令 / 基地号 / 邀请链接</span>
                  <span style={{ color: 'var(--accent)', fontWeight: 700 }}>
                    {hasInputCode ? '有就进 · 无就自动建房' : '留空点按钮 0.5 秒开房'}
                  </span>
                </div>
                <div className="input-group">
                  <input
                    className="field-input with-suffix mono"
                    style={{ height: 54, fontSize: 16, fontWeight: 800, letterSpacing: '0.02em', paddingRight: 108 }}
                    value={roomInput}
                    onChange={(e) => {
                      setRoomInput(e.target.value);
                      setErrorText('');
                      if (!e.target.value.trim()) setNeedPassword(false);
                    }}
                    onBlur={() => void probeRoomInput(roomInput)}
                    placeholder="留空秒开，或输 849205 / bay-8888"
                  />
                  <div className="input-suffix" style={{ right: 8, display: 'flex', alignItems: 'center', gap: 4 }}>
                    {roomInput && (
                      <button
                        type="button"
                        className="btn btn-ghost btn-sm"
                        style={{ padding: '0 6px' }}
                        onClick={() => {
                          setRoomInput('');
                          setNeedPassword(false);
                          setErrorText('');
                        }}
                        title="清空"
                      >
                        <X size={14} />
                      </button>
                    )}
                    <button
                      type="button"
                      className="btn btn-soft btn-sm"
                      onClick={() => void handlePasteClipboard()}
                    >
                      <ClipboardPaste size={13} /> 粘贴
                    </button>
                  </div>
                </div>
              </div>

              {/* 仅当探测到加密房或未展开高级设置但已填密码时展示 */}
              {(needPassword || (!showAdvanced && hasPasswordInput)) && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text-sub)' }}>
                    输入房间暗号密码
                  </span>
                  <div className="input-group">
                    <span className="input-prefix-icon">
                      <Lock size={15} />
                    </span>
                    <input
                      className="field-input with-prefix"
                      type="password"
                      value={roomPassword}
                      onChange={(e) => {
                        setRoomPassword(e.target.value);
                        setErrorText('');
                      }}
                      placeholder="输入对方告知的暗号..."
                      autoFocus
                    />
                  </div>
                </div>
              )}

              {errorText && (
                <div className="alert-strip">
                  <AlertCircle size={15} />
                  <span>{errorText}</span>
                </div>
              )}

              {/* 主行动栏：一键秒开/直达 + 随机串门 */}
              <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                <button
                  type="submit"
                  className="btn btn-jelly btn-xl"
                  style={{ flex: 1, minWidth: 180 }}
                  disabled={submitting || randomJoining}
                >
                  <Sparkles size={17} />
                  <span>
                    {submitting
                      ? '正在直连房间...'
                      : hasInputCode
                        ? '直达进入（无则自动建房）'
                        : mode === 'fixed'
                          ? '秒开常驻秘密基地'
                          : '秒开快传房间'}
                  </span>
                  <ArrowRight size={17} />
                </button>
                <button
                  type="button"
                  className="btn btn-soft btn-xl"
                  disabled={submitting || randomJoining}
                  onClick={() => void handleRandomJoin()}
                  title="只进入无密码且允许串门的活跃房间"
                >
                  <Dices size={17} />
                  <span>{randomJoining ? '匹配中...' : '随机串门'}</span>
                </button>
              </div>

              {/* 一行极简控制条：允许串门快捷胶囊 + 高级设置折叠开关 */}
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 8,
                  flexWrap: 'wrap',
                  paddingTop: 2,
                }}
              >
                <button
                  type="button"
                  className={`btn btn-sm ${!hasPasswordInput && allowRandom ? 'btn-soft' : 'btn-glass'}`}
                  disabled={hasPasswordInput}
                  onClick={() => {
                    const next = !allowRandom;
                    setAllowRandom(next);
                    onToast(next ? '🌐 已开启陌生人随机串门' : '🔒 已关闭随机串门，仅限口令进入');
                  }}
                  title="默认开启，传私密文件时点击即可关闭"
                >
                  {!hasPasswordInput && allowRandom ? <Globe size={13} /> : <Lock size={13} />}
                  <span>
                    {hasPasswordInput
                      ? '暗号保护中'
                      : allowRandom
                        ? '允许随机串门 ✓'
                        : '仅限口令进入'}
                  </span>
                </button>

                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => setShowAdvanced((prev) => !prev)}
                  style={{ color: showAdvanced ? 'var(--accent)' : 'var(--text-sub)', fontWeight: 700 }}
                >
                  <SlidersHorizontal size={13} />
                  <span>高级设置</span>
                  {!showAdvanced && (
                    <span style={{ fontSize: 11.5, color: 'var(--text-muted)', fontWeight: 500 }}>
                      ({summaryTag})
                    </span>
                  )}
                  {showAdvanced ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
                </button>
              </div>

              {/* 高级设置折叠区（默认收起，保持页面极简） */}
              {showAdvanced && (
                <div
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 14,
                    paddingTop: 12,
                    borderTop: '1px solid var(--line-border)',
                  }}
                >
                  <div className="candy-mode-grid">
                    <button
                      type="button"
                      className={`candy-card ${mode === 'ephemeral' ? 'selected' : ''}`}
                      onClick={() => handleModeChange('ephemeral')}
                    >
                      <div
                        className="candy-badge-icon"
                        style={{ background: 'linear-gradient(135deg, #ff5487, #ff965a)' }}
                      >
                        <Flame size={17} />
                      </div>
                      <div style={{ fontSize: 14, fontWeight: 800 }}>闪传临时房 · 聊完销毁</div>
                      <div style={{ fontSize: 11.5, color: 'var(--text-sub)', lineHeight: 1.4 }}>
                        生成 6 位随机口令，离开后自动过期清空
                      </div>
                    </button>

                    <button
                      type="button"
                      className={`candy-card ${mode === 'fixed' ? 'selected' : ''}`}
                      onClick={() => handleModeChange('fixed')}
                    >
                      <div
                        className="candy-badge-icon"
                        style={{ background: 'linear-gradient(135deg, #8b5cf6, #06b6d4)' }}
                      >
                        <Pin size={17} />
                      </div>
                      <div style={{ fontSize: 14, fontWeight: 800 }}>常驻秘密基地 · 长期保留</div>
                      <div style={{ fontSize: 11.5, color: 'var(--text-sub)', lineHeight: 1.4 }}>
                        固定房号，手机电脑随时丝滑重连
                      </div>
                    </button>
                  </div>

                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    <div className="lobby-field-head">
                      <span>服务器消息与文件保鲜期（最长 7 天）</span>
                      <span style={{ color: 'var(--accent)', fontWeight: 600 }}>
                        {retentionDays === 0
                          ? '🔥 服务器零留存'
                          : `活跃起 ${retentionDays} 天后自动清空`}
                      </span>
                    </div>
                    <div className="segmented full seg-mobile-grid">
                      {RETENTION_STEPS.map((st) => {
                        const active = retentionDays === st.days;
                        const cls = active
                          ? st.days === 0
                            ? 'seg-item danger-active'
                            : 'seg-item active'
                          : 'seg-item';
                        return (
                          <button
                            key={st.days}
                            type="button"
                            className={cls}
                            onClick={() => setRetentionDays(st.days)}
                          >
                            {st.label}
                          </button>
                        );
                      })}
                    </div>
                  </div>

                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    <div className="lobby-field-head">
                      <span>单附件传输大小上限</span>
                      <span style={{ color: 'var(--accent)', fontWeight: 600 }}>
                        {maxFileSizeMB === 0
                          ? '♾️ 不限制（支持 GB 级流式直传）'
                          : `单文件 ≤ ${maxFileSizeMB >= 1024 ? `${(maxFileSizeMB / 1024).toFixed(maxFileSizeMB % 1024 === 0 ? 0 : 1)} GB` : `${maxFileSizeMB} MB`}`}
                      </span>
                    </div>
                    <div className="segmented full seg-mobile-grid">
                      {FILE_LIMIT_PRESETS.filter((p) => {
                        const cap = serverConfig?.maxAttachmentMB || 0;
                        if (cap <= 0) return true;
                        return p.mb > 0 && p.mb <= cap;
                      }).map((p) => (
                        <button
                          key={p.mb}
                          type="button"
                          className={`seg-item ${maxFileSizeMB === p.mb ? 'active' : ''}`}
                          onClick={() => setMaxFileSizeMB(p.mb)}
                        >
                          {p.label}
                        </button>
                      ))}
                    </div>
                  </div>

                  <div className="lobby-two-col">
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                      <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text-sub)' }}>
                        房间容量
                      </span>
                      <div className="segmented full" style={{ height: 44, alignItems: 'center' }}>
                        <button
                          type="button"
                          className={`seg-item ${privatePairOnly ? 'active' : ''}`}
                          onClick={() => setPrivatePairOnly(true)}
                        >
                          1v1 双人私密
                        </button>
                        <button
                          type="button"
                          className={`seg-item ${!privatePairOnly ? 'active' : ''}`}
                          onClick={() => setPrivatePairOnly(false)}
                        >
                          小队 ({serverConfig?.maxRoomPeers || 6}人)
                        </button>
                      </div>
                    </div>

                    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                      <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text-sub)' }}>
                        暗号密码（可选）
                      </span>
                      <div className="input-group">
                        <span className="input-prefix-icon">
                          <Lock size={15} />
                        </span>
                        <input
                          className="field-input with-prefix"
                          type="password"
                          value={roomPassword}
                          onChange={(e) => setRoomPassword(e.target.value)}
                          placeholder="留空免密；设暗号禁随机"
                        />
                      </div>
                    </div>
                  </div>

                  {(mode === 'fixed' || hasInputCode) && (
                    <Switch
                      checked={saveToFavorites}
                      onChange={setSaveToFavorites}
                      label="收藏到「我的秘密基地」"
                      description="下次打开网页直接一键空降进入房间"
                    />
                  )}
                </div>
              )}
            </form>
          </section>

          {/* 右侧 Bento 副栏：百变身份卡 + 秘密基地列表 + 创意玩法标签 */}
          <aside style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
            {/* 百变身份卡 */}
            <div className="glass-bento" style={{ padding: 20 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
                <span style={{ fontSize: 14, fontWeight: 800 }}>🎭 我的百变身份</span>
                <div style={{ display: 'flex', gap: 6 }}>
                  <button
                    type="button"
                    className="btn btn-soft btn-sm"
                    onClick={() =>
                      onQuickRandomProfile({
                        ...profile,
                        name: generateRandomName(),
                        avatar: pickRandomPresetAvatar(),
                      })
                    }
                    title="随机换个新马甲"
                  >
                    <Dices size={13} /> 摇一摇
                  </button>
                  <button type="button" className="btn btn-glass btn-sm" onClick={onEditProfile}>
                    定制头像
                  </button>
                </div>
              </div>

              <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                <div onClick={onEditProfile} style={{ cursor: 'pointer' }}>
                  <Avatar name={profile.name} avatar={profile.avatar} size={54} />
                </div>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontSize: 16, fontWeight: 800, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {profile.name}
                  </div>
                  <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 3 }}>
                    免注册账号 · 支持上传自拍与表情包当头像
                  </div>
                </div>
              </div>
            </div>

            {/* 最近访问过的房间（包含临时闪传房与固定房，支持一键重进与自动失效清理） */}
            {recentRooms.length > 0 && (
              <div className="glass-bento" style={{ padding: 18 }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
                  <span style={{ fontSize: 14, fontWeight: 800, display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                    <Clock size={14} style={{ color: 'var(--accent)' }} />
                    最近房间
                  </span>
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm"
                    style={{ height: 24, padding: '0 8px', fontSize: 11.5 }}
                    onClick={onClearRecentRooms}
                  >
                    清空
                  </button>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {recentRooms.slice(0, 6).map((item) => (
                    <div
                      key={item.code}
                      style={{
                        padding: '9px 12px',
                        borderRadius: 16,
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
                          {item.hasPassword && <Lock size={11} style={{ color: 'var(--accent)' }} />}
                        </div>
                        <div style={{ fontSize: 11, color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: 6, marginTop: 2 }}>
                          <span>{item.mode === 'fixed' ? '📌 常驻' : '🔥 闪传'}</span>
                          <span>· {formatRelativeTime(item.lastVisitedAt)}</span>
                        </div>
                      </div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                        <button
                          type="button"
                          className="btn btn-soft btn-sm"
                          onClick={() => void handleQuickEnterRecent(item)}
                        >
                          重进
                        </button>
                        <button
                          type="button"
                          className="btn btn-ghost btn-icon"
                          style={{ width: 28, height: 28 }}
                          title="复制邀请链接"
                          onClick={async () => {
                            const url = `${window.location.origin}/?room=${encodeURIComponent(item.code)}`;
                            if (await copyTextToClipboard(url)) onToast(`已复制 #${item.code} 链接`);
                          }}
                        >
                          <Copy size={13} />
                        </button>
                        <button
                          type="button"
                          className="btn btn-ghost btn-icon"
                          style={{ width: 28, height: 28 }}
                          title="移除此记录"
                          onClick={() => onRemoveRecentRoom(item.code)}
                        >
                          <Trash2 size={13} />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* 常驻秘密基地 */}
            <div className="glass-bento" style={{ padding: 18 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
                <span style={{ fontSize: 14, fontWeight: 800 }}>📌 我的秘密基地</span>
                <span className="pill pill-accent mono">{savedFixedRooms.length}</span>
              </div>

              {savedFixedRooms.length === 0 ? (
                <div
                  style={{
                    padding: '16px 14px',
                    borderRadius: 18,
                    background: 'var(--glass-subtle)',
                    border: '1.5px dashed var(--line-strong)',
                    fontSize: 12.5,
                    color: 'var(--text-sub)',
                    lineHeight: 1.55,
                  }}
                >
                  还没有收藏常驻房间～创建一个「专属秘密基地」，就能固定在这里，手机和电脑随时互扔文件与截图！
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {savedFixedRooms.map((item) => (
                    <div
                      key={item.code}
                      style={{
                        padding: '10px 12px',
                        borderRadius: 16,
                        background: 'var(--glass-bg-strong)',
                        border: '1px solid var(--line-border)',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        gap: 8,
                      }}
                    >
                      <div style={{ minWidth: 0 }}>
                        <div className="mono" style={{ fontSize: 14.5, fontWeight: 800, display: 'flex', alignItems: 'center', gap: 5 }}>
                          <span>#{item.code}</span>
                          {item.hasPassword && <Lock size={12} style={{ color: 'var(--accent)' }} />}
                        </div>
                        <div style={{ fontSize: 11.5, color: 'var(--text-muted)' }}>常驻直连通道</div>
                      </div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                        <button
                          type="button"
                          className="btn btn-jelly btn-sm"
                          onClick={() => void handleQuickEnterSaved(item)}
                        >
                          空降
                        </button>
                        <button
                          type="button"
                          className="btn btn-ghost btn-icon"
                          style={{ width: 28, height: 28 }}
                          title="复制邀请链接"
                          onClick={async () => {
                            const url = `${window.location.origin}/?room=${encodeURIComponent(item.code)}`;
                            if (await copyTextToClipboard(url)) onToast(`已复制 #${item.code} 链接`);
                          }}
                        >
                          <Copy size={13} />
                        </button>
                        <button
                          type="button"
                          className="btn btn-ghost btn-icon"
                          style={{ width: 28, height: 28 }}
                          title="取消收藏"
                          onClick={() => onRemoveSavedRoom(item.code)}
                        >
                          <Trash2 size={13} />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* 核心能力概览卡 */}
            <div className="glass-bento" style={{ padding: 18 }}>
              <div style={{ fontSize: 13, fontWeight: 800, marginBottom: 10, display: 'flex', alignItems: 'center', gap: 6 }}>
                <Wand2 size={15} style={{ color: 'var(--accent)' }} />
                房间核心能力
              </div>
              <div className="feature-tag-cloud">
                <span className="feature-mini-pill">📦 GB 级 P2P 文件直传</span>
                <span className="feature-mini-pill">🎧 语音连麦与投屏</span>
                <span className="feature-mini-pill">🎨 多人协作白板</span>
                <span className="feature-mini-pill">🍅 同步番茄自习室</span>
                <span className="feature-mini-pill">⏱ 0~7天可控缓存</span>
                <span className="feature-mini-pill">🖼️ 粘贴截图原图直发</span>
              </div>
            </div>
          </aside>
        </div>
      </main>
    </div>
  );
};
