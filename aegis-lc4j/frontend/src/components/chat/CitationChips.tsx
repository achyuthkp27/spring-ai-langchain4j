"use client";

import { motion } from "framer-motion";
import { FileText } from "lucide-react";
import type { CitationData } from "@/lib/sse";

/** Renders the REAL cited policy passages pushed by searchPolicies (see
    BankingTools.CITATIONS_KEY) as small source chips instead of leaving citations buried
    in the model's prose. Hover a chip for the actual snippet it was grounded in. */
export function CitationChips({ citations }: { citations: CitationData[] }) {
  if (citations.length === 0) return null;

  return (
    <div className="mt-1.5 flex flex-wrap gap-1.5">
      {citations.map((c, i) => (
        <motion.span
          key={c.source}
          initial={{ opacity: 0, y: 4 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: i * 0.04 }}
          title={c.snippet}
          className="flex max-w-[220px] items-center gap-1.5 rounded-full border border-border-soft bg-surface-2 px-2.5 py-1 text-[11px] text-muted"
        >
          <FileText size={11} className="shrink-0" />
          <span className="truncate">{c.source}</span>
        </motion.span>
      ))}
    </div>
  );
}
