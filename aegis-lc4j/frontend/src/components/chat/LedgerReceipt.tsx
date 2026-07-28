"use client";

import { motion } from "framer-motion";
import clsx from "clsx";
import { ArrowRight } from "lucide-react";
import type { LedgerEntryData } from "@/lib/sse";

const money = (n: number) =>
  n.toLocaleString(undefined, { style: "currency", currency: "USD" });

/** Renders the REAL double-entry posting pushed by transferBetweenOwnAccounts (see
    BankingTools.LEDGER_KEY) — the matched debit/credit pair from BankingService.transfer,
    not a summary sentence the model composed. */
export function LedgerReceipt({ entries }: { entries: LedgerEntryData[] }) {
  if (entries.length === 0) return null;
  const debit = entries.find((e) => e.direction === "DEBIT");
  const credit = entries.find((e) => e.direction === "CREDIT");
  const reference = entries[0].reference;

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ type: "spring", stiffness: 340, damping: 30 }}
      className="mt-1 w-full max-w-sm rounded-xl border border-border-soft bg-surface p-3.5"
    >
      <div className="flex items-center justify-between gap-2">
        <span className="text-[11px] font-medium uppercase tracking-wide text-muted">
          Transfer posted
        </span>
        <span className="text-[11px] text-muted">{reference}</span>
      </div>

      <div className="mt-2.5 flex items-center gap-2.5">
        <div className="flex-1 rounded-lg bg-critical-soft px-3 py-2">
          <p className="text-[10px] font-medium uppercase tracking-wide text-critical">From</p>
          <p className="text-[13px] font-medium tabular-nums">{debit?.accountId}</p>
          <p className="mt-0.5 text-sm font-semibold tabular-nums text-critical">
            −{money(Math.abs(debit?.amount ?? 0))}
          </p>
        </div>
        <ArrowRight size={15} className="shrink-0 text-muted" aria-hidden />
        <div className="flex-1 rounded-lg bg-good-soft px-3 py-2">
          <p className="text-[10px] font-medium uppercase tracking-wide text-good">To</p>
          <p className="text-[13px] font-medium tabular-nums">{credit?.accountId}</p>
          <p className="mt-0.5 text-sm font-semibold tabular-nums text-good">
            +{money(Math.abs(credit?.amount ?? 0))}
          </p>
        </div>
      </div>

      <div className="mt-2.5 flex items-center justify-between border-t border-border-soft pt-2 text-[11px] text-muted">
        <span>Balance after</span>
        <span className="tabular-nums">
          {debit && money(debit.balanceAfter)} <span className={clsx("mx-1")}>·</span>{" "}
          {credit && money(credit.balanceAfter)}
        </span>
      </div>
    </motion.div>
  );
}
