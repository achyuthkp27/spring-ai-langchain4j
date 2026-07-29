"use client";

import { memo } from "react";
import { motion } from "framer-motion";
import clsx from "clsx";
import { ArrowDownLeft, ArrowUpRight } from "lucide-react";
import type { TransactionData } from "@/lib/sse";

const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const formatDate = (iso: string) =>
  new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });

export const TransactionList = memo(function TransactionList({ transactions }: { transactions: TransactionData[] }) {
  if (transactions.length === 0) return null;

  return (
    <div className="mt-1 w-full max-w-sm overflow-hidden rounded-xl border border-border-soft bg-surface">
      {transactions.map((t, i) => {
        const credit = t.direction === "CREDIT";
        return (
          <motion.div
            key={t.txnId}
            initial={{ opacity: 0, x: -8 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ delay: i * 0.05 }}
            className={clsx(
              "flex items-center gap-3 px-3 py-2.5",
              i !== transactions.length - 1 && "border-b border-border-soft",
            )}
          >
            <span
              className={clsx(
                "grid h-8 w-8 shrink-0 place-items-center rounded-full",
                credit ? "bg-good-soft text-good" : "bg-surface-2 text-muted",
              )}
            >
              {credit ? <ArrowDownLeft size={14} /> : <ArrowUpRight size={14} />}
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{t.merchant}</p>
              <p className="text-[11px] text-muted">{formatDate(t.date)}</p>
            </div>
            <span className={clsx("shrink-0 text-sm font-semibold tabular-nums", credit && "text-good")}>
              {credit ? "+" : "-"}${money(t.amount)}
            </span>
          </motion.div>
        );
      })}
    </div>
  );
});
