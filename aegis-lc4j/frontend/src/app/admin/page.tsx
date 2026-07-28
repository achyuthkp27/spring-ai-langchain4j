"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { motion } from "framer-motion";
import {
  Activity,
  ArrowLeft,
  Gauge,
  MessageSquare,
  ShieldAlert,
  Zap,
} from "lucide-react";
import { fetchOverview, fetchTimeseries, type Overview, type TimeseriesPoint } from "@/lib/adminApi";
import { StatTile } from "@/components/admin/StatTile";
import { TrafficChart } from "@/components/admin/TrafficChart";
import { AuditQueryPanel } from "@/components/admin/AuditQueryPanel";

export default function AdminPage() {
  const [overview, setOverview] = useState<Overview | null>(null);
  const [timeseries, setTimeseries] = useState<TimeseriesPoint[]>([]);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const load = () => {
      Promise.all([fetchOverview(), fetchTimeseries(60)])
        .then(([o, t]) => {
          if (cancelled) return;
          setOverview(o);
          setTimeseries(t);
          setError(false);
        })
        .catch(() => !cancelled && setError(true));
    };
    load();
    const interval = setInterval(load, 15_000);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, []);

  return (
    <div className="min-h-dvh bg-background px-4 py-6 sm:px-8">
      <div className="mx-auto max-w-5xl">
        <div className="mb-6 flex items-center gap-3">
          <Link
            href="/"
            className="flex items-center gap-1.5 text-sm text-muted hover:text-foreground transition-colors"
          >
            <ArrowLeft size={15} /> Back to chat
          </Link>
        </div>

        <motion.h1
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          className="text-xl font-semibold"
        >
          Achu FinBot — Admin
        </motion.h1>
        <p className="mt-1 text-sm text-muted">
          Traffic, guardrails, and compliance visibility across every tenant.
        </p>

        {error && (
          <div className="mt-6 rounded-xl border border-critical/30 bg-critical-soft px-4 py-3 text-sm text-critical">
            Couldn&apos;t reach the admin API — confirm the backend is running and this
            session has an admin-role token.
          </div>
        )}

        {overview && (
          <>
            <div className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
              <StatTile
                index={0}
                label="Total requests"
                value={String(overview.requests.total)}
                icon={MessageSquare}
              />
              <StatTile
                index={1}
                label="Cache hit rate"
                value={`${Math.round(overview.requests.cacheHitRate * 100)}%`}
                icon={Zap}
                tone="good"
              />
              <StatTile
                index={2}
                label="Blocked"
                value={String(overview.requests.blocked)}
                icon={ShieldAlert}
                tone="warning"
              />
              <StatTile
                index={3}
                label="LLM circuit"
                value={overview.llmCircuit}
                icon={Activity}
                tone={overview.llmCircuit === "CLOSED" ? "good" : "critical"}
              />
            </div>

            <div className="mt-4 flex items-center gap-2 text-xs text-muted">
              <Gauge size={13} />
              Model: <span className="font-medium text-foreground">{overview.chatModel}</span>
              · Uptime {overview.uptimeMinutes}m
            </div>

            <motion.section
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.1 }}
              className="mt-6 rounded-lg border border-border-soft bg-surface p-4"
            >
              <h2 className="mb-3 text-sm font-semibold">Traffic — last 60 minutes</h2>
              <TrafficChart data={timeseries} />
            </motion.section>

            <motion.section
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.15 }}
              className="mt-6 rounded-lg border border-border-soft bg-surface p-4"
            >
              <h2 className="mb-3 text-sm font-semibold">Compliance audit query</h2>
              <p className="mb-3 text-xs text-muted">
                Queries the persisted audit table (survives restarts) — filter by tenant for a
                forensic export.
              </p>
              <AuditQueryPanel />
            </motion.section>
          </>
        )}
      </div>
    </div>
  );
}
