"use client";

import { memo } from "react";
import { FileText } from "lucide-react";
import type { CitationData } from "@/lib/sse";

export const CitationChips = memo(function CitationChips({
  citations,
}: {
  citations: CitationData[];
}) {
  if (citations.length === 0) return null;

  return (
    <div className="flex flex-wrap gap-1.5">
      {citations.map((c) => (
        <span
          key={c.source}
          title={c.snippet}
          className="flex max-w-[220px] items-center gap-1.5 rounded-full border border-hairline bg-surface-2/60 px-2.5 py-1 text-micro text-muted"
        >
          <FileText size={11} className="shrink-0 text-accent" aria-hidden />
          <span className="truncate">{c.source}</span>
        </span>
      ))}
    </div>
  );
});
