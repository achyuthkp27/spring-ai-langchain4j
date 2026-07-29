"use client";

import clsx from "clsx";

function sourceTone(source: string): "good" | "warning" | "critical" | "neutral" {
  if (source === "cache") return "good";
  if (source.startsWith("blocked")) return "warning";
  if (source === "unavailable" || source === "error") return "critical";
  return "neutral";
}

export function EventsTable({
  rows,
  emptyLabel = "No events yet.",
}: {
  rows: { at: string; tenant: string; source: string; elapsedMs: number; question: string }[];
  emptyLabel?: string;
}) {
  if (rows.length === 0) {
    return <p className="py-6 text-center text-sm text-muted">{emptyLabel}</p>;
  }
  return (
    <div className="max-h-96 overflow-y-auto rounded-xl border border-border-soft">
      <table className="w-full text-left text-xs">
        <thead className="sticky top-0 bg-surface-2 text-muted">
          <tr>
            <th className="px-3 py-2 font-medium">Time</th>
            <th className="px-3 py-2 font-medium">Tenant</th>
            <th className="px-3 py-2 font-medium">Source</th>
            <th className="px-3 py-2 font-medium tabular-nums">Latency</th>
            <th className="px-3 py-2 font-medium">Question</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => {
            const tone = sourceTone(r.source);
            return (
              <tr key={i} className="border-t border-border-soft">
                <td className="px-3 py-1.5 whitespace-nowrap text-muted">
                  {new Date(r.at).toLocaleTimeString()}
                </td>
                <td className="px-3 py-1.5">{r.tenant}</td>
                <td className="px-3 py-1.5">
                  <span
                    className={clsx(
                      "inline-flex items-center gap-1 rounded-full px-2 py-0.5 font-medium",
                      tone === "good" && "bg-good-soft text-good",
                      tone === "warning" && "bg-warning-soft text-warning",
                      tone === "critical" && "bg-critical-soft text-critical",
                      tone === "neutral" && "bg-accent-soft text-accent",
                    )}
                  >
                    {r.source}
                  </span>
                </td>
                <td className="px-3 py-1.5 tabular-nums text-muted">{r.elapsedMs}ms</td>
                <td className="max-w-xs truncate px-3 py-1.5 text-muted">{r.question}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
