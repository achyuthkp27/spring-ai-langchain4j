"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { motion, useReducedMotion } from "framer-motion";
import { Activity, AlertTriangle, ArrowLeft, Gauge, MessageSquare, ShieldAlert, Zap } from "lucide-react";
import { fetchOverview, fetchTimeseries, type Overview, type TimeseriesPoint } from "@/lib/adminApi";
import { BrandMark } from "@/components/BrandMark";
import { StatTile } from "@/components/admin/StatTile";
import { TrafficChart } from "@/components/admin/TrafficChart";
import { AuditQueryPanel } from "@/components/admin/AuditQueryPanel";

export default function AdminPage() {
  const [overview, setOverview] = useState<Overview | null>(null);
  const [timeseries, setTimeseries] = useState<TimeseriesPoint[]>([]);
  const [error, setError] = useState(false);
  const reduceMotion = useReducedMotion();

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

  const section = (delay: number, title: string, subtitle: string, body: React.ReactNode) => (
    <motion.section
      initial={reduceMotion ? false : { opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: reduceMotion ? 0 : delay, duration: 0.35 }}
      className="panel lit mt-4 p-4 sm:p-5"
    >
      <h2 className="text-label font-semibold">{title}</h2>
      <p className="mt-0.5 text-micro text-muted">{subtitle}</p>
      <div className="mt-4">{body}</div>
    </motion.section>
  );

  return (
    <div className="relative min-h-dvh">
      <div className="canvas-bloom" aria-hidden />
      <div className="canvas-grain" aria-hidden />

      <div className="relative z-10 mx-auto max-w-5xl px-4 py-6 sm:px-8">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 rounded-lg py-1 text-label text-muted transition-colors hover:text-foreground"
        >
          <ArrowLeft size={15} /> Back to chat
        </Link>

        <header className="mt-5 flex items-start gap-3">
          <span className="mt-0.5 grid h-10 w-10 shrink-0 place-items-center rounded-xl border border-hairline bg-surface-2">
            <BrandMark size={19} glow gradientId="admin-mark" />
          </span>
          <div className="min-w-0">
            <h1 className="text-xl font-semibold tracking-tight">Operations</h1>
            <p className="mt-0.5 text-label text-muted">
              Traffic, guardrails and compliance visibility across every tenant.
            </p>
          </div>
        </header>

        {error && (
          <div className="mt-5 flex items-start gap-2.5 rounded-xl border border-critical/25 bg-critical-soft px-4 py-3 text-label text-critical-ink">
            <AlertTriangle size={16} className="mt-0.5 shrink-0 text-critical" />
            <span>
              Couldn&apos;t reach the admin API — confirm the backend is running and this session
              has an admin-role token.
            </span>
          </div>
        )}

        {overview && (
          <>
            <div className="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-4">
              <StatTile
                index={0}
                label="Requests"
                value={String(overview.requests.total)}
                icon={MessageSquare}
              />
              <StatTile
                index={1}
                label="Cache hits"
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

            <div className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-micro text-muted">
              <Gauge size={13} className="shrink-0" aria-hidden />
              Model <span className="font-medium text-foreground">{overview.chatModel}</span>
              <span aria-hidden>·</span> Uptime {overview.uptimeMinutes}m
              <span aria-hidden>·</span> Refreshing every 15s
            </div>

            {section(
              0.1,
              "Traffic",
              "Model calls, cache hits, blocks and errors over the last 60 minutes.",
              <TrafficChart data={timeseries} />,
            )}

            {section(
              0.15,
              "Compliance audit query",
              "Queries the persisted audit table, which survives restarts — filter by tenant for a forensic export.",
              <AuditQueryPanel />,
            )}
          </>
        )}
      </div>
    </div>
  );
}
