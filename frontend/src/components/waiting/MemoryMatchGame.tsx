import { useCallback, useEffect, useRef, useState } from 'react';
import { Bug, Cloud, Code2, Cpu, Database, GitBranch, RotateCcw, Trophy } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

interface CardFace {
  Icon: LucideIcon;
  color: string;
  label: string;
}

const FACES: CardFace[] = [
  { Icon: Code2, color: 'text-indigo-500', label: '代码' },
  { Icon: Database, color: 'text-emerald-500', label: '数据库' },
  { Icon: Cpu, color: 'text-amber-500', label: '芯片' },
  { Icon: Cloud, color: 'text-sky-500', label: '云' },
  { Icon: GitBranch, color: 'text-rose-500', label: '分支' },
  { Icon: Bug, color: 'text-violet-500', label: '虫子' },
];

const BEST_KEY = 'ig-waiting-memory-best';

interface Card {
  key: number;
  face: number;
  matched: boolean;
}

function shuffleDeck(): Card[] {
  const faces = FACES.flatMap((_, idx) => [idx, idx]);
  for (let i = faces.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [faces[i], faces[j]] = [faces[j], faces[i]];
  }
  return faces.map((face, key) => ({ key, face, matched: false }));
}

function readBest(): number | null {
  try {
    const raw = window.localStorage.getItem(BEST_KEY);
    const n = raw ? Number(raw) : NaN;
    return Number.isFinite(n) && n > 0 ? n : null;
  } catch {
    return null;
  }
}

/**
 * 等待 AI 生成时的轻量记忆翻牌小游戏：纯前端、无网络请求、触屏友好
 */
export default function MemoryMatchGame() {
  const [cards, setCards] = useState<Card[]>(shuffleDeck);
  const [flipped, setFlipped] = useState<number[]>([]);
  const [moves, setMoves] = useState(0);
  const [locked, setLocked] = useState(false);
  const [best, setBest] = useState<number | null>(readBest);
  const timerRef = useRef<number | null>(null);

  const matchedCount = cards.filter(c => c.matched).length;
  const won = matchedCount === cards.length;

  useEffect(() => () => {
    if (timerRef.current != null) window.clearTimeout(timerRef.current);
  }, []);

  useEffect(() => {
    if (!won || moves === 0) return;
    if (best == null || moves < best) {
      setBest(moves);
      try {
        window.localStorage.setItem(BEST_KEY, String(moves));
      } catch {
        // ignore storage errors
      }
    }
  }, [won, moves, best]);

  const restart = useCallback(() => {
    if (timerRef.current != null) window.clearTimeout(timerRef.current);
    setCards(shuffleDeck());
    setFlipped([]);
    setMoves(0);
    setLocked(false);
  }, []);

  const handleFlip = (idx: number) => {
    if (locked || flipped.includes(idx) || cards[idx].matched) return;
    const next = [...flipped, idx];
    if (next.length < 2) {
      setFlipped(next);
      return;
    }
    setMoves(m => m + 1);
    const [a, b] = next;
    if (cards[a].face === cards[b].face) {
      setCards(prev => prev.map((c, i) => (i === a || i === b ? { ...c, matched: true } : c)));
      setFlipped([]);
      return;
    }
    setFlipped(next);
    setLocked(true);
    timerRef.current = window.setTimeout(() => {
      setFlipped([]);
      setLocked(false);
    }, 750);
  };

  return (
    <div className="space-y-3" data-testid="waiting-memory-game">
      <div className="flex items-center justify-between text-xs text-slate-500 dark:text-slate-400">
        <span>
          记忆翻牌 · 已配对 {matchedCount / 2} / {FACES.length} · 步数 {moves}
        </span>
        <button
          type="button"
          onClick={restart}
          className="inline-flex items-center gap-1 rounded-lg px-2 py-1 font-medium text-primary-600 hover:bg-primary-50 dark:text-primary-400 dark:hover:bg-primary-900/30"
        >
          <RotateCcw className="h-3.5 w-3.5" />
          重开
        </button>
      </div>

      <div className="grid grid-cols-4 gap-2 sm:gap-2.5">
        {cards.map((card, idx) => {
          const open = card.matched || flipped.includes(idx);
          const face = FACES[card.face];
          return (
            <button
              key={card.key}
              type="button"
              aria-label={open ? face.label : '未翻开的卡片'}
              onClick={() => handleFlip(idx)}
              className={`flex aspect-square items-center justify-center rounded-xl border text-sm transition-all duration-200 select-none touch-manipulation ${
                open
                  ? card.matched
                    ? 'border-emerald-200 bg-emerald-50 dark:border-emerald-800/60 dark:bg-emerald-900/25'
                    : 'border-primary-200 bg-white dark:border-primary-700/60 dark:bg-slate-800'
                  : 'border-transparent bg-gradient-to-br from-primary-500 to-indigo-500 shadow-sm active:scale-95'
              }`}
            >
              {open ? (
                <face.Icon className={`h-6 w-6 sm:h-7 sm:w-7 ${face.color}`} />
              ) : (
                <span className="text-base font-bold text-white/80">?</span>
              )}
            </button>
          );
        })}
      </div>

      {won && (
        <div className="flex items-center justify-center gap-1.5 rounded-xl bg-emerald-50 px-3 py-2 text-xs font-medium text-emerald-700 dark:bg-emerald-900/25 dark:text-emerald-300">
          <Trophy className="h-4 w-4" />
          全部配对完成，用了 {moves} 步{best != null ? `（最佳 ${best} 步）` : ''}，再来一局？
        </div>
      )}
    </div>
  );
}
