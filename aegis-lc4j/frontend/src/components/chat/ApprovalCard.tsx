"use client";

import { memo } from "react";
import { motion } from "framer-motion";
import clsx from "clsx";
import { Clock3, ShieldCheck } from "lucide-react";
import type { ApprovalData } from "@/lib/sse";

function statusTone(status: string): "good" | "warning" | "critical" {
  const s = status.toUpperCase();
  if (s.includes("APPROVED")) return "good";
  if (s.includes("REJECTED") || s.includes("DENIED")) return "critical";
  return "warning"; 
}

const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export const ApprovalCard = memo(function ApprovalCard({ approval }: { approval: ApprovalData }) {
  const tone = statusTone(approval.status);
  const pending = tone === "warning";

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ type: "spring", stiffness: 340, damping: 30 }}
      className="mt-1 w-full max-w-sm rounded-xl border border-border-soft bg-surface p-3.5"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="flex items-center gap-2">
          {pending ? (
            <Clock3 size={15} className="text-warning" />
          ) : (
            <ShieldCheck size={15} className="text-good" />
          )}
          <span className="text-sm font-semibold">{approval.subject}</span>
        </div>
        <span
          className={clsx(
            "shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide",
            tone === "good" && "bg-good-soft text-good",
            tone === "warning" && "bg-warning-soft text-warning",
            tone === "critical" && "bg-critical-soft text-critical",
          )}
        >
          {approval.status.replaceAll("_", " ")}
        </span>
      </div>
      <p className="mt-2 text-xl font-semibold tabular-nums">${money(approval.amount)}</p>
      <p className="mt-1 text-[12px] text-muted">
        {approval.approvalId} · no funds have moved — bank staff will review this request
      </p>
    </motion.div>
  );
});
