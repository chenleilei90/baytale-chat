import React, { useEffect, useRef, useState } from 'react';
import { MonitorUp, Maximize2, ChevronUp, ChevronDown, Square } from 'lucide-react';

interface RemoteAudioPlayerProps {
  peerId: string;
  stream: MediaStream;
  onSpeakingChange: (peerId: string, speaking: boolean) => void;
}

// 远端语音连麦播放器：自动播放对方麦克风音频流并实时检测声纹能量，驱动侧边栏头像发光环
export const RemoteAudioPlayer: React.FC<RemoteAudioPlayerProps> = ({
  peerId,
  stream,
  onSpeakingChange,
}) => {
  const audioRef = useRef<HTMLAudioElement>(null);

  useEffect(() => {
    const el = audioRef.current;
    if (!el) return;
    el.srcObject = stream;
    void el.play().catch(() => {
      // 忽略自动播放策略限制
    });
    return () => {
      el.srcObject = null;
    };
  }, [stream]);

  useEffect(() => {
    if (stream.getAudioTracks().length === 0) return;
    const Ctor =
      window.AudioContext ||
      (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    if (!Ctor) return;

    let ctx: AudioContext | null = null;
    let timer: number | null = null;
    let lastSpeaking = false;

    try {
      ctx = new Ctor();
      const source = ctx.createMediaStreamSource(stream);
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 256;
      source.connect(analyser);
      const data = new Uint8Array(analyser.frequencyBinCount);

      timer = window.setInterval(() => {
        analyser.getByteFrequencyData(data);
        let sum = 0;
        for (let i = 0; i < data.length; i++) sum += data[i];
        const avg = sum / data.length;
        const speaking = avg > 14;
        if (speaking !== lastSpeaking) {
          lastSpeaking = speaking;
          onSpeakingChange(peerId, speaking);
        }
      }, 180);
    } catch {
      // 忽略 Web Audio 限制
    }

    return () => {
      if (timer !== null) clearInterval(timer);
      if (lastSpeaking) onSpeakingChange(peerId, false);
      if (ctx) void ctx.close().catch(() => {});
    };
  }, [peerId, stream, onSpeakingChange]);

  return <audio ref={audioRef} autoPlay playsInline style={{ display: 'none' }} />;
};

export interface ScreenFeedItem {
  id: string;
  label: string;
  isSelf: boolean;
  stream: MediaStream;
}

interface ScreenShareStageProps {
  feeds: ScreenFeedItem[];
  onStopLocalScreen: () => void;
}

// 聊天室顶部实时投屏舞台：支持多端画面切换、折叠/展开、一键全屏，不打断下方聊天与传文件
export const ScreenShareStage: React.FC<ScreenShareStageProps> = ({
  feeds,
  onStopLocalScreen,
}) => {
  const [selectedId, setSelectedId] = useState<string>(feeds[0]?.id || '');
  const [collapsed, setCollapsed] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);

  const activeFeed = feeds.find((f) => f.id === selectedId) || feeds[0];

  useEffect(() => {
    if (activeFeed && activeFeed.id !== selectedId) {
      setSelectedId(activeFeed.id);
    }
  }, [activeFeed, selectedId]);

  useEffect(() => {
    const v = videoRef.current;
    if (!v || !activeFeed) return;
    v.srcObject = activeFeed.stream;
    void v.play().catch(() => {});
    return () => {
      v.srcObject = null;
    };
  }, [activeFeed, collapsed]);

  if (!activeFeed) return null;

  const handleFullscreen = () => {
    const v = videoRef.current;
    if (v && typeof v.requestFullscreen === 'function') {
      void v.requestFullscreen().catch(() => {});
    }
  };

  return (
    <div className="screen-stage-card">
      <div className="screen-stage-head">
        <div className="screen-stage-meta">
          <span className="pill pill-emerald screen-stage-badge">
            <MonitorUp size={11} aria-hidden="true" /> 实时投屏中
          </span>
          {feeds.length > 1 ? (
            <div className="screen-stage-tabs">
              {feeds.map((f) => (
                <button
                  key={f.id}
                  type="button"
                  className={`btn btn-sm screen-stage-tab-btn ${f.id === activeFeed.id ? 'btn-primary' : 'btn-secondary'}`}
                  onClick={() => setSelectedId(f.id)}
                >
                  {f.label}
                </button>
              ))}
            </div>
          ) : (
            <span className="screen-stage-title">
              {activeFeed.label} 正在共享屏幕
            </span>
          )}
        </div>

        <div className="screen-stage-actions">
          {activeFeed.isSelf && (
            <button
              type="button"
              className="btn btn-danger btn-sm screen-stage-action-btn"
              onClick={onStopLocalScreen}
            >
              <Square size={10} aria-hidden="true" /> 停止共享
            </button>
          )}
          {!collapsed && (
            <button
              type="button"
              className="btn btn-secondary btn-sm screen-stage-action-btn"
              onClick={handleFullscreen}
              title="全屏观看投屏"
              aria-label="全屏观看投屏"
            >
              <Maximize2 size={11} aria-hidden="true" /> 全屏
            </button>
          )}
          <button
            type="button"
            className="btn btn-ghost btn-icon screen-stage-collapse-btn"
            onClick={() => setCollapsed((v) => !v)}
            title={collapsed ? '展开投屏画面' : '折叠投屏画面'}
            aria-label={collapsed ? '展开投屏画面' : '折叠投屏画面'}
          >
            {collapsed ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronUp size={14} aria-hidden="true" />}
          </button>
        </div>
      </div>

      {!collapsed && (
        <div className="screen-video-box">
          <video
            ref={videoRef}
            autoPlay
            playsInline
            muted={activeFeed.isSelf}
            controls={!activeFeed.isSelf}
          />
        </div>
      )}
    </div>
  );
};
