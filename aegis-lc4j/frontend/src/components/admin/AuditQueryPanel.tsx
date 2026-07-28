"use client";

import { useState } from "react";
import { Search } from "lucide-react";
import { fetchAuditQuery, type AuditEvent } from "@/lib/adminApi";
import { EventsTable } from "./EventsTable";

/** Compliance/forensics view over the PERSISTED audit table (survives restarts) — filterable
    by tenant, unlike the live in-memory /events feed above it on the page. */
export function AuditQueryPanel() {
  const [tenant, setTenant] = useState("");
  const [rows, setRows] = useState<AuditEvent[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);

  const run = async () => {
    setLoading(true);
    setError(false);
    try {
      const data = await fetchAuditQuery({ tenant: tenant || undefined, limit: 200 });
      setRows(data);
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <div className="mb-3 flex items-center gap-2">
        <input
          value={tenant}
          onChange={(e) => setTenant(e.target.value)}
          placeholder="Filter by tenant (e.g. achu-bank) — blank for all"
          className="flex-1 rounded-lg border border-border-soft bg-transparent px-3 py-1.5 text-sm outline-none focus:ring-2 focus:ring-accent/40"
        />
        <button
          onClick={run}
          disabled={loading}
          className="flex items-center gap-1.5 rounded-lg bg-accent px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
        >
          <Search size={13} /> {loading ? "Querying…" : "Query (last 7 days)"}
        </button>
      </div>
      {error && <p className="text-sm text-critical">Query failed — is the backend reachable?</p>}
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
