"use client";

import { memo } from "react";
import { Clock3, ShieldCheck, XCircle } from "lucide-react";
import type { ApprovalData } from "@/lib/sse";
import { money } from "@/lib/format";
import { StatusBadge, WidgetCard, type Tone } from "./WidgetCard";

function statusTone(status: string): Tone {
  const s = status.toUpperCase();
  if (s.includes("APPROVED")) return "good";
  if (s.includes("REJECTED") || s.includes("DENIED")) return "critical";
  return "warning";
}

/**
 * `subject` is an internal, colon-delimited key. The customer sees a sentence
 * and a clean reference instead of "CARD-REPLACEMENT:CRD-7001".
 */
function describe(subject: string): { label: string; ref: string | null } {
  const [head, tail] = subject.split(":", 2);
  if (tail) {
    const label = head
      .toLowerCase()
      .split("-")
      .join(" ")
      .replace(/^\w/, (c) => c.toUpperCase());
    return { label, ref: tail };
  }
  if (/^CASE-/i.test(subject)) return { label: "Provisional credit", ref: subject };
  return { label: subject, ref: null };
}

export const ApprovalCard = memo(function ApprovalCard({ approval }: { approval: ApprovalData }) {
  const tone = statusTone(approval.status);
  const { label, ref } = describe(approval.subject);
  const Icon = tone === "good" ? ShieldCheck : tone === "critical" ? XCircle : Clock3;
  const hasAmount = approval.amount > 0;

  return (
    <WidgetCard
      icon={Icon}
      title={label}
      badge={<StatusBadge tone={tone}>{approval.status.replaceAll("_", " ")}</StatusBadge>}
    >
      <div className="px-3.5 pt-2">
        {hasAmount ? (
          <p className="text-figure font-semibold tabular-nums">{money(approval.amount)}</p>
        ) : (
          <p className="text-label text-muted">No amount attached to this request.</p>
        )}
        {ref && <p className="mt-0.5 text-micro tabular-nums text-muted">{ref}</p>}
      </div>
      <p className="px-3.5 pb-3 pt-2.5 text-micro leading-relaxed text-muted">
        {approval.approvalId} · no funds have moved — bank staff review this before anything happens.
      </p>
    </WidgetCard>
  );
});
