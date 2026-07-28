"use client";

import { motion } from "framer-motion";
import { Receipt } from "lucide-react";
import type { StatementData } from "@/lib/sse";

const money = (n: number) =>
  n.toLocaleString(undefined, { style: "currency", currency: "USD" });

// Fixed categorical order (dataviz skill: assign hue by fixed order, never cycled) —
// matches BankingTools.categoryOf's exact heuristic buckets, plus a catch-all.
const CATEGORY_ORDER = ["Shopping", "Subscriptions", "Cash & ATM", "Transfers", "Interest", "Other"];
const CATEGORY_TINT: Record<string, string> = {
  Shopping: "bg-accent",
  Subscriptions: "bg-system",
  "Cash & ATM": "bg-warning",
  Transfers: "bg-good",
  Interest: "bg-critical",
  Other: "bg-muted",
};

/** Renders the REAL per-category totals pushed by getSpendingSummary (see
    BankingTools.STATEMENT_KEY) as a proportional bar breakdown instead of prose. */
export function SpendingStatement({ statement }: { statement: StatementData }) {
  const entries = Object.entries(statement.byCategory).sort(
    (a, b) => CATEGORY_ORDER.indexOf(a[0]) - CATEGORY_ORDER.indexOf(b[0]),
  );
  const total = entries.reduce((sum, [, v]) => sum + v, 0);
  if (entries.length === 0) return null;

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ type: "spring", stiffness: 340, damping: 30 }}
      className="mt-1 w-full max-w-sm rounded-xl border border-border-soft bg-surface p-3.5"
    >
      <div className="flex items-center gap-1.5 text-muted">
        <Receipt size={13} />
        <span className="text-[11px] font-medium uppercase tracking-wide">
          Spending · {statement.accountId}
        </span>
      </div>

      {/* Proportional stacked bar — 2px surface gaps between segments per dataviz mark spec. */}
      <div className="mt-2.5 flex h-2 gap-0.5 overflow-hidden rounded-full">
        {entries.map(([cat, amt]) => (
          <div
            key={cat}
            className={CATEGORY_TINT[cat] ?? CATEGORY_TINT.Other}
            style={{ width: `${total > 0 ? (amt / total) * 100 : 0}%` }}
          />
        ))}
      </div>

      <ul className="mt-2.5 space-y-1.5">
        {entries.map(([cat, amt]) => (
          <li key={cat} className="flex items-center justify-between text-[13px]">
            <span className="flex items-center gap-1.5">
              <span className={`h-2 w-2 shrink-0 rounded-full ${CATEGORY_TINT[cat] ?? CATEGORY_TINT.Other}`} />
              {cat}
            </span>
            <span className="tabular-nums font-medium">{money(amt)}</span>
          </li>
        ))}
      </ul>

      <div className="mt-2.5 flex items-center justify-between border-t border-border-soft pt-2 text-[11px] text-muted">
        <span>Total debits {money(statement.totalDebits)}</span>
        <span>Credits {money(statement.totalCredits)}</span>
      </div>
    </motion.div>
  );
}
