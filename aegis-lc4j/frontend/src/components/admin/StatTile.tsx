"use client";

import { motion, useReducedMotion } from "framer-motion";
import type { LucideIcon } from "lucide-react";
import clsx from "clsx";

const TONE_ICON: Record<string, string> = {
  neutral: "text-accent",
  good: "text-good",
  warning: "text-warning",
  critical: "text-critical",
};

const TONE_VALUE: Record<string, string> = {
  neutral: "text-foreground",
  good: "text-good-ink",
  warning: "text-warning-ink",
  critical: "text-critical-ink",
};

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
  const reduceMotion = useReducedMotion();

  return (
    <motion.div
      initial={reduceMotion ? false : { opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: reduceMotion ? 0 : index * 0.05, duration: 0.32 }}
      className="panel lit p-4"
    >
      <div className="flex items-center gap-2 text-muted">
        <Icon size={14} className={clsx("shrink-0", TONE_ICON[tone])} aria-hidden />
        <span className="text-micro font-medium uppercase tracking-wide">{label}</span>
      </div>
      <p className={clsx("mt-2 text-figure font-semibold tabular-nums", TONE_VALUE[tone])}>
        {value}
      </p>
    </motion.div>
  );
}
