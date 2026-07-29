"use client";

import { memo } from "react";
import { ArrowRight, ArrowUpRight, ArrowDownLeft, ReceiptText } from "lucide-react";
import type { LedgerEntryData } from "@/lib/sse";
import { money } from "@/lib/format";
import { WidgetCard } from "./WidgetCard";

export const LedgerReceipt = memo(function LedgerReceipt({
  entries,
}: {
  entries: LedgerEntryData[];
}) {
  if (entries.length === 0) return null;
  const debit = entries.find((e) => e.direction === "DEBIT");
  const credit = entries.find((e) => e.direction === "CREDIT");
  const reference = entries[0].reference;
  const postedAt = entries[0].postedAt;

  return (
    <WidgetCard icon={ReceiptText} title="Transfer posted">
      <div className="flex items-stretch gap-2 px-3.5 pt-2.5">
        {/* A routine outgoing leg is neutral, not red — red is reserved for
            failures so it keeps its meaning elsewhere in the app. Direction is
            carried by an icon and a sign, never by colour alone. */}
        <div className="min-w-0 flex-1 rounded-lg border border-hairline bg-surface px-3 py-2">
          <p className="flex items-center gap-1 text-micro font-medium uppercase tracking-wide text-muted">
            <ArrowUpRight size={11} aria-hidden /> From
          </p>
          <p className="mt-0.5 truncate text-label tabular-nums">{debit?.accountId}</p>
          <p className="mt-0.5 text-label font-semibold tabular-nums">
            −{money(Math.abs(debit?.amount ?? 0))}
          </p>
        </div>
        <ArrowRight size={15} className="shrink-0 self-center text-muted" aria-hidden />
        <div className="min-w-0 flex-1 rounded-lg border border-good/25 bg-good-soft px-3 py-2">
          <p className="flex items-center gap-1 text-micro font-medium uppercase tracking-wide text-good-ink">
            <ArrowDownLeft size={11} aria-hidden /> To
          </p>
          <p className="mt-0.5 truncate text-label tabular-nums">{credit?.accountId}</p>
          <p className="mt-0.5 text-label font-semibold tabular-nums text-good-ink">
            +{money(Math.abs(credit?.amount ?? 0))}
          </p>
        </div>
      </div>

      <div className="mx-3.5 mt-2.5 flex items-center justify-between gap-2 border-t border-hairline pt-2 text-micro text-muted">
        <span>Balance after</span>
        <span className="tabular-nums">
          {debit && money(debit.balanceAfter)} · {credit && money(credit.balanceAfter)}
        </span>
      </div>

      <p className="px-3.5 pb-3 pt-1.5 text-micro text-muted">
        {reference}
        {postedAt && ` · ${new Date(postedAt).toLocaleString()}`}
      </p>
    </WidgetCard>
  );
});
