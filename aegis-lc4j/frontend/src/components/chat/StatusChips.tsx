"use client";

import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import clsx from "clsx";
import { Loader2, Sparkles } from "lucide-react";

function isEscalation(status: string): boolean {
  return status.toLowerCase().includes("extended reasoning");
}

/**
 * Fade only. These update rapidly during generation, in the same region as the
 * streaming caret and the auto-scroll — a spring here reads as three things
 * competing for attention at the moment the user is trying to read.
 */
export function StatusChips({ statuses }: { statuses: string[] }) {
  const reduceMotion = useReducedMotion();

  return (
    <div className="ml-10 flex flex-wrap gap-1.5">
      <AnimatePresence mode="popLayout" initial={false}>
        {statuses.map((s, i) => {
          const escalated = isEscalation(s);
          return (
            <motion.span
              key={`${s}-${i}`}
              initial={reduceMotion ? false : { opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.18 }}
              className={clsx(
                "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-micro font-medium",
                escalated
                  ? "border-system/25 bg-system-soft text-system-ink"
                  : "border-hairline bg-surface-2/60 text-muted",
              )}
            >
              {escalated ? (
                <Sparkles size={11} className="shrink-0" aria-hidden />
              ) : (
                <Loader2 size={11} className="shrink-0 animate-spin" aria-hidden />
              )}
              {s}
            </motion.span>
          );
        })}
      </AnimatePresence>
    </div>
  );
}
