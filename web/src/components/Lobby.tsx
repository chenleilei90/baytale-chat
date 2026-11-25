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
  const [tab, setTab] = useState<'create' | 'join'>(initialJoinCode ? 'join' : 'create');

  const [mode, setMode] = useState<RoomMode>('ephemeral');
  const [customCode, setCustomCode] = useState('');
  const [createPassword, setCreatePassword] = useState('');
  const [allowRandom, setAllowRandom] = useState(true); // 默认允许陌生人随机串门（有密码时自动失效）
  const [privatePairOnly, setPrivatePairOnly] = useState(false);
  const [retentionDays, setRetentionDays] = useState(1);
  const [maxFileSizeMB, setMaxFileSizeMB] = useState<number>(
    serverConfig?.defaultRoomMaxFileMB ?? 500
  );
  const [saveToFavorites, setSaveToFavorites] = useState(true);
  const [creating, setCreating] = useState(false);
  const [randomJoining, setRandomJoining] = useState(false);

  useEffect(() => {
    if (serverConfig && typeof serverConfig.defaultRoomMaxFileMB === 'number') {
      setMaxFileSizeMB(serverConfig.defaultRoomMaxFileMB);
    }
  }, [serverConfig?.defaultRoomMaxFileMB]);

  const [joinInput, setJoinInput] = useState(initialJoinCode || '');
  const [joinPassword, setJoinPassword] = useState('');
  const [needPassword, setNeedPassword] = useState(false);
  const [joining, setJoining] = useState(false);
  const [errorText, setErrorText] = useState(initialNotice || '');

  useEffect(() => {
    if (initialJoinCode) {
      setTab('join');
      setJoinInput(initialJoinCode);
      void probeJoinRoom(initialJoinCode);
    }
  }, [initialJoinCode]);

  useEffect(() => {
    if (initialNotice) setErrorText(initialNotice);
  }, [initialNotice]);

  const probeJoinRoom = async (raw: string) => {
    const code = extractRoomCodeInput(raw);
    if (!code) return;
    try {
      const info = await fetchRoomInfo(code);
      setNeedPassword(info.hasPassword);
      setErrorText('');
    } catch (err) {
      if (err instanceof ApiError) setErrorText(err.message);
    }
  };

  const handleModeChange = (nextMode: RoomMode) => {
    setMode(nextMode);
    setErrorText('');
    if (nextMode === 'ephemeral' && retentionDays === 3) setRetentionDays(1);
    else if (nextMode === 'fixed' && retentionDays === 1) setRetentionDays(3);
  };

  const handleCreateSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorText('');
    setCreating(true);
    try {
      const maxLimit = serverConfig?.maxRoomPeers || 6;
      const cleanPwd = createPassword.trim();
      const summary = await createRoom({
        mode,
        code: mode === 'fixed' ? customCode.trim() : undefined,
        password: cleanPwd || undefined,
        allowRandom: cleanPwd ? false : allowRandom,
        maxPeers: privatePairOnly ? 2 : maxLimit,
        retentionDays,
        maxFileSizeMB,
      });
      onEnterRoom(
        summary.code,
        cleanPwd,
        summary.mode,
        mode === 'fixed' && saveToFavorites
      );
    } catch (err) {
      setErrorText(err instanceof Error ? err.message : '创建房间失败');
    } finally {
      setCreating(false);
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

  const handleJoinSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorText('');
    const code = extractRoomCodeInput(joinInput);
    if (!code) {
      setErrorText('请输入房间口令或粘贴邀请链接');
      return;
    }

    setJoining(true);
    try {
      const info = await fetchRoomInfo(code);
      if (info.hasPassword) {
        if (!joinPassword.trim()) {
          setNeedPassword(true);
          setErrorText('该房间设有访问口令，请输入密码');
          return;
        }
        await verifyRoomPassword(code, joinPassword.trim());
      }
      onEnterRoom(code, joinPassword.trim(), info.mode, info.mode === 'fixed');
    } catch (err) {
      if (err instanceof ApiError && err.errorCode === 'WRONG_PASSWORD') {
        setNeedPassword(true);
      }
      setErrorText(err instanceof Error ? err.message : '加入房间失败');
    } finally {
      setJoining(false);
    }
  };

  const handleQuickEnterSaved = async (item: SavedFixedRoom) => {
    setErrorText('');
    try {
      const info = await fetchRoomInfo(item.code);
      if (info.hasPassword && !item.password) {
        setTab('join');
        setJoinInput(item.code);
        setNeedPassword(true);
        setErrorText('请输入该常驻房间的密码');
        return;
      }
      if (info.hasPassword && item.password) {
        await verifyRoomPassword(item.code, item.password);
      }
      onEnterRoom(item.code, item.password || '', 'fixed', true);
    } catch (err) {
      setTab('join');
      setJoinInput(item.code);
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
        setTab('join');
        setJoinInput(item.code);
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
      setTab('join');
      setJoinInput(item.code);
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
        setJoinInput(text.trim());
        void probeJoinRoom(text);
      }
    } catch {
      onToast('请直接在输入框内按 Ctrl+V 粘贴');
    }
  };

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
        <div className="bento-stage">
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

            <div className="segmented full" style={{ marginBottom: 18 }}>
              <button
                type="button"
                className={`seg-item ${tab === 'create' ? 'active' : ''}`}
                onClick={() => {
                  setTab('create');
                  setErrorText('');
                }}
              >
                ✨ 创建新房间
              </button>
              <button
                type="button"
                className={`seg-item ${tab === 'join' ? 'active' : ''}`}
                onClick={() => {
                  setTab('join');
                  setErrorText('');
                }}
              >
                🚀 输入口令加入
              </button>
            </div>

            {tab === 'create' ? (
              <form
                onSubmit={handleCreateSubmit}
                style={{ display: 'flex', flexDirection: 'column', gap: 16 }}
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
                      <Flame size={19} />
                    </div>
                    <div style={{ fontSize: 15, fontWeight: 800 }}>闪传盲盒房 · 聊完就变</div>
                    <div style={{ fontSize: 12, color: 'var(--text-sub)', lineHeight: 1.45 }}>
                      生成 6 位随机口令，人走茶凉自动销毁作废，下次开启全新口令
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
                      <Pin size={19} />
                    </div>
                    <div style={{ fontSize: 15, fontWeight: 800 }}>专属秘密基地 · 长期不变</div>
                    <div style={{ fontSize: 12, color: 'var(--text-sub)', lineHeight: 1.45 }}>
                      自定义专属房间号，退出后依然保留，手机电脑随时丝滑重连
                    </div>
                  </button>
                </div>

                {mode === 'fixed' && (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12.5, fontWeight: 700, color: 'var(--text-sub)' }}>
                      <span>定制基地房间号</span>
                      <span style={{ color: 'var(--text-muted)', fontWeight: 500 }}>留空则随机生成</span>
                    </div>
                    <div className="input-group">
                      <input
                        className="field-input with-suffix mono"
                        value={customCode}
                        maxLength={24}
                        onChange={(e) => setCustomCode(e.target.value)}
                        placeholder="如 my-desk、sweet-888"
                      />
                      <div className="input-suffix">
                        <button
                          type="button"
                          className="btn btn-soft btn-sm"
                          onClick={() =>
                            setCustomCode(`bay-${Math.floor(1000 + Math.random() * 9000)}`)
                          }
                        >
                          <Dices size={13} /> 随机号
                        </button>
                      </div>
                    </div>
                  </div>
                )}

                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  <div className="lobby-field-head">
                    <span>服务器消息保鲜期（最长 7 天）</span>
                    <span style={{ color: 'var(--accent)', fontWeight: 600 }}>
                      {retentionDays === 0
                        ? '🔥 服务器零留存'
                        : `最后聊天起 ${retentionDays} 天后自动清空`}
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
                    <span>单附件传输大小上限（进房后随时可调）</span>
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
                        value={createPassword}
                        onChange={(e) => setCreatePassword(e.target.value)}
                        placeholder={mode === 'fixed' ? '推荐设个小暗号' : '留空免密秒进'}
                      />
                    </div>
                  </div>
                </div>

                {createPassword.trim() === '' ? (
                  <Switch
                    checked={allowRandom}
                    onChange={setAllowRandom}
                    label="🌐 允许陌生人随机串门"
                    description="默认开启；若仅自己跨端传私密文件，点击可关闭防误闯"
                  />
                ) : (
                  <div style={{ fontSize: 12, color: 'var(--text-muted)', padding: '2px 4px' }}>
                    🔒 已设置暗号密码，自动关闭随机串门（仅凭密码进入）
                  </div>
                )}

                {mode === 'fixed' && (
                  <Switch
                    checked={saveToFavorites}
                    onChange={setSaveToFavorites}
                    label="收藏到右侧「我的秘密基地」"
                    description="下次打开网页直接一键空降进入房间"
                  />
                )}

                {errorText && (
                  <div className="alert-strip">
                    <AlertCircle size={15} />
                    <span>{errorText}</span>
                  </div>
                )}

                <button
                  type="submit"
                  className="btn btn-jelly btn-xl"
                  style={{ width: '100%', marginTop: 4 }}
                  disabled={creating}
                >
                  <Sparkles size={18} />
                  <span>
                    {creating
                      ? '正在召唤直连房间...'
                      : mode === 'fixed'
                        ? '开启常驻秘密基地'
                        : '生成闪传口令并出发'}
                  </span>
                  <ArrowRight size={17} />
                </button>
              </form>
            ) : (
              <form
                onSubmit={handleJoinSubmit}
                style={{ display: 'flex', flexDirection: 'column', gap: 16 }}
              >
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  <span style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text-sub)' }}>
                    输入 6 位口令 / 基地号 / 粘贴分享链接
                  </span>
                  <div className="input-group">
                    <input
                      className="field-input with-suffix mono"
                      style={{ height: 54, fontSize: 19, fontWeight: 800, letterSpacing: '0.05em' }}
                      value={joinInput}
                      onChange={(e) => {
                        setJoinInput(e.target.value);
                        setErrorText('');
                      }}
                      onBlur={() => void probeJoinRoom(joinInput)}
                      placeholder="例如 849205 或 my-desk"
                      autoFocus
                    />
                    <div className="input-suffix" style={{ right: 8 }}>
                      <button
                        type="button"
                        className="btn btn-soft btn-sm"
                        onClick={() => void handlePasteClipboard()}
                      >
                        <ClipboardPaste size={13} /> 一键粘贴
                      </button>
                    </div>
                  </div>
                </div>

                {needPassword && (
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
                        value={joinPassword}
                        onChange={(e) => setJoinPassword(e.target.value)}
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

                <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                  <button
                    type="submit"
                    className="btn btn-jelly btn-xl"
                    style={{ flex: 1, minWidth: 160 }}
                    disabled={joining || randomJoining}
                  >
                    <span>{joining ? '正在穿越公网连接...' : '嗖一下进入房间'}</span>
                    <ArrowRight size={17} />
                  </button>
                  <button
                    type="button"
                    className="btn btn-soft btn-xl"
                    disabled={joining || randomJoining}
                    onClick={() => void handleRandomJoin()}
                    title="只进入无密码且允许串门的活跃房间"
                  >
                    <Dices size={17} />
                    <span>{randomJoining ? '匹配中...' : '随机串门'}</span>
                  </button>
                </div>
              </form>
            )}
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
