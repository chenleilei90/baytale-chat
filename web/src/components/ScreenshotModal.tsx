import React, { useEffect, useRef, useState } from 'react';
import {
  Crop,
  Pen,
  Square,
  ArrowUpRight,
  Grid3X3,
  Undo2,
  RotateCcw,
  Send,
  X,
} from 'lucide-react';

type ToolType = 'crop' | 'pen' | 'rect' | 'arrow' | 'mosaic';

interface Point {
  x: number;
  y: number;
}

interface ScreenshotModalProps {
  sourceCanvas: HTMLCanvasElement;
  onSend: (blob: Blob, caption: string) => void;
  onClose: () => void;
}

const COLORS = ['#ef4444', '#f59e0b', '#10b981', '#6366f1', '#ffffff'];

// 内置截图标注工作台：支持框选裁剪、画笔、矩形、箭头、隐私马赛克与撤销
export const ScreenshotModal: React.FC<ScreenshotModalProps> = ({
  sourceCanvas,
  onSend,
  onClose,
}) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [tool, setTool] = useState<ToolType>('crop');
  const [color, setColor] = useState('#ef4444');
  const [caption, setCaption] = useState('');
  const [history, setHistory] = useState<ImageData[]>([]);
  const [sending, setSending] = useState(false);

  const drawingRef = useRef(false);
  const startRef = useRef<Point>({ x: 0, y: 0 });
  const snapshotRef = useRef<ImageData | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    canvas.width = sourceCanvas.width;
    canvas.height = sourceCanvas.height;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.drawImage(sourceCanvas, 0, 0);
    setHistory([ctx.getImageData(0, 0, canvas.width, canvas.height)]);
  }, [sourceCanvas]);

  const getCanvasPoint = (e: React.PointerEvent<HTMLCanvasElement>): Point => {
    const canvas = canvasRef.current!;
    const rect = canvas.getBoundingClientRect();
    return {
      x: (e.clientX - rect.left) * (canvas.width / rect.width),
      y: (e.clientY - rect.top) * (canvas.height / rect.height),
    };
  };

  const pushSnapshot = () => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;
    setHistory((prev) => [...prev.slice(-15), ctx.getImageData(0, 0, canvas.width, canvas.height)]);
  };

  const handlePointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;
    e.currentTarget.setPointerCapture(e.pointerId);

    drawingRef.current = true;
    const pt = getCanvasPoint(e);
    startRef.current = pt;
    snapshotRef.current = ctx.getImageData(0, 0, canvas.width, canvas.height);

    if (tool === 'pen') {
      ctx.beginPath();
      ctx.moveTo(pt.x, pt.y);
      ctx.strokeStyle = color;
      ctx.lineWidth = Math.max(3, Math.round(canvas.width / 320));
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
    } else if (tool === 'mosaic') {
      applyMosaicBrush(ctx, pt.x, pt.y, Math.max(20, Math.round(canvas.width / 45)));
    }
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (!drawingRef.current) return;
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;

    const pt = getCanvasPoint(e);
    const start = startRef.current;
    const lineW = Math.max(3, Math.round(canvas.width / 320));

    if (tool === 'pen') {
      ctx.lineTo(pt.x, pt.y);
      ctx.stroke();
      return;
    }

    if (tool === 'mosaic') {
      applyMosaicBrush(ctx, pt.x, pt.y, Math.max(20, Math.round(canvas.width / 45)));
      return;
    }

    if (!snapshotRef.current) return;
    ctx.putImageData(snapshotRef.current, 0, 0);

    if (tool === 'rect') {
      ctx.strokeStyle = color;
      ctx.lineWidth = lineW;
      ctx.strokeRect(start.x, start.y, pt.x - start.x, pt.y - start.y);
    } else if (tool === 'arrow') {
      drawArrow(ctx, start.x, start.y, pt.x, pt.y, color, lineW);
    } else if (tool === 'crop') {
      ctx.save();
      ctx.fillStyle = 'rgba(0, 0, 0, 0.5)';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      const rx = Math.min(start.x, pt.x);
      const ry = Math.min(start.y, pt.y);
      const rw = Math.abs(pt.x - start.x);
      const rh = Math.abs(pt.y - start.y);
      if (rw > 4 && rh > 4) {
        ctx.putImageData(snapshotRef.current, 0, 0, rx, ry, rw, rh);
        ctx.strokeStyle = '#818cf8';
        ctx.lineWidth = Math.max(2, lineW * 0.8);
        ctx.setLineDash([8, 5]);
        ctx.strokeRect(rx, ry, rw, rh);
      }
      ctx.restore();
    }
  };

  const handlePointerUp = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (!drawingRef.current) return;
    drawingRef.current = false;

    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;

    if (tool === 'crop' && snapshotRef.current) {
      const pt = getCanvasPoint(e);
      const start = startRef.current;
      const rx = Math.max(0, Math.round(Math.min(start.x, pt.x)));
      const ry = Math.max(0, Math.round(Math.min(start.y, pt.y)));
      const rw = Math.round(Math.abs(pt.x - start.x));
      const rh = Math.round(Math.abs(pt.y - start.y));

      ctx.putImageData(snapshotRef.current, 0, 0);
      if (rw >= 24 && rh >= 24) {
        const cropped = ctx.getImageData(rx, ry, rw, rh);
        canvas.width = rw;
        canvas.height = rh;
        ctx.putImageData(cropped, 0, 0);
        pushSnapshot();
      }
      snapshotRef.current = null;
      return;
    }

    snapshotRef.current = null;
    pushSnapshot();
  };

  const handleUndo = () => {
    if (history.length <= 1) return;
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;
    const nextHistory = history.slice(0, -1);
    const last = nextHistory[nextHistory.length - 1];
    canvas.width = last.width;
    canvas.height = last.height;
    ctx.putImageData(last, 0, 0);
    setHistory(nextHistory);
  };

  const handleReset = () => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;
    canvas.width = sourceCanvas.width;
    canvas.height = sourceCanvas.height;
    ctx.drawImage(sourceCanvas, 0, 0);
    setHistory([ctx.getImageData(0, 0, canvas.width, canvas.height)]);
  };

  const handleSend = () => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    setSending(true);
    canvas.toBlob(
      (blob) => {
        setSending(false);
        if (blob) onSend(blob, caption.trim());
      },
      'image/png',
      0.95
    );
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-sheet wide" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <h3>屏幕截图标注</h3>
          <button type="button" className="btn btn-ghost btn-icon" onClick={onClose}>
            <X size={16} />
          </button>
        </div>

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            flexWrap: 'wrap',
            gap: 10,
          }}
        >
          <div className="segmented">
            <button
              type="button"
              className={`seg-item ${tool === 'crop' ? 'active' : ''}`}
              onClick={() => setTool('crop')}
            >
              <Crop size={14} /> 裁剪
            </button>
            <button
              type="button"
              className={`seg-item ${tool === 'pen' ? 'active' : ''}`}
              onClick={() => setTool('pen')}
            >
              <Pen size={14} /> 画笔
            </button>
            <button
              type="button"
              className={`seg-item ${tool === 'rect' ? 'active' : ''}`}
              onClick={() => setTool('rect')}
            >
              <Square size={14} /> 方框
            </button>
            <button
              type="button"
              className={`seg-item ${tool === 'arrow' ? 'active' : ''}`}
              onClick={() => setTool('arrow')}
            >
              <ArrowUpRight size={14} /> 箭头
            </button>
            <button
              type="button"
              className={`seg-item ${tool === 'mosaic' ? 'active' : ''}`}
              onClick={() => setTool('mosaic')}
            >
              <Grid3X3 size={14} /> 马赛克
            </button>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {COLORS.map((c) => (
              <button
                key={c}
                type="button"
                onClick={() => setColor(c)}
                style={{
                  width: 20,
                  height: 20,
                  borderRadius: '50%',
                  background: c,
                  border:
                    color === c ? '2.5px solid var(--accent)' : '1px solid var(--line-strong)',
                  cursor: 'pointer',
                }}
              />
            ))}
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={handleUndo}
              disabled={history.length <= 1}
            >
              <Undo2 size={13} /> 撤销
            </button>
            <button type="button" className="btn btn-secondary btn-sm" onClick={handleReset}>
              <RotateCcw size={13} /> 重置
            </button>
          </div>
        </div>

        <div className="screenshot-canvas-wrap">
          <canvas
            ref={canvasRef}
            style={{ touchAction: 'none' }}
            onPointerDown={handlePointerDown}
            onPointerMove={handlePointerMove}
            onPointerUp={handlePointerUp}
            onPointerCancel={handlePointerUp}
          />
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <input
            className="field-input"
            value={caption}
            onChange={(e) => setCaption(e.target.value)}
            placeholder="可选：输入截图备注说明..."
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !sending) {
                e.preventDefault();
                handleSend();
              }
            }}
          />
          <button
            type="button"
            className="btn btn-primary"
            style={{ height: 40, padding: '0 18px' }}
            onClick={handleSend}
            disabled={sending}
          >
            <Send size={14} />
            {sending ? '正在生成...' : '发送截图'}
          </button>
        </div>
      </div>
    </div>
  );
};

function drawArrow(
  ctx: CanvasRenderingContext2D,
  fromX: number,
  fromY: number,
  toX: number,
  toY: number,
  color: string,
  lineWidth: number
) {
  const headLen = Math.max(14, lineWidth * 4.2);
  const angle = Math.atan2(toY - fromY, toX - fromX);

  ctx.save();
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.lineWidth = lineWidth;
  ctx.lineCap = 'round';

  ctx.beginPath();
  ctx.moveTo(fromX, fromY);
  ctx.lineTo(toX, toY);
  ctx.stroke();

  ctx.beginPath();
  ctx.moveTo(toX, toY);
  ctx.lineTo(
    toX - headLen * Math.cos(angle - Math.PI / 6),
    toY - headLen * Math.sin(angle - Math.PI / 6)
  );
  ctx.lineTo(
    toX - headLen * Math.cos(angle + Math.PI / 6),
    toY - headLen * Math.sin(angle + Math.PI / 6)
  );
  ctx.closePath();
  ctx.fill();
  ctx.restore();
}

function applyMosaicBrush(
  ctx: CanvasRenderingContext2D,
  cx: number,
  cy: number,
  radius: number
) {
  const block = Math.max(8, Math.round(radius / 2.2));
  const startX = Math.max(0, Math.floor((cx - radius) / block) * block);
  const startY = Math.max(0, Math.floor((cy - radius) / block) * block);
  const endX = Math.min(ctx.canvas.width, cx + radius);
  const endY = Math.min(ctx.canvas.height, cy + radius);

  for (let y = startY; y < endY; y += block) {
    for (let x = startX; x < endX; x += block) {
      const w = Math.min(block, ctx.canvas.width - x);
      const h = Math.min(block, ctx.canvas.height - y);
      if (w <= 0 || h <= 0) continue;
      const sample = ctx.getImageData(x + Math.floor(w / 2), y + Math.floor(h / 2), 1, 1).data;
      ctx.fillStyle = `rgb(${sample[0]},${sample[1]},${sample[2]})`;
      ctx.fillRect(x, y, w, h);
    }
  }
}
