"use client";

import { useMemo, useState } from "react";
import clsx from "clsx";
import { Table2, LineChart as LineChartIcon } from "lucide-react";
import type { TimeseriesPoint } from "@/lib/adminApi";

const SERIES: { key: keyof Omit<TimeseriesPoint, "minute">; label: string; varName: string }[] = [
  { key: "llm", label: "Model calls", varName: "--accent" },
  { key: "cache", label: "Cache hits", varName: "--good" },
  { key: "blocked", label: "Blocked", varName: "--warning" },
  { key: "error", label: "Errors", varName: "--critical" },
];

const WIDTH = 640;
const HEIGHT = 200;
const PAD_LEFT = 8;
const PAD_RIGHT = 8;
const PAD_TOP = 8;
const PAD_BOTTOM = 20;

export function TrafficChart({ data }: { data: TimeseriesPoint[] }) {
  const [tableView, setTableView] = useState(false);
  const [hoverIdx, setHoverIdx] = useState<number | null>(null);

  const { bands, maxTotal, points } = useMemo(() => {
    const totals = data.map((p) => p.llm + p.cache + p.blocked + p.error);
    const maxTotal = Math.max(1, ...totals);
    const innerW = WIDTH - PAD_LEFT - PAD_RIGHT;
    const innerH = HEIGHT - PAD_TOP - PAD_BOTTOM;
    const x = (i: number) => PAD_LEFT + (data.length <= 1 ? 0 : (i / (data.length - 1)) * innerW);
    const y = (v: number) => PAD_TOP + innerH - (v / maxTotal) * innerH;

    const cum: number[][] = data.map(() => [0, 0, 0, 0, 0]);
    data.forEach((p, i) => {
      const vals = [p.llm, p.cache, p.blocked, p.error];
      let running = 0;
      cum[i][0] = 0;
      vals.forEach((v, s) => {
        running += v;
        cum[i][s + 1] = running;
      });
    });

    const bands = SERIES.map((s, sIdx) => {
      const top = data.map((_, i) => `${x(i)},${y(cum[i][sIdx + 1])}`).join(" L ");
      const bottom = data
        .map((_, i) => `${x(data.length - 1 - i)},${y(cum[data.length - 1 - i][sIdx])}`)
        .join(" L ");
      return { key: s.key, label: s.label, varName: s.varName, path: `M ${top} L ${bottom} Z` };
    });

    const points = data.map((_, i) => ({ x: x(i), i }));
    return { bands, maxTotal, points };
  }, [data]);

  if (data.length === 0) {
    return <p className="py-8 text-center text-sm text-muted">No traffic yet in this window.</p>;
  }

  const hovered = hoverIdx !== null ? data[hoverIdx] : null;

  return (
    <div>
      {}
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap gap-3">
          {SERIES.map((s) => (
            <span key={s.key} className="flex items-center gap-1.5 text-xs text-muted">
              <span
                className="h-2 w-2 rounded-full"
                style={{ background: `var(${s.varName})` }}
                aria-hidden
              />
              {s.label}
            </span>
          ))}
        </div>
        <button
          onClick={() => setTableView((v) => !v)}
          className="flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-muted hover:bg-surface-2 transition-colors"
          aria-label={tableView ? "Show chart view" : "Show table view"}
        >
          {tableView ? <LineChartIcon size={13} /> : <Table2 size={13} />}
          {tableView ? "Chart" : "Table"}
        </button>
      </div>

      {tableView ? (
        <div className="max-h-64 overflow-y-auto rounded-xl border border-border-soft">
          <table className="w-full text-left text-xs">
            <thead className="sticky top-0 bg-surface-2 text-muted">
              <tr>
                <th className="px-3 py-2 font-medium">Minute</th>
                {SERIES.map((s) => (
                  <th key={s.key} className="px-3 py-2 font-medium tabular-nums">{s.label}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data.map((p) => (
                <tr key={p.minute} className="border-t border-border-soft">
                  <td className="px-3 py-1.5 text-muted">{new Date(p.minute).toLocaleTimeString()}</td>
                  {SERIES.map((s) => (
                    <td key={s.key} className="px-3 py-1.5 tabular-nums">{p[s.key]}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="relative">
          <svg
            viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
            className="w-full"
            onMouseMove={(e) => {
              const rect = e.currentTarget.getBoundingClientRect();
              const relX = ((e.clientX - rect.left) / rect.width) * WIDTH;
              const idx = Math.round(((relX - PAD_LEFT) / (WIDTH - PAD_LEFT - PAD_RIGHT)) * (data.length - 1));
              setHoverIdx(Math.min(data.length - 1, Math.max(0, idx)));
            }}
            onMouseLeave={() => setHoverIdx(null)}
          >
            {}
            <line
              x1={PAD_LEFT}
              y1={HEIGHT - PAD_BOTTOM}
              x2={WIDTH - PAD_RIGHT}
              y2={HEIGHT - PAD_BOTTOM}
              stroke="var(--border)"
              strokeWidth={1}
            />
            {bands.map((b) => (
              <path key={b.key} d={b.path} fill={`var(${b.varName})`} fillOpacity={0.55} />
            ))}
            {hoverIdx !== null && (
              <line
                x1={points[hoverIdx].x}
                y1={PAD_TOP}
                x2={points[hoverIdx].x}
                y2={HEIGHT - PAD_BOTTOM}
                stroke="var(--muted)"
                strokeWidth={1}
                strokeDasharray="3 3"
              />
            )}
          </svg>
          {hovered && (
            <div
              className={clsx(
                "pointer-events-none absolute top-2 rounded-lg border border-border-soft bg-surface px-2.5 py-2 text-[11px] shadow-lg",
                hoverIdx! > data.length / 2 ? "right-2" : "left-2",
              )}
            >
              <p className="mb-1 font-medium text-muted">{new Date(hovered.minute).toLocaleTimeString()}</p>
              {SERIES.map((s) => (
                <p key={s.key} className="flex items-center gap-1.5 tabular-nums">
                  <span className="h-1.5 w-1.5 rounded-full" style={{ background: `var(${s.varName})` }} aria-hidden />
                  {s.label}: {hovered[s.key]}
                </p>
              ))}
            </div>
          )}
        </div>
      )}
      <p className="mt-1 text-right text-[10px] text-muted">peak {maxTotal}/min</p>
    </div>
  );
}
