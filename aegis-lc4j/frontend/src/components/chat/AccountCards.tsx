"use client";

import { memo } from "react";
import { motion } from "framer-motion";
import clsx from "clsx";
import { PiggyBank, Wallet } from "lucide-react";
import type { AccountData } from "@/lib/sse";

const TYPE_STYLE: Record<AccountData["type"], { accent: string; icon: typeof Wallet; label: string }> = {
  CHECKING: { accent: "bg-accent", icon: Wallet, label: "Checking" },
  SAVINGS: { accent: "bg-system", icon: PiggyBank, label: "Savings" },
};

const money = (n: number) =>
  n.toLocaleString(undefined, { style: "currency", currency: "USD" });

export const AccountCards = memo(function AccountCards({ accounts }: { accounts: AccountData[] }) {
  if (accounts.length === 0) return null;

  return (
    <div className="mt-1 flex gap-3 overflow-x-auto pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
      {accounts.map((a, i) => {
        const style = TYPE_STYLE[a.type] ?? TYPE_STYLE.CHECKING;
        const Icon = style.icon;
        return (
          <motion.div
            key={a.accountId}
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: i * 0.06, type: "spring", stiffness: 340, damping: 30 }}
            className="relative w-56 shrink-0 overflow-hidden rounded-xl border border-border-soft bg-surface pl-4 pr-4 py-3"
          >
            <span className={clsx("absolute left-0 top-0 h-full w-1", style.accent)} aria-hidden />
            <div className="flex items-center gap-1.5 text-muted">
              <Icon size={13} />
              <span className="text-[11px] font-medium uppercase tracking-wide">{style.label}</span>
            </div>
            <p className="mt-2 text-xl font-semibold tabular-nums">{money(a.balance)}</p>
            <p className="mt-0.5 text-[11px] text-muted">{a.accountId}</p>
          </motion.div>
        );
      })}
    </div>
  );
});
