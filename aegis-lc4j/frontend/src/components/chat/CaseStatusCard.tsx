"use client";

import { memo } from "react";
import { motion } from "framer-motion";
import clsx from "clsx";
import { FileQuestion } from "lucide-react";
import type { CaseData } from "@/lib/sse";

function statusTone(status: string): "good" | "warning" | "critical" | "accent" {
  const s = status.toUpperCase();
  if (s.includes("RESOLVED") || s.includes("APPROVED")) return "good";
  if (s.includes("REJECTED") || s.includes("DENIED")) return "critical";
  if (s.includes("PENDING")) return "warning";
  return "accent";
}

export const CaseStatusCard = memo(function CaseStatusCard({ caseData }: { caseData: CaseData }) {
  const tone = statusTone(caseData.status);

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ type: "spring", stiffness: 340, damping: 30 }}
      className="mt-1 w-full max-w-sm rounded-xl border border-border-soft bg-surface p-3.5"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="flex items-center gap-2">
          <FileQuestion size={15} className="text-muted" />
          <span className="text-sm font-semibold">{caseData.caseId}</span>
        </div>
        <span
          className={clsx(
            "shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide",
            tone === "good" && "bg-good-soft text-good",
            tone === "warning" && "bg-warning-soft text-warning",
            tone === "critical" && "bg-critical-soft text-critical",
            tone === "accent" && "bg-accent-soft text-accent",
          )}
        >
          {caseData.status}
        </span>
      </div>
      <p className="mt-2 text-[13px] text-muted">
        Transaction <span className="font-medium text-foreground">{caseData.transactionId}</span> ·
        account {caseData.accountId}
      </p>
      <p className="mt-1 text-[13px] leading-snug">{caseData.reason}</p>
    </motion.div>
  );
});
