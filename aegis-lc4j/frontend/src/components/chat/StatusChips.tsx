"use client";

import { AnimatePresence, motion } from "framer-motion";
import { Loader2 } from "lucide-react";

/** Live tool-progress pills ("Freezing card CRD-7001…") during a turn. */
export function StatusChips({ statuses }: { statuses: string[] }) {
  return (
    <div className="flex flex-wrap gap-1.5 min-h-0">
      <AnimatePresence mode="popLayout">
        {statuses.map((s, i) => (
          <motion.span
            key={`${s}-${i}`}
            layout
            initial={{ opacity: 0, scale: 0.85, x: -8 }}
            animate={{ opacity: 1, scale: 1, x: 0 }}
            exit={{ opacity: 0, scale: 0.85 }}
            transition={{ type: "spring", stiffness: 500, damping: 32 }}
            className="inline-flex items-center gap-1.5 rounded-full bg-accent-soft text-accent px-3 py-1 text-xs font-medium"
          >
            <Loader2 size={11} className="animate-spin" />
            {s}
          </motion.span>
        ))}
      </AnimatePresence>
    </div>
  );
}
