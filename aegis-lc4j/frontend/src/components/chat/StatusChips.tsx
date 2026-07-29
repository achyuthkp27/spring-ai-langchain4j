"use client";

import { AnimatePresence, motion } from "framer-motion";
import clsx from "clsx";
import { Loader2, Sparkles } from "lucide-react";

function isEscalation(status: string): boolean {
  return status.toLowerCase().includes("extended reasoning");
}

export function StatusChips({ statuses }: { statuses: string[] }) {
  return (
    <div className="flex flex-wrap gap-1.5 min-h-0">
      <AnimatePresence mode="popLayout">
        {statuses.map((s, i) => {
          const escalated = isEscalation(s);
          return (
            <motion.span
              key={`${s}-${i}`}
              layout
              initial={{ opacity: 0, scale: 0.85, x: -8 }}
              animate={{ opacity: 1, scale: 1, x: 0 }}
              exit={{ opacity: 0, scale: 0.85 }}
              transition={{ type: "spring", stiffness: 500, damping: 32 }}
              className={clsx(
                "inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium",
                escalated ? "bg-system-soft text-system" : "bg-accent-soft text-accent",
              )}
            >
              {escalated ? <Sparkles size={11} /> : <Loader2 size={11} className="animate-spin" />}
              {s}
            </motion.span>
          );
        })}
      </AnimatePresence>
    </div>
  );
}
