"use client";

import { memo } from "react";
import { motion, useReducedMotion } from "framer-motion";
import clsx from "clsx";
import { PiggyBank, Wallet } from "lucide-react";
import type { AccountData } from "@/lib/sse";
import { money } from "@/lib/format";

const TYPE_STYLE: Record<AccountData["type"], { rail: string; icon: typeof Wallet; label: string }> = {
  CHECKING: { rail: "bg-accent", icon: Wallet, label: "Checking" },
  SAVINGS: { rail: "bg-system", icon: PiggyBank, label: "Savings" },
};

export const AccountCards = memo(function AccountCards({ accounts }: { accounts: AccountData[] }) {
  const reduceMotion = useReducedMotion();
  if (accounts.length === 0) return null;

  return (
    <div className="scroll-none flex w-full gap-2.5 overflow-x-auto pb-1">
      {accounts.map((a, i) => {
        const style = TYPE_STYLE[a.type] ?? TYPE_STYLE.CHECKING;
        const Icon = style.icon;
        return (
          <motion.div
            key={a.accountId}
            initial={reduceMotion ? false : { opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: reduceMotion ? 0 : i * 0.05, duration: 0.3 }}
            className="lit relative w-52 shrink-0 overflow-hidden rounded-xl border border-hairline bg-surface-2/60 px-3.5 py-3"
          >
            <span className={clsx("absolute left-0 top-0 h-full w-0.5", style.rail)} aria-hidden />
            <div className="flex items-center gap-1.5 text-muted">
              <Icon size={13} aria-hidden />
              <span className="text-micro font-medium uppercase tracking-wide">{style.label}</span>
            </div>
            <p className="mt-2 text-figure font-semibold tabular-nums">{money(a.balance)}</p>
            <p className="mt-0.5 text-micro tabular-nums text-muted">{a.accountId}</p>
          </motion.div>
        );
      })}
    </div>
  );
});
