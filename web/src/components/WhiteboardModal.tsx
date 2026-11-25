import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Sparkles,
  Eraser,
  Undo2,
  Trash2,
  Send,
  X,
  Palette,
  StickyNote,
  PenTool,
  Users,
  Wand2,
  Moon,
  Sun,
  Pin,
} from 'lucide-react';
import type {
  StickyNoteColor,
  WhiteboardBgStyle,
  WhiteboardBrushMode,
  WhiteboardEvent,
  WhiteboardNote,
  WhiteboardStroke,
} from '../types';

export interface RemoteCursorState {
  peerId: string;
  name: string;
  color: string;
  nx: number;
  ny: number;
  updatedAt: number;
}

interface WhiteboardModalProps {
  selfPeerId: string;
  selfName: string;
  onlinePeerCount: number;
  bgStyle: WhiteboardBgStyle;
  strokes: WhiteboardStroke[];
  notes: WhiteboardNote[];
  remoteCursors: Record<string, RemoteCursorState>;
  onEmitEvent: (event: WhiteboardEvent) => void;
  onExportImage: (blob: Blob, caption: string) => void;
  onClose: () => void;
}

const NEON_COLORS = ['#ff5487', '#ff965a', '#facc15', '#34d399', '#22d3ee', '#a855f7', '#ffffff'];
const NOTE_STYLES: Record<
  StickyNoteColor,
  { label: string; bg: string; edge: string; text: string }
> = {
  yellow: { label: '柠檬黄', bg: '#fef08a', edge: '#eab308', text: '#422006' },
  pink: { label: '蜜桃粉', bg: '#fbcfe8', edge: '#ec4899', text: '#500724' },
  mint: { label: '薄荷蓝', bg: '#a5f3fc', edge: '#06b6d4', text: '#083344' },
  violet: { label: '香芋紫', bg: '#ddd6fe', edge: '#8b5cf6', text: '#2e1065' },
};

const CANVAS_W = 920;
const CANVAS_H = 540;

// 绘制背景与全部矢量笔迹（支持主画布与高清导出复用）
function renderStrokesToCanvas(
  ctx: CanvasRenderingContext2D,
  w: number,
  h: number,
  bgStyle: WhiteboardBgStyle,
  strokes: WhiteboardStroke[]
) {
  ctx.save();
  ctx.shadowBlur = 0;
  if (bgStyle === 'night') {
    const g = ctx.createLinearGradient(0, 0, w, h);
    g.addColorStop(0, '#14112c');
    g.addColorStop(1, '#251746');
    ctx.fillStyle = g;
  } else {
    const g = ctx.createLinearGradient(0, 0, w, h);
    g.addColorStop(0, '#fff9f5');
    g.addColorStop(1, '#fdf2f8');
    ctx.fillStyle = g;
  }
  ctx.fillRect(0, 0, w, h);

  // 轻柔点阵参考网格
  ctx.fillStyle = bgStyle === 'night' ? 'rgba(255,255,255,0.06)' : 'rgba(139,92,246,0.1)';
  for (let gx = 28; gx < w; gx += 32) {
    for (let gy = 28; gy < h; gy += 32) {
      ctx.fillRect(gx, gy, 2, 2);
    }
  }

  for (const s of strokes) {
    if (!s.points || s.points.length === 0) continue;
    ctx.save();
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    if (s.mode === 'eraser') {
      ctx.shadowBlur = 0;
      ctx.strokeStyle = bgStyle === 'night' ? '#1c1438' : '#fef5f7';
      ctx.lineWidth = s.size * 3.2;
      ctx.beginPath();
      s.points.forEach(([nx, ny], idx) => {
        const px = nx * w;
        const py = ny * h;
        if (idx === 0) ctx.moveTo(px, py);
        else ctx.lineTo(px, py);
      });
      if (s.points.length === 1) {
        ctx.lineTo(s.points[0][0] * w + 0.5, s.points[0][1] * h + 0.5);
      }
      ctx.stroke();
      ctx.restore();
      continue;
    }

    if (s.mode === 'rainbow') {
      ctx.lineWidth = s.size;
      ctx.shadowBlur = 12;
      for (let i = 1; i < s.points.length; i++) {
        const hue = (i * 9) % 360;
        const c = `hsl(${hue}, 95%, 62%)`;
        ctx.strokeStyle = c;
        ctx.shadowColor = c;
        ctx.beginPath();
        ctx.moveTo(s.points[i - 1][0] * w, s.points[i - 1][1] * h);
        ctx.lineTo(s.points[i][0] * w, s.points[i][1] * h);
        ctx.stroke();
      }
      ctx.restore();
      continue;
    }

    ctx.strokeStyle = s.color;
    ctx.lineWidth = s.size;
    if (s.mode === 'neon') {
      ctx.shadowColor = s.color;
      ctx.shadowBlur = 14;
    } else {
      ctx.shadowBlur = 0;
    }
    ctx.beginPath();
    s.points.forEach(([nx, ny], idx) => {
      const px = nx * w;
      const py = ny * h;
      if (idx === 0) ctx.moveTo(px, py);
      else ctx.lineTo(px, py);
    });
    if (s.points.length === 1) {
      ctx.lineTo(s.points[0][0] * w + 0.5, s.points[0][1] * h + 0.5);
    }
    ctx.stroke();
    ctx.restore();
  }
  ctx.restore();
}

// 多人实时同屏协作白板：支持 WebRTC 实时笔迹、彩色协同光标、可拖拽编辑便利贴与一键合图发聊天室
export const WhiteboardModal: React.FC<WhiteboardModalProps> = ({
  selfPeerId,
  selfName,
  onlinePeerCount,
  bgStyle,
  strokes,
  notes,
  remoteCursors,
  onEmitEvent,
  onExportImage,
  onClose,
}) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);

  const [tool, setTool] = useState<WhiteboardBrushMode | 'note'>('neon');
  const [color, setColor] = useState('#ff5487');
  const [noteColor, setNoteColor] = useState<StickyNoteColor>('yellow');
  const [size, setSize] = useState(6);
  const [caption, setCaption] = useState('');
  const [cursorNow, setCursorNow] = useState(() => Date.now());

  const activeStrokeIdRef = useRef<string | null>(null);
  const pendingPtsRef = useRef<[number, number][]>([]);
  const lastFlushRef = useRef<number>(0);
  const lastCursorRef = useRef<number>(0);
  const draggingNoteRef = useRef<{ id: string; offsetX: number; offsetY: number } | null>(null);

  const hasRemoteCursors = Object.keys(remoteCursors).length > 0;

  // 定时刷新时钟，确保对方停止移动鼠标 5 秒后协同光标自动淡出而不冻结在画布上
  useEffect(() => {
    if (!hasRemoteCursors) return;
    setCursorNow(Date.now());
    const id = window.setInterval(() => setCursorNow(Date.now()), 1500);
    return () => clearInterval(id);
  }, [hasRemoteCursors, remoteCursors]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  // 每次笔迹或背景变化时重绘画布
  useEffect(() => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;
    if (canvas.width !== CANVAS_W || canvas.height !== CANVAS_H) {
      canvas.width = CANVAS_W;
      canvas.height = CANVAS_H;
    }
    renderStrokesToCanvas(ctx, CANVAS_W, CANVAS_H, bgStyle, strokes);
  }, [bgStyle, strokes]);

  const getNormalizedPoint = (clientX: number, clientY: number): [number, number] => {
    const el = canvasRef.current!;
    const rect = el.getBoundingClientRect();
    const nx = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
    const ny = Math.max(0, Math.min(1, (clientY - rect.top) / rect.height));
    return [Number(nx.toFixed(4)), Number(ny.toFixed(4))];
  };

  const emitCursorThrottled = useCallback(
    (nx: number, ny: number) => {
      const now = performance.now();
      if (now - lastCursorRef.current < 45) return;
      lastCursorRef.current = now;
      onEmitEvent({
        type: 'cursor',
        nx,
        ny,
        name: selfName,
        color,
      });
    },
    [color, onEmitEvent, selfName]
  );

  const flushPendingPoints = useCallback(() => {
    const id = activeStrokeIdRef.current;
    if (!id || pendingPtsRef.current.length === 0) return;
    const batch = pendingPtsRef.current.splice(0);
    onEmitEvent({ type: 'stroke-append', strokeId: id, points: batch });
  }, [onEmitEvent]);

  const handleAddStickyNote = (nx = 0.2 + Math.random() * 0.45, ny = 0.2 + Math.random() * 0.42) => {
    const note: WhiteboardNote = {
      id: `note_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
      peerId: selfPeerId,
      authorName: selfName,
      x: Math.max(0.03, Math.min(0.76, nx)),
      y: Math.max(0.04, Math.min(0.72, ny)),
      text: '',
      color: noteColor,
      updatedAt: Date.now(),
    };
    onEmitEvent({ type: 'note-upsert', note });
  };

  const handlePointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const [nx, ny] = getNormalizedPoint(e.clientX, e.clientY);
    if (tool === 'note') {
      handleAddStickyNote(nx - 0.08, ny - 0.06);
      return;
    }

    e.currentTarget.setPointerCapture(e.pointerId);
    const id = `st_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
    activeStrokeIdRef.current = id;
    pendingPtsRef.current = [];
    lastFlushRef.current = performance.now();

    const stroke: WhiteboardStroke = {
      id,
      peerId: selfPeerId,
      mode: tool,
      color: tool === 'pen' && bgStyle === 'cream' && color === '#ffffff' ? '#1f1629' : color,
      size,
      points: [[nx, ny]],
    };
    onEmitEvent({ type: 'stroke-start', stroke });
    emitCursorThrottled(nx, ny);
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const [nx, ny] = getNormalizedPoint(e.clientX, e.clientY);
    emitCursorThrottled(nx, ny);

    if (!activeStrokeIdRef.current) return;
    pendingPtsRef.current.push([nx, ny]);
    const now = performance.now();
    if (now - lastFlushRef.current >= 28 || pendingPtsRef.current.length >= 4) {
      lastFlushRef.current = now;
      flushPendingPoints();
    }
  };

  const handlePointerUp = () => {
    if (!activeStrokeIdRef.current) return;
    flushPendingPoints();
    activeStrokeIdRef.current = null;
  };

  // 拖拽便签移动并同步全房
  const handleNoteDragStart = (e: React.PointerEvent<HTMLDivElement>, note: WhiteboardNote) => {
    const stage = stageRef.current;
    if (!stage) return;
    e.stopPropagation();
    e.currentTarget.setPointerCapture(e.pointerId);
    const rect = stage.getBoundingClientRect();
    draggingNoteRef.current = {
      id: note.id,
      offsetX: (e.clientX - rect.left) / rect.width - note.x,
      offsetY: (e.clientY - rect.top) / rect.height - note.y,
    };
  };

  const handleNoteDragMove = (e: React.PointerEvent<HTMLDivElement>, note: WhiteboardNote) => {
    const drag = draggingNoteRef.current;
    const stage = stageRef.current;
    if (!drag || drag.id !== note.id || !stage) return;
    const rect = stage.getBoundingClientRect();
    const nx = Math.max(0.01, Math.min(0.78, (e.clientX - rect.left) / rect.width - drag.offsetX));
    const ny = Math.max(0.02, Math.min(0.75, (e.clientY - rect.top) / rect.height - drag.offsetY));
    onEmitEvent({
      type: 'note-upsert',
      note: { ...note, x: Number(nx.toFixed(4)), y: Number(ny.toFixed(4)), updatedAt: Date.now() },
    });
  };

  const handleNoteDragEnd = () => {
    draggingNoteRef.current = null;
  };

  // 撤销自己最近画的一笔
  const handleUndoSelf = () => {
    for (let i = strokes.length - 1; i >= 0; i--) {
      if (strokes[i].peerId === selfPeerId) {
        onEmitEvent({ type: 'stroke-undo', strokeId: strokes[i].id });
        return;
      }
    }
  };

  // 将画布笔迹 + 全部共享便利贴合成为高清 PNG 发送进聊天室
  const handleExport = () => {
    const off = document.createElement('canvas');
    off.width = CANVAS_W;
    off.height = CANVAS_H;
    const ctx = off.getContext('2d');
    if (!ctx) return;

    renderStrokesToCanvas(ctx, CANVAS_W, CANVAS_H, bgStyle, strokes);

    // 合成共享便签卡片
    for (const note of notes) {
      const st = NOTE_STYLES[note.color] || NOTE_STYLES.yellow;
      const x = note.x * CANVAS_W;
      const y = note.y * CANVAS_H;
      const nw = 176;
      const nh = 116;

      ctx.save();
      ctx.shadowColor = 'rgba(0, 0, 0, 0.24)';
      ctx.shadowBlur = 14;
      ctx.shadowOffsetY = 6;
      ctx.fillStyle = st.bg;
      roundRectPath(ctx, x, y, nw, nh, 14);
      ctx.fill();
      ctx.shadowBlur = 0;
      ctx.strokeStyle = st.edge;
      ctx.lineWidth = 2;
      ctx.stroke();

      ctx.fillStyle = st.text;
      ctx.font = 'bold 12px "PingFang SC", "Microsoft YaHei", sans-serif';
      ctx.fillText(note.authorName, x + 12, y + 22);

      ctx.font = '13px "PingFang SC", "Microsoft YaHei", sans-serif';
      const content = note.text || '（空白便签）';
      wrapCanvasText(ctx, content, x + 12, y + 44, nw - 24, 18, 4);
      ctx.restore();
    }

    off.toBlob((blob) => {
      if (blob) {
        onExportImage(blob, caption.trim() || '多人协作白板作品');
      }
    }, 'image/png');
  };

  const hasOwnStroke = strokes.some((s) => s.peerId === selfPeerId);
  const activeCursorList = Object.values(remoteCursors).filter(
    (c) => cursorNow - c.updatedAt < 5000
  );

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal-sheet wide"
        onClick={(e) => e.stopPropagation()}
        style={{ maxWidth: 960, gap: 12, padding: 20 }}
      >
        <div className="modal-head">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <Palette size={20} style={{ color: 'var(--accent)' }} />
            <h3>多人实时同屏协作白板</h3>
            <span className="pill pill-emerald" style={{ height: 24, fontSize: 11.5 }}>
              <Users size={11} />
              {onlinePeerCount > 0 ? `已联机 · ${onlinePeerCount + 1} 人可同屏画` : '单人创作中（有人进房即同步）'}
            </span>
          </div>
          <button
            type="button"
            className="btn btn-ghost btn-icon"
            onClick={onClose}
            aria-label="关闭协作白板"
            title="关闭协作白板"
          >
            <X size={16} aria-hidden="true" />
          </button>
        </div>

        {/* 工具栏 */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 8 }}>
          <div className="segmented">
            <button
              type="button"
              className={`seg-item ${tool === 'neon' ? 'active' : ''}`}
              onClick={() => setTool('neon')}
            >
              <Wand2 size={13} aria-hidden="true" /> 荧光笔
            </button>
            <button
              type="button"
              className={`seg-item ${tool === 'rainbow' ? 'active' : ''}`}
              onClick={() => setTool('rainbow')}
            >
              <Sparkles size={13} aria-hidden="true" /> 彩虹笔
            </button>
            <button
              type="button"
              className={`seg-item ${tool === 'pen' ? 'active' : ''}`}
              onClick={() => setTool('pen')}
            >
              <PenTool size={13} aria-hidden="true" /> 细画笔
            </button>
            <button
              type="button"
              className={`seg-item ${tool === 'note' ? 'active' : ''}`}
              onClick={() => setTool('note')}
            >
              <StickyNote size={13} aria-hidden="true" /> 贴便签
            </button>
            <button
              type="button"
              className={`seg-item ${tool === 'eraser' ? 'active' : ''}`}
              onClick={() => setTool('eraser')}
            >
              <Eraser size={13} aria-hidden="true" /> 橡皮
            </button>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
            <button
              type="button"
              className={`btn btn-sm ${bgStyle === 'night' ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => onEmitEvent({ type: 'bg-change', bgStyle: 'night' })}
            >
              <Moon size={13} aria-hidden="true" /> 星空
            </button>
            <button
              type="button"
              className={`btn btn-sm ${bgStyle === 'cream' ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => onEmitEvent({ type: 'bg-change', bgStyle: 'cream' })}
            >
              <Sun size={13} aria-hidden="true" /> 奶油
            </button>
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={handleUndoSelf}
              disabled={!hasOwnStroke}
              title="撤销我画的最后一笔"
            >
              <Undo2 size={13} aria-hidden="true" /> 撤销
            </button>
            <button
              type="button"
              className="btn btn-danger-soft btn-sm"
              onClick={() => onEmitEvent({ type: 'clear', actorName: selfName })}
              title="清空全房白板与便签"
            >
              <Trash2 size={13} aria-hidden="true" /> 清空
            </button>
          </div>
        </div>

        {/* 颜色/便签色/粗细条 */}
        {tool === 'note' ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            {(Object.keys(NOTE_STYLES) as StickyNoteColor[]).map((ck) => (
              <button
                key={ck}
                type="button"
                className={`btn btn-sm ${noteColor === ck ? 'btn-primary' : 'btn-secondary'}`}
                onClick={() => setNoteColor(ck)}
                style={{
                  background: noteColor === ck ? undefined : NOTE_STYLES[ck].bg,
                  color: noteColor === ck ? 'var(--on-accent)' : NOTE_STYLES[ck].text,
                }}
              >
                <Pin size={12} aria-hidden="true" /> {NOTE_STYLES[ck].label}
              </button>
            ))}
            <button
              type="button"
              className="btn btn-soft btn-sm"
              onClick={() => handleAddStickyNote()}
            >
              + 立即新增便签
            </button>
            <span style={{ fontSize: 'var(--fs-footnote)', color: 'var(--text-muted)' }}>
              也可直接在画布任意空白处点击贴便签，拖动顶条可同步移动
            </span>
          </div>
        ) : (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            {NEON_COLORS.map((c) => {
              const isSelected = color === c && (tool === 'neon' || tool === 'pen');
              return (
                <button
                  key={c}
                  type="button"
                  aria-label={`选择画笔颜色 ${c}`}
                  onClick={() => {
                    setColor(c);
                    if (tool === 'eraser') setTool('neon');
                  }}
                  className={`wb-color-orb ${isSelected ? 'active' : ''}`}
                  style={{
                    background: c,
                    color: c,
                  }}
                />
              );
            })}
            <span style={{ fontSize: 'var(--fs-footnote)', color: 'var(--text-muted)', marginLeft: 4 }}>粗细</span>
            <input
              type="range"
              min={2}
              max={18}
              value={size}
              aria-label="画笔粗细"
              onChange={(e) => setSize(Number(e.target.value))}
              style={{ width: 92, accentColor: 'var(--accent)' }}
            />
            <span className="mono" style={{ fontSize: 'var(--fs-footnote)', color: 'var(--text-muted)' }}>
              {size}px
            </span>
          </div>
        )}

        {/* 主协作画布舞台（含实时便签层与远端彩色光标层） */}
        <div
          ref={stageRef}
          className="screenshot-canvas-wrap"
          style={{ position: 'relative', maxHeight: '56vh', aspectRatio: `${CANVAS_W}/${CANVAS_H}` }}
        >
          <canvas
            ref={canvasRef}
            style={{ width: '100%', height: '100%', touchAction: 'none', cursor: tool === 'note' ? 'copy' : 'crosshair' }}
            onPointerDown={handlePointerDown}
            onPointerMove={handlePointerMove}
            onPointerUp={handlePointerUp}
            onPointerCancel={handlePointerUp}
          />

          {/* 共享便利贴层 */}
          {notes.map((note) => {
            const st = NOTE_STYLES[note.color] || NOTE_STYLES.yellow;
            return (
              <div
                key={note.id}
                className="wb-sticky-note"
                style={{
                  left: `${note.x * 100}%`,
                  top: `${note.y * 100}%`,
                  background: st.bg,
                  color: st.text,
                }}
              >
                <div
                  className="wb-sticky-head"
                  onPointerDown={(e) => handleNoteDragStart(e, note)}
                  onPointerMove={(e) => handleNoteDragMove(e, note)}
                  onPointerUp={handleNoteDragEnd}
                  onPointerCancel={handleNoteDragEnd}
                >
                  <span>{note.authorName}</span>
                  <button
                    type="button"
                    className="wb-sticky-del"
                    onClick={(e) => {
                      e.stopPropagation();
                      onEmitEvent({ type: 'note-delete', noteId: note.id });
                    }}
                    title="删除便签"
                    aria-label="删除便签"
                  >
                    <X size={11} aria-hidden="true" />
                  </button>
                </div>
                <textarea
                  className="wb-sticky-textarea"
                  value={note.text}
                  onChange={(e) =>
                    onEmitEvent({
                      type: 'note-upsert',
                      note: { ...note, text: e.target.value.slice(0, 120), updatedAt: Date.now() },
                    })
                  }
                  placeholder="写点灵感或待办..."
                />
              </div>
            );
          })}

          {/* 远端实时彩色协同光标 */}
          {activeCursorList.map((cur) => (
            <div
              key={cur.peerId}
              className="wb-remote-cursor"
              style={{
                left: `${cur.nx * 100}%`,
                top: `${cur.ny * 100}%`,
                ['--cursor-color' as string]: cur.color || '#ff5487',
              }}
            >
              <span className="wb-cursor-dot" />
              <span className="wb-cursor-tag">{cur.name}</span>
            </div>
          ))}
        </div>

        {/* 底部导出成图发聊天室 */}
        <div style={{ display: 'flex', gap: 10 }}>
          <input
            className="field-input"
            value={caption}
            onChange={(e) => setCaption(e.target.value)}
            placeholder="给合作白板起个名字或留言，一键合图贴进聊天室..."
          />
          <button
            type="button"
            className="btn btn-jelly"
            style={{ height: 44, padding: '0 20px' }}
            onClick={handleExport}
          >
            <Send size={15} /> 合图贴进聊天室
          </button>
        </div>
      </div>
    </div>
  );
};

function roundRectPath(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number
) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.lineTo(x + w - r, y);
  ctx.quadraticCurveTo(x + w, y, x + w, y + r);
  ctx.lineTo(x + w, y + h - r);
  ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  ctx.lineTo(x + r, y + h);
  ctx.quadraticCurveTo(x, y + h, x, y + h - r);
  ctx.lineTo(x, y + r);
  ctx.quadraticCurveTo(x, y, x + r, y);
  ctx.closePath();
}

function wrapCanvasText(
  ctx: CanvasRenderingContext2D,
  text: string,
  x: number,
  y: number,
  maxWidth: number,
  lineHeight: number,
  maxLines: number
) {
  let line = '';
  let lineCount = 0;
  for (const ch of text) {
    if (ch === '\n') {
      ctx.fillText(line, x, y + lineCount * lineHeight);
      line = '';
      lineCount++;
      if (lineCount >= maxLines) return;
      continue;
    }
    const test = line + ch;
    if (ctx.measureText(test).width > maxWidth && line.length > 0) {
      ctx.fillText(line, x, y + lineCount * lineHeight);
      line = ch;
      lineCount++;
      if (lineCount >= maxLines) return;
    } else {
      line = test;
    }
  }
  if (line && lineCount < maxLines) {
    ctx.fillText(line, x, y + lineCount * lineHeight);
  }
}
