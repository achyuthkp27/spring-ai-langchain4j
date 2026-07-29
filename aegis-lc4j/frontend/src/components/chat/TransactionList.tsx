"use client";

import { memo } from "react";
import { motion, useReducedMotion } from "framer-motion";
import clsx from "clsx";
import { ArrowDownLeft, ArrowUpRight } from "lucide-react";
import type { TransactionData } from "@/lib/sse";
import { shortDate } from "@/lib/format";
import { WidgetCard } from "./WidgetCard";

const amount = (n: number) =>
  n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export const TransactionList = memo(function TransactionList({
  transactions,
}: {
  transactions: TransactionData[];
}) {
  const reduceMotion = useReducedMotion();
  if (transactions.length === 0) return null;

  return (
    <WidgetCard>
      <ul>
        {transactions.map((t, i) => {
          const credit = t.direction === "CREDIT";
          return (
            <motion.li
              key={t.txnId}
              initial={reduceMotion ? false : { opacity: 0, x: -6 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ delay: reduceMotion ? 0 : i * 0.035, duration: 0.25 }}
              className={clsx(
                "flex items-center gap-3 px-3.5 py-2.5",
                i !== transactions.length - 1 && "border-b border-hairline",
              )}
            >
              <span
                className={clsx(
                  "grid h-8 w-8 shrink-0 place-items-center rounded-full",
                  credit ? "bg-good-soft text-good-ink" : "bg-surface text-muted",
                )}
                aria-hidden
              >
                {credit ? <ArrowDownLeft size={14} /> : <ArrowUpRight size={14} />}
              </span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-label font-medium">{t.merchant}</p>
                <p className="text-micro text-muted">{shortDate(t.date)}</p>
              </div>
              <span
                className={clsx(
                  "shrink-0 text-label font-semibold tabular-nums",
                  credit ? "text-good-ink" : "text-foreground",
                )}
              >
                <span className="sr-only">{credit ? "Credit of " : "Debit of "}</span>
                {credit ? "+" : "−"}${amount(t.amount)}
              </span>
            </motion.li>
          );
        })}
      </ul>
    </WidgetCard>
  );
});
