"use client";

import { motion, useReducedMotion } from "framer-motion";
import { BrandMark } from "@/components/BrandMark";

export function TypingIndicator() {
  const reduceMotion = useReducedMotion();

  return (
    <motion.div
      initial={reduceMotion ? false : { opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.25 }}
      className="flex w-full gap-3"
    >
      <span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center self-start rounded-lg border border-hairline bg-surface-2">
        <BrandMark size={14} gradientId="typing-mark" />
      </span>
      <div className="flex items-center gap-1.5 pt-2">
        {[0, 1, 2].map((i) => (
          <motion.span
            key={i}
            className="block h-1.5 w-1.5 rounded-full bg-muted"
            animate={reduceMotion ? { opacity: 0.6 } : { y: [0, -3, 0], opacity: [0.35, 1, 0.35] }}
            transition={
              reduceMotion
                ? undefined
                : { duration: 1, repeat: Infinity, delay: i * 0.15, ease: "easeInOut" }
            }
          />
        ))}
      </div>
    </motion.div>
  );
}
