import React, { useEffect, useRef, useState } from 'react';
import { Sparkles, Eraser, Undo2, Trash2, Send, X, Palette } from 'lucide-react';

interface DoodleModalProps {
  onSend: (blob: Blob, caption: string) => void;
  onClose: () => void;
}

type BrushMode = 'neon' | 'rainbow' | 'eraser' | 'stamp';
type BgStyle = 'night' | 'cream';

const NEON_COLORS = ['#ff5487', '#ff965a', '#facc15', '#34d399', '#22d3ee', '#a855f7', '#ffffff'];
const STAMPS = ['⭐', '💖', '🔥', '🎉', '🚀', '🐱', '⚡', '🌈'];

// 创意手绘霓虹涂鸦贴纸板：支持荧光发光笔、七彩彩虹笔、趣味印章贴纸，一键作为拍立得贴纸发进聊天室
export const DoodleModal: React.FC<DoodleModalProps> = ({ onSend, onClose }) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [brush, setBrush] = useState<BrushMode>('neon');
  const [color, setColor] = useState('#ff5487');
  const [stamp, setStamp] = useState('⭐');
  const [bgStyle, setBgStyle] = useState<BgStyle>('night');
  const [size, setSize] = useState(6);
  const [caption, setCaption] = useState('');
  const [history, setHistory] = useState<ImageData[]>([]);

  const drawingRef = useRef(false);
  const hueRef = useRef(0);

  const fillCanvasBg = (ctx: CanvasRenderingContext2D, style: BgStyle) => {
    const w = ctx.canvas.width;
    const h = ctx.canvas.height;
    if (style === 'night') {
      const g = ctx.createLinearGradient(0, 0, w, h);
      g.addColorStop(0, '#161230');
      g.addColorStop(1, '#241744');
      ctx.fillStyle = g;
    } else {
      const g = ctx.createLinearGradient(0, 0, w, h);
      g.addColorStop(0, '#fff7f2');
      g.addColorStop(1, '#fef2f8');
      ctx.fillStyle = g;
    }
    ctx.shadowBlur = 0;
    ctx.fillRect(0, 0, w, h);
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  useEffect(() => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;
    canvas.width = 720;
    canvas.height = 440;
    fillCanvasBg(ctx, bgStyle);
    setHistory([ctx.getImageData(0, 0, canvas.width, canvas.height)]);
  }, [bgStyle]);

  const getPt = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current!;
    const rect = canvas.getBoundingClientRect();
    return {
      x: (e.clientX - rect.left) * (canvas.width / rect.width),
      y: (e.clientY - rect.top) * (canvas.height / rect.height),
    };
  };

  const pushSnap = () => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;
    setHistory((prev) => [...prev.slice(-14), ctx.getImageData(0, 0, canvas.width, canvas.height)]);
  };

  const handleDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    const pt = getPt(e);

    if (brush === 'stamp') {
      ctx.save();
      ctx.shadowBlur = 12;
      ctx.shadowColor = 'rgba(255, 84, 135, 0.5)';
      ctx.font = '40px "Apple Color Emoji", "Segoe UI Emoji", sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(stamp, pt.x, pt.y);
      ctx.restore();
      pushSnap();
      return;
    }

    drawingRef.current = true;
    ctx.shadowBlur = 0;
    ctx.beginPath();
    ctx.moveTo(pt.x, pt.y);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
  };

  const handleMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (!drawingRef.current) return;
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;
    const pt = getPt(e);

    if (brush === 'eraser') {
      ctx.shadowBlur = 0;
      ctx.strokeStyle = bgStyle === 'night' ? '#1d153a' : '#fff5f5';
      ctx.lineWidth = size * 3.2;
      ctx.lineTo(pt.x, pt.y);
      ctx.stroke();
      return;
    }

    if (brush === 'rainbow') {
      hueRef.current = (hueRef.current + 6) % 360;
      const c = `hsl(${hueRef.current}, 95%, 62%)`;
      ctx.strokeStyle = c;
      ctx.shadowColor = c;
      ctx.shadowBlur = 14;
      ctx.lineWidth = size;
      ctx.lineTo(pt.x, pt.y);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(pt.x, pt.y);
      return;
    }

    ctx.strokeStyle = color;
    ctx.shadowColor = color;
    ctx.shadowBlur = 14;
    ctx.lineWidth = size;
    ctx.lineTo(pt.x, pt.y);
    ctx.stroke();
  };

  const handleUp = () => {
    if (!drawingRef.current) return;
    drawingRef.current = false;
    const ctx = canvasRef.current?.getContext('2d');
    if (ctx) ctx.shadowBlur = 0;
    pushSnap();
  };

  const handleUndo = () => {
    if (history.length <= 1) return;
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;
    const next = history.slice(0, -1);
    ctx.putImageData(next[next.length - 1], 0, 0);
    setHistory(next);
  };

  const handleClear = () => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;
    fillCanvasBg(ctx, bgStyle);
    setHistory([ctx.getImageData(0, 0, canvas.width, canvas.height)]);
  };

  const handleSend = () => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    canvas.toBlob((blob) => {
      if (blob) onSend(blob, caption.trim() || '🎨 手绘涂鸦贴纸');
    }, 'image/png');
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-sheet wide" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 780 }}>
        <div className="modal-head">
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Palette size={20} style={{ color: 'var(--accent)' }} />
            <h3>手绘霓虹涂鸦板</h3>
          </div>
          <button type="button" className="btn btn-ghost btn-icon" onClick={onClose}>
            <X size={16} />
          </button>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10 }}>
          <div className="segmented">
            <button
              type="button"
              className={`seg-item ${brush === 'neon' ? 'active' : ''}`}
              onClick={() => setBrush('neon')}
            >
              ✨ 荧光笔
            </button>
            <button
              type="button"
              className={`seg-item ${brush === 'rainbow' ? 'active' : ''}`}
              onClick={() => setBrush('rainbow')}
            >
              <Sparkles size={13} /> 彩虹笔
            </button>
            <button
              type="button"
              className={`seg-item ${brush === 'stamp' ? 'active' : ''}`}
              onClick={() => setBrush('stamp')}
            >
              ⭐ 盖印章
            </button>
            <button
              type="button"
              className={`seg-item ${brush === 'eraser' ? 'active' : ''}`}
              onClick={() => setBrush('eraser')}
            >
              <Eraser size={13} /> 橡皮
            </button>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <button
              type="button"
              className={`btn btn-sm ${bgStyle === 'night' ? 'btn-jelly' : 'btn-glass'}`}
              onClick={() => setBgStyle('night')}
            >
              🌌 星空底
            </button>
            <button
              type="button"
              className={`btn btn-sm ${bgStyle === 'cream' ? 'btn-jelly' : 'btn-glass'}`}
              onClick={() => setBgStyle('cream')}
            >
              🌸 奶油底
            </button>
            <button
              type="button"
              className="btn btn-glass btn-sm"
              onClick={handleUndo}
              disabled={history.length <= 1}
            >
              <Undo2 size={13} /> 撤销
            </button>
            <button type="button" className="btn btn-glass btn-sm" onClick={handleClear}>
              <Trash2 size={13} /> 清空
            </button>
          </div>
        </div>

        {brush === 'stamp' ? (
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            {STAMPS.map((st) => (
              <button
                key={st}
                type="button"
                className={`btn btn-sm ${stamp === st ? 'btn-jelly' : 'btn-glass'}`}
                onClick={() => setStamp(st)}
                style={{ fontSize: 17 }}
              >
                {st}
              </button>
            ))}
            <span style={{ fontSize: 12, color: 'var(--text-muted)', alignSelf: 'center', marginLeft: 4 }}>
              选中印章后在画布任意位置点击即可盖章
            </span>
          </div>
        ) : (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            {NEON_COLORS.map((c) => (
              <button
                key={c}
                type="button"
                onClick={() => {
                  setColor(c);
                  if (brush === 'eraser') setBrush('neon');
                }}
                style={{
                  width: 24,
                  height: 24,
                  borderRadius: '50%',
                  background: c,
                  border: color === c && brush === 'neon' ? '3px solid var(--text-main)' : '2px solid rgba(0,0,0,0.12)',
                  cursor: 'pointer',
                }}
              />
            ))}
            <span style={{ fontSize: 12, color: 'var(--text-muted)', marginLeft: 6 }}>粗细</span>
            <input
              type="range"
              min={3}
              max={18}
              value={size}
              onChange={(e) => setSize(Number(e.target.value))}
              style={{ width: 90, accentColor: 'var(--accent)' }}
            />
          </div>
        )}

        <div className="screenshot-canvas-wrap">
          <canvas
            ref={canvasRef}
            style={{ touchAction: 'none' }}
            onPointerDown={handleDown}
            onPointerMove={handleMove}
            onPointerUp={handleUp}
            onPointerCancel={handleUp}
          />
        </div>

        <div style={{ display: 'flex', gap: 10 }}>
          <input
            className="field-input"
            value={caption}
            onChange={(e) => setCaption(e.target.value)}
            placeholder="给你的涂鸦起个名字或留言..."
          />
          <button type="button" className="btn btn-jelly" style={{ height: 44, padding: '0 22px' }} onClick={handleSend}>
            <Send size={15} /> 贴进聊天室
          </button>
        </div>
      </div>
    </div>
  );
};
