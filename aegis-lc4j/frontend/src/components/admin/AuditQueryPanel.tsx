"use client";

import { useState } from "react";
import { AlertTriangle, Search } from "lucide-react";
import { fetchAuditQuery, type AuditEvent } from "@/lib/adminApi";
import { EventsTable } from "./EventsTable";

export function AuditQueryPanel() {
  const [tenant, setTenant] = useState("");
  const [rows, setRows] = useState<AuditEvent[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);

  const run = async () => {
    setLoading(true);
    setError(false);
    try {
      setRows(await fetchAuditQuery({ tenant: tenant || undefined, limit: 200 }));
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <div className="mb-3 flex flex-col gap-2 sm:flex-row sm:items-center">
        <label htmlFor="audit-tenant" className="sr-only">
          Filter by tenant
        </label>
        <input
          id="audit-tenant"
          value={tenant}
          onChange={(e) => setTenant(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && run()}
          placeholder="Filter by tenant (e.g. achu-bank) — blank for all"
          className="flex-1 rounded-xl border border-hairline bg-surface-2/60 px-3 py-2 text-label outline-none transition-colors placeholder:text-muted focus:border-accent/45"
        />
        <button
          onClick={run}
          disabled={loading}
          className="flex shrink-0 items-center justify-center gap-1.5 rounded-xl bg-accent-strong px-3.5 py-2 text-label font-medium text-on-accent transition-opacity disabled:opacity-50"
        >
          <Search size={14} /> {loading ? "Querying…" : "Query last 7 days"}
        </button>
      </div>
      {error && (
        <p className="flex items-center gap-1.5 text-label text-critical-ink">
          <AlertTriangle size={14} className="shrink-0 text-critical" />
          Query failed — is the backend reachable?
        </p>
      )}
      {rows && (
        <EventsTable
          rows={rows.map((r) => ({
            at: r.createdAt,
            tenant: r.tenant,
            source: r.source,
            elapsedMs: r.elapsedMs,
            question: r.question,
          }))}
          emptyLabel="No persisted audit events match this filter."
        />
      )}
    </div>
  );
}
