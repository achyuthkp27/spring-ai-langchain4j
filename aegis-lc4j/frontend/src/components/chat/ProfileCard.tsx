"use client";

import { motion } from "framer-motion";
import clsx from "clsx";
import { Bell, BellOff, Mail, Phone, Plane } from "lucide-react";
import type { ProfileData } from "@/lib/sse";

/** Renders the REAL customer-profile record pushed by updateContactInfo/setAlertPreferences/
    setTravelNotice (see BankingTools.PROFILE_KEY), not the model's paraphrase of the change. */
export function ProfileCard({ profile }: { profile: ProfileData }) {
  const travelActive = !!profile.travelNoticeUntil;

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ type: "spring", stiffness: 340, damping: 30 }}
      className="mt-1 w-full max-w-sm rounded-xl border border-border-soft bg-surface p-3.5"
    >
      <p className="text-[11px] font-medium uppercase tracking-wide text-muted">Profile updated</p>

      <div className="mt-2 space-y-1.5 text-[13px]">
        <div className="flex items-center gap-2">
          <Mail size={13} className="shrink-0 text-muted" />
          <span className="truncate">{profile.email}</span>
        </div>
        <div className="flex items-center gap-2">
          <Phone size={13} className="shrink-0 text-muted" />
          <span>{profile.phone}</span>
        </div>
      </div>

      <div className="mt-2.5 flex flex-wrap gap-1.5">
        <span
          className={clsx(
            "inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide",
            profile.lowBalanceAlerts ? "bg-good-soft text-good" : "bg-border-soft text-muted",
          )}
        >
          {profile.lowBalanceAlerts ? <Bell size={10} /> : <BellOff size={10} />}
          Low balance
        </span>
        <span
          className={clsx(
            "inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide",
            profile.largeTransactionAlerts ? "bg-good-soft text-good" : "bg-border-soft text-muted",
          )}
        >
          {profile.largeTransactionAlerts ? <Bell size={10} /> : <BellOff size={10} />}
          Large txn
        </span>
        {travelActive && (
          <span className="inline-flex items-center gap-1 rounded-full bg-accent-soft px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-accent">
            <Plane size={10} />
            {profile.travelDestination} until {profile.travelNoticeUntil}
          </span>
        )}
      </div>
    </motion.div>
  );
}
