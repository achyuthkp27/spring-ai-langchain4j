"use client";

import { motion } from "framer-motion";
import { Landmark } from "lucide-react";

export function TypingIndicator() {
  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0 }}
      className="flex w-full items-end gap-2"
    >
      <div className="grid h-7 w-7 shrink-0 place-items-center self-start rounded-md bg-foreground text-background">
        <Landmark size={13} />
      </div>
      <div className="flex items-center gap-1.5 rounded-2xl rounded-bl-md bg-surface border border-border-soft px-4 py-3 w-fit">
        {[0, 1, 2].map((i) => (
          <motion.span
            key={i}
            className="block h-1.5 w-1.5 rounded-full bg-muted"
            animate={{ y: [0, -4, 0], opacity: [0.4, 1, 0.4] }}
            transition={{ duration: 0.9, repeat: Infinity, delay: i * 0.15, ease: "easeInOut" }}
          />
        ))}
      </div>
    </motion.div>
  );
}
