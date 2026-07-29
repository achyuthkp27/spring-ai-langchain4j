"use client";

import { memo } from "react";
import clsx from "clsx";
import { Bell, BellOff, Mail, Phone, Plane, UserRound } from "lucide-react";
import type { ProfileData } from "@/lib/sse";
import { WidgetCard } from "./WidgetCard";

function Toggle({ on, label }: { on: boolean; label: string }) {
  const Icon = on ? Bell : BellOff;
  return (
    <span
      className={clsx(
        "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-micro font-medium",
        on ? "bg-good-soft text-good-ink" : "bg-surface text-muted",
      )}
    >
      <Icon size={11} aria-hidden />
      {label}
      <span className="sr-only">{on ? " enabled" : " disabled"}</span>
      <span aria-hidden className="font-semibold">
        {on ? "On" : "Off"}
      </span>
    </span>
  );
}

export const ProfileCard = memo(function ProfileCard({ profile }: { profile: ProfileData }) {
  return (
    <WidgetCard icon={UserRound} title="Profile updated">
      <div className="space-y-1.5 px-3.5 pt-2 text-label">
        <div className="flex items-center gap-2">
          <Mail size={13} className="shrink-0 text-muted" aria-hidden />
          <span className="truncate">{profile.email}</span>
        </div>
        <div className="flex items-center gap-2">
          <Phone size={13} className="shrink-0 text-muted" aria-hidden />
          <span>{profile.phone}</span>
        </div>
      </div>

      <div className="flex flex-wrap gap-1.5 px-3.5 pb-3 pt-2.5">
        <Toggle on={profile.lowBalanceAlerts} label="Low balance" />
        <Toggle on={profile.largeTransactionAlerts} label="Large transaction" />
        {profile.travelNoticeUntil && (
          <span className="inline-flex items-center gap-1.5 rounded-full bg-accent-soft px-2.5 py-1 text-micro font-medium text-accent-ink">
            <Plane size={11} aria-hidden />
            {profile.travelDestination} until {profile.travelNoticeUntil}
          </span>
        )}
      </div>
    </WidgetCard>
  );
});
