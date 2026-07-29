"use client";

import { memo } from "react";
import { Receipt } from "lucide-react";
import type { StatementData } from "@/lib/sse";
import { money } from "@/lib/format";
import { WidgetCard } from "./WidgetCard";

const CATEGORY_ORDER = ["Shopping", "Subscriptions", "Cash & ATM", "Transfers", "Interest", "Other"];

/**
 * A categorical ramp, not the status palette — "Interest" was previously red,
 * which read as an error for what is normally a credit.
 */
const CATEGORY_TINT: Record<string, string> = {
  Shopping: "bg-accent",
  Subscriptions: "bg-system",
  "Cash & ATM": "bg-[#3fb0c9]",
  Transfers: "bg-[#5f7ae8]",
  Interest: "bg-[#7bc47f]",
  Other: "bg-muted",
};

export const SpendingStatement = memo(function SpendingStatement({
  statement,
}: {
  statement: StatementData;
}) {
  const entries = Object.entries(statement.byCategory).sort(
    (a, b) => CATEGORY_ORDER.indexOf(a[0]) - CATEGORY_ORDER.indexOf(b[0]),
  );
  const total = entries.reduce((sum, [, v]) => sum + v, 0);
  if (entries.length === 0) return null;

  return (
    <WidgetCard icon={Receipt} title={`Spending · ${statement.accountId}`}>
      <div className="px-3.5 pt-2.5">
        <div className="flex h-2 gap-0.5 overflow-hidden rounded-full" aria-hidden>
          {entries.map(([cat, amt]) => (
            <div
              key={cat}
              className={CATEGORY_TINT[cat] ?? CATEGORY_TINT.Other}
              style={{ width: `${total > 0 ? (amt / total) * 100 : 0}%` }}
            />
          ))}
        </div>

        <ul className="mt-3 space-y-1.5">
          {entries.map(([cat, amt]) => (
            <li key={cat} className="flex items-center justify-between gap-3 text-label">
              <span className="flex min-w-0 items-center gap-2">
                <span
                  className={`h-2 w-2 shrink-0 rounded-full ${CATEGORY_TINT[cat] ?? CATEGORY_TINT.Other}`}
                  aria-hidden
                />
                <span className="truncate">{cat}</span>
              </span>
              <span className="shrink-0 font-medium tabular-nums">{money(amt)}</span>
            </li>
          ))}
        </ul>

        <div className="mt-3 flex items-center justify-between border-t border-hairline pt-2 text-micro text-muted">
          <span className="tabular-nums">Out {money(statement.totalDebits)}</span>
          <span className="tabular-nums">In {money(statement.totalCredits)}</span>
        </div>
      </div>
      <div className="h-3" />
    </WidgetCard>
  );
});
