"use client";

import { motion, useReducedMotion } from "framer-motion";
import clsx from "clsx";
import type { LucideIcon } from "lucide-react";

export type Tone = "neutral" | "accent" | "good" | "warning" | "critical";

/**
 * The one status badge in the app. Foreground always comes from the `-ink`
 * tier, never from the base hue — that pairing is what keeps every badge above
 * 5.5:1 on its own tint in both themes.
 */
const TONE_CLASS: Record<Tone, string> = {
  neutral: "bg-surface-2 text-muted",
  accent: "bg-accent-soft text-accent-ink",
  good: "bg-good-soft text-good-ink",
  warning: "bg-warning-soft text-warning-ink",
  critical: "bg-critical-soft text-critical-ink",
};

export function StatusBadge({ tone, children }: { tone: Tone; children: React.ReactNode }) {
  return (
    <span
      className={clsx(
        "shrink-0 rounded-full px-2 py-0.5 text-micro font-semibold uppercase tracking-wide",
        TONE_CLASS[tone],
      )}
    >
      {children}
    </span>
  );
}

/**
 * Shared shell for every receipt/status widget attached to an assistant turn.
 *
 * `nested` (the default) drops the outer border so a turn carrying three
 * widgets reads as one answer with attachments rather than four separate
 * cards — the bubble's left rail already provides the grouping.
 */
export function WidgetCard({
  icon: Icon,
  title,
  badge,
  meta,
  children,
  className,
  delay = 0,
}: {
  icon?: LucideIcon;
  title?: React.ReactNode;
  badge?: React.ReactNode;
  meta?: React.ReactNode;
  children?: React.ReactNode;
  className?: string;
  delay?: number;
}) {
  const reduceMotion = useReducedMotion();

  return (
    <motion.div
      initial={reduceMotion ? false : { opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.32, delay: reduceMotion ? 0 : delay, ease: [0.22, 1, 0.36, 1] }}
      className={clsx(
        "lit w-full max-w-sm overflow-hidden rounded-xl border border-hairline bg-surface-2/60",
        className,
      )}
    >
      {(title || badge) && (
        <div className="flex items-start justify-between gap-2 px-3.5 pt-3">
          <div className="flex min-w-0 items-center gap-2">
            {Icon && <Icon size={14} className="shrink-0 text-muted" />}
            <span className="truncate text-label font-semibold">{title}</span>
          </div>
          {badge}
        </div>
      )}
      {children}
      {meta && <p className="px-3.5 pb-3 pt-2 text-micro text-muted">{meta}</p>}
    </motion.div>
  );
}
