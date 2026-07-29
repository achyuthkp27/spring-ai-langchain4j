"use client";

import clsx from "clsx";

function sourceTone(source: string): "good" | "warning" | "critical" | "neutral" {
  if (source === "cache") return "good";
  if (source.startsWith("blocked")) return "warning";
  if (source === "unavailable" || source === "error") return "critical";
  return "neutral";
}

/* Foreground always from the `-ink` tier — see WidgetCard.StatusBadge. */
const TONE_CLASS: Record<string, string> = {
  good: "bg-good-soft text-good-ink",
  warning: "bg-warning-soft text-warning-ink",
  critical: "bg-critical-soft text-critical-ink",
  neutral: "bg-accent-soft text-accent-ink",
};

export function EventsTable({
  rows,
  emptyLabel = "No events yet.",
}: {
  rows: { at: string; tenant: string; source: string; elapsedMs: number; question: string }[];
  emptyLabel?: string;
}) {
  if (rows.length === 0) {
    return <p className="py-6 text-center text-label text-muted">{emptyLabel}</p>;
  }
  return (
    <div className="scroll-slim max-h-96 overflow-auto rounded-xl border border-hairline">
      <table className="w-full min-w-[640px] text-left text-micro">
        <thead className="sticky top-0 z-10 bg-surface-2 text-muted">
          <tr>
            <th scope="col" className="px-3 py-2 font-medium">
              Time
            </th>
            <th scope="col" className="px-3 py-2 font-medium">
              Tenant
            </th>
            <th scope="col" className="px-3 py-2 font-medium">
              Source
            </th>
            <th scope="col" className="px-3 py-2 font-medium tabular-nums">
              Latency
            </th>
            <th scope="col" className="px-3 py-2 font-medium">
              Question
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i} className="border-t border-hairline">
              <td className="whitespace-nowrap px-3 py-1.5 text-muted">
                {new Date(r.at).toLocaleTimeString()}
              </td>
              <td className="px-3 py-1.5">{r.tenant}</td>
              <td className="px-3 py-1.5">
                <span
                  className={clsx(
                    "inline-flex items-center gap-1 rounded-full px-2 py-0.5 font-medium",
                    TONE_CLASS[sourceTone(r.source)],
                  )}
                >
                  {r.source}
                </span>
              </td>
              <td className="px-3 py-1.5 tabular-nums text-muted">{r.elapsedMs}ms</td>
              <td className="max-w-xs truncate px-3 py-1.5 text-muted">{r.question}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
