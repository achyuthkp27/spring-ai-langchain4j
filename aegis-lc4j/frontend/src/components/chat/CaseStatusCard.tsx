"use client";

import { memo } from "react";
import { FileQuestion } from "lucide-react";
import type { CaseData } from "@/lib/sse";
import { StatusBadge, WidgetCard, type Tone } from "./WidgetCard";

function statusTone(status: string): Tone {
  const s = status.toUpperCase();
  if (s.includes("RESOLVED") || s.includes("APPROVED")) return "good";
  if (s.includes("REJECTED") || s.includes("DENIED")) return "critical";
  if (s.includes("PENDING")) return "warning";
  return "accent";
}

export const CaseStatusCard = memo(function CaseStatusCard({ caseData }: { caseData: CaseData }) {
  return (
    <WidgetCard
      icon={FileQuestion}
      title={caseData.caseId}
      badge={<StatusBadge tone={statusTone(caseData.status)}>{caseData.status}</StatusBadge>}
    >
      <div className="px-3.5 pb-3 pt-2">
        <p className="text-label leading-snug">{caseData.reason}</p>
        <p className="mt-1.5 text-micro text-muted">
          Transaction <span className="font-medium text-foreground">{caseData.transactionId}</span> ·
          account {caseData.accountId}
        </p>
      </div>
    </WidgetCard>
  );
});
