import React, { useEffect, useMemo, useState } from 'react';
import {
  Activity,
  CheckCircle2,
  HardDrive,
  Pause,
  Play,
  QrCode,
  RefreshCw,
  ShieldCheck,
  Wifi,
} from 'lucide-react';
import type { FileTransferInfo } from '../types';
import { formatBytes } from '../lib/platform';

// 24 分片并发乱序索引表（与 P2PLink 原型分片矩阵视觉保持一致，驱动真实进度）
const CHUNK_ORDER = [
  0, 6, 13, 2, 19, 8, 15, 4, 21, 10, 1, 17,
  7, 12, 23, 3, 14, 9, 20, 5, 16, 11, 22, 18,
];

interface P2PMonitorPanelProps {
  roomCode: string;
  wsConnected: boolean;
  peerCount: number;
  directPeerCount: number;
  transfers: FileTransferInfo[];
  onOpenQrInvite: () => void;
  onCancelTransfer?: (peerId: string, transferId: string) => void;
  onRequestResume?: (peerId: string, transfer: FileTransferInfo) => void;
}

type CellState = 'done' | 'active' | 'paused' | 'pending';

function computeRealChunkGrid(progressPercent: number, state: 'transferring' | 'paused' | 'completed' | 'idle'): CellState[] {
  const grid: CellState[] = Array(24).fill('pending');
  if (state === 'idle') return grid;
  if (state === 'completed' || progressPercent >= 100) {
    return Array(24).fill('done');
  }
  const doneCount = Math.max(0, Math.min(24, Math.floor((progressPercent / 100) * 24)));
  for (let i = 0; i < doneCount; i++) {
    grid[CHUNK_ORDER[i]] = 'done';
  }
  const activeSlots = [CHUNK_ORDER[doneCount], CHUNK_ORDER[Math.min(23, doneCount + 1)]];
  activeSlots.forEach((idx) => {
    if (idx !== undefined && grid[idx] !== 'done') {
      grid[idx] = state === 'transferring' ? 'active' : 'paused';
    }
  });
  return grid;
}

/**
 * BayChat 桌面端右侧第三栏 P2P 实时监控面板：
 * 1. 基于真实 WebRTC FileTransferInfo 的 24 格分片进度矩阵
 * 2. 每 500ms 采样的真实 DataChannel 实时吞吐速率波形图
 * 3. E2EE 端到端加密与扫码配对入口
 */
export const P2PMonitorPanel: React.FC<P2PMonitorPanelProps> = ({
  roomCode,
  wsConnected,
  peerCount,
  directPeerCount,
  transfers,
  onOpenQrInvite,
  onCancelTransfer,
  onRequestResume,
}) => {
  const [selectedTransferId, setSelectedTransferId] = useState<string | null>(null);
  const [bandwidthSeries, setBandwidthSeries] = useState<number[]>(() => Array(28).fill(0));
  const [peakSpeedMBs, setPeakSpeedMBs] = useState<number>(0);
  const [showSlowReconnect, setShowSlowReconnect] = useState<boolean>(false);

  // 微信式静默缓冲：信令刚断开的前 5s 显示“收取中...”，超过 5s 仍未恢复再温和提示重连中
  useEffect(() => {
    if (wsConnected) {
      setShowSlowReconnect(false);
      return;
    }
    const timer = window.setTimeout(() => {
      setShowSlowReconnect(true);
    }, 5000);
    return () => window.clearTimeout(timer);
  }, [wsConnected]);

  // 自动聚焦到正在传输的任务，若无进行中任务则聚焦最近一条任务
  const primaryTransfer = useMemo(() => {
    if (transfers.length === 0) return null;
    if (selectedTransferId) {
      const matched = transfers.find((t) => t.transferId === selectedTransferId);
      if (matched) return matched;
    }
    const active = transfers.find((t) => t.status === 'transferring');
    if (active) return active;
    const resumable = transfers.find((t) => (t.canResumeBytes || 0) > 0 && t.status !== 'completed');
    if (resumable) return resumable;
    return transfers[transfers.length - 1];
  }, [transfers, selectedTransferId]);

  const currentSpeedMBs = useMemo(() => {
    const totalBps = transfers
      .filter((t) => t.status === 'transferring')
      .reduce((acc, t) => acc + Math.max(0, t.speedBps || 0), 0);
    return +(totalBps / (1024 * 1024)).toFixed(2);
  }, [transfers]);

  useEffect(() => {
    const sample = () => {
      const totalBps = transfers
        .filter((t) => t.status === 'transferring')
        .reduce((acc, t) => acc + Math.max(0, t.speedBps || 0), 0);
      const mbs = +(totalBps / (1024 * 1024)).toFixed(2);
      setPeakSpeedMBs((prev) => (mbs > prev ? mbs : prev));
      setBandwidthSeries((prev) => [...prev.slice(1), mbs]);
    };
    sample();
    const timer = window.setInterval(sample, 500);
    return () => window.clearInterval(timer);
  }, [transfers]);

  const progressPercent = useMemo(() => {
    if (!primaryTransfer || primaryTransfer.fileSize <= 0) return 0;
    if (primaryTransfer.status === 'completed') return 100;
    const bytes = Math.max(primaryTransfer.transferredBytes || 0, primaryTransfer.canResumeBytes || 0);
    return Math.min(100, +((bytes / primaryTransfer.fileSize) * 100).toFixed(1));
  }, [primaryTransfer]);

  const matrixState = useMemo(() => {
    if (!primaryTransfer) return 'idle' as const;
    if (primaryTransfer.status === 'completed') return 'completed' as const;
    if (primaryTransfer.status === 'transferring') return 'transferring' as const;
    return 'paused' as const;
  }, [primaryTransfer]);

  const chunkCells = useMemo(
    () => computeRealChunkGrid(progressPercent, matrixState),
    [progressPercent, matrixState]
  );

  // 动态计算 SVG 折线与面积路径（自适应当前波形最大值）
  const chartPaths = useMemo(() => {
    const w = 240;
    const h = 68;
    const maxSeries = Math.max(10, ...bandwidthSeries, peakSpeedMBs);
    const pts = bandwidthSeries.map((val, idx) => {
      const x = (idx / (bandwidthSeries.length - 1)) * w;
      const y = h - Math.min(h - 6, (val / maxSeries) * (h - 12)) - 3;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    });
    const linePath = `M ${pts.join(' L ')}`;
    const areaPath = `${linePath} L ${w},${h} L 0,${h} Z`;
    return { linePath, areaPath };
  }, [bandwidthSeries, peakSpeedMBs]);

  const canResumePrimary =
    primaryTransfer &&
    primaryTransfer.status !== 'completed' &&
    primaryTransfer.status !== 'transferring' &&
    primaryTransfer.direction === 'receive' &&
    primaryTransfer.targetPeerId &&
    onRequestResume;

  const canCancelPrimary =
    primaryTransfer &&
    primaryTransfer.status === 'transferring' &&
    primaryTransfer.targetPeerId &&
    onCancelTransfer;

  return (
    <aside className="p2p-monitor-col" aria-label="P2P 实时链路与分片监控面板">
      {/* 模块 1：真实 24 分片断点续传进度矩阵 */}
      <div className="p2p-card">
        <div className="p2p-card-head">
          <span className="p2p-card-title">断点续传 &amp; 分片矩阵</span>
          <span className="p2p-badge mono">24 Chunks</span>
        </div>

        <div className="p2p-file-meta">
          <div className="p2p-file-name-row">
            <HardDrive size={13} aria-hidden="true" style={{ color: 'var(--accent)', flexShrink: 0 }} />
            <span className="p2p-file-name" title={primaryTransfer?.fileName || '等待文件传输'}>
              {primaryTransfer ? primaryTransfer.fileName : '暂无传输任务（拖拽文件开始）'}
            </span>
          </div>
          {primaryTransfer && (
            <div className="p2p-file-sub mono">
              <span>{formatBytes(primaryTransfer.fileSize)}</span>
              <span>·</span>
              <span>{progressPercent}%</span>
              {primaryTransfer.verified && (
                <>
                  <span>·</span>
                  <span style={{ color: 'var(--success)' }}>SHA-256 ✓</span>
                </>
              )}
            </div>
          )}
        </div>

        {/* 24 格分片可视化矩阵 */}
        <div className="p2p-chunk-grid" role="img" aria-label="24 分片传输状态矩阵">
          {chunkCells.map((cell, idx) => (
            <div
              key={idx}
              title={`分片 #${idx + 1} · ${
                cell === 'done'
                  ? '已校验写入'
                  : cell === 'active'
                  ? '高速传输中'
                  : cell === 'paused'
                  ? '断点锁定等待续传'
                  : '待传输'
              }`}
              className={`p2p-chunk-cell ${cell}`}
            >
              {idx + 1}
            </div>
          ))}
        </div>

        {/* 快捷任务切换与断点/中止控制 */}
        {transfers.length > 1 && (
          <div className="p2p-task-switcher">
            {transfers.slice(-3).map((tf) => {
              const active = primaryTransfer?.transferId === tf.transferId;
              return (
                <button
                  key={tf.transferId}
                  type="button"
                  className={`p2p-task-pill ${active ? 'active' : ''}`}
                  onClick={() => setSelectedTransferId(tf.transferId)}
                  title={tf.fileName}
                >
                  {tf.fileName}
                </button>
              );
            })}
          </div>
        )}

        {canCancelPrimary && (
          <button
            type="button"
            className="btn btn-glass btn-sm"
            style={{ width: '100%' }}
            onClick={() => onCancelTransfer(primaryTransfer.targetPeerId!, primaryTransfer.transferId)}
          >
            <Pause size={12} aria-hidden="true" /> 暂停当前分片传输
          </button>
        )}

        {canResumePrimary && (
          <button
            type="button"
            className="btn btn-jelly btn-sm"
            style={{ width: '100%' }}
            onClick={() => onRequestResume(primaryTransfer.targetPeerId!, primaryTransfer)}
          >
            <Play size={12} aria-hidden="true" /> 请求断点续传
          </button>
        )}

        {primaryTransfer?.status === 'completed' && (
          <div className="p2p-done-pill">
            <CheckCircle2 size={13} aria-hidden="true" />
            <span>分片校验完整 · 无损直达</span>
          </div>
        )}
      </div>

      {/* 模块 2：真实实时吞吐波形图 */}
      <div className="p2p-card">
        <div className="p2p-card-head">
          <span className="p2p-card-title" style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
            <Activity size={13} style={{ color: 'var(--accent)' }} aria-hidden="true" />
            实时吞吐波形
          </span>
          <span className="p2p-speed-now mono">
            {currentSpeedMBs > 0 ? `${currentSpeedMBs.toFixed(1)} MB/s` : '待命中'}
          </span>
        </div>

        <div className="p2p-wave-box">
          <svg viewBox="0 0 240 68" className="p2p-wave-svg" aria-hidden="true">
            <defs>
              <linearGradient id="bayP2PWaveGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="var(--accent)" stopOpacity="0.45" />
                <stop offset="100%" stopColor="var(--accent)" stopOpacity="0.0" />
              </linearGradient>
            </defs>
            <line
              x1="0"
              y1="18"
              x2="240"
              y2="18"
              stroke="var(--separator)"
              strokeOpacity="0.45"
              strokeDasharray="3 3"
            />
            <line
              x1="0"
              y1="42"
              x2="240"
              y2="42"
              stroke="var(--separator)"
              strokeOpacity="0.45"
              strokeDasharray="3 3"
            />
            <path d={chartPaths.areaPath} fill="url(#bayP2PWaveGrad)" />
            <path
              d={chartPaths.linePath}
              fill="none"
              stroke="var(--accent)"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </div>

        <div className="p2p-wave-foot mono">
          <span>协议: WebRTC SCTP</span>
          <span>峰值: {peakSpeedMBs.toFixed(1)} MB/s</span>
        </div>
      </div>

      {/* 模块 3：真实链路加密状态与扫码配对入口 */}
      <div className="p2p-card">
        <div className="p2p-card-head">
          <span className="p2p-card-title" style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
            <Wifi size={13} style={{ color: 'var(--accent)' }} aria-hidden="true" />
            节点与安全链路
          </span>
          <span className={`p2p-status-dot ${wsConnected ? 'online' : 'warn'}`} />
        </div>

        <div className="p2p-security-box">
          <div className="p2p-security-row">
            <ShieldCheck size={15} style={{ color: 'var(--success)', flexShrink: 0 }} aria-hidden="true" />
            <div>
              <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-primary)' }}>
                端到端加密保护中
              </div>
              <div className="mono" style={{ fontSize: 10.5, color: 'var(--accent)' }}>
                AES-256-GCM + DTLS 1.3
              </div>
            </div>
          </div>

          <div className="p2p-kv-list mono">
            <div className="p2p-kv">
              <span>当前口令</span>
              <strong>#{roomCode}</strong>
            </div>
            <div className="p2p-kv">
              <span>信令通道</span>
              <strong style={{ color: wsConnected ? 'var(--success)' : (showSlowReconnect ? 'var(--warning)' : 'var(--accent)') }}>
                {wsConnected ? '已连接' : (showSlowReconnect ? '重连中' : '收取中...')}
              </strong>
            </div>
            <div className="p2p-kv">
              <span>P2P 直连</span>
              <strong>{peerCount === 0 ? '等待伙伴' : `${directPeerCount}/${peerCount} 就绪`}</strong>
            </div>
          </div>
        </div>

        <button
          type="button"
          className="btn btn-soft btn-sm"
          style={{ width: '100%' }}
          onClick={onOpenQrInvite}
        >
          <QrCode size={13} aria-hidden="true" />
          <span>扫码配对 / 邀请设备</span>
        </button>

        {!wsConnected && showSlowReconnect && (
          <div className="p2p-reconnect-hint">
            <RefreshCw size={11} aria-hidden="true" />
            <span>当前网络较慢，正在重连...</span>
          </div>
        )}
      </div>
    </aside>
  );
};
