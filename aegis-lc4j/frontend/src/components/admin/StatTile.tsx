"use client";

import { motion } from "framer-motion";
import type { LucideIcon } from "lucide-react";
import clsx from "clsx";

export function StatTile({
  label,
  value,
  icon: Icon,
  tone = "neutral",
  index = 0,
}: {
  label: string;
  value: string;
  icon: LucideIcon;
  tone?: "neutral" | "good" | "warning" | "critical";
  index?: number;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: index * 0.05, type: "spring", stiffness: 380, damping: 32 }}
      className="rounded-lg border border-border-soft bg-surface p-4"
    >
      <div className="flex items-center gap-2 text-muted">
        <Icon
          size={14}
          className={clsx(
            tone === "good" && "text-good",
            tone === "warning" && "text-warning",
            tone === "critical" && "text-critical",
            tone === "neutral" && "text-accent",
          )}
        />
        <span className="text-xs font-medium">{label}</span>
      </div>
      <p className="mt-2 text-2xl font-semibold tabular-nums">{value}</p>
    </motion.div>
  );
}
