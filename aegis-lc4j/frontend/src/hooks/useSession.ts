"use client";

import { useCallback, useEffect, useState } from "react";
import { getProfile, switchIdentity, type Profile } from "@/lib/api";
import { displayName } from "@/lib/tenantNames";

export interface DemoIdentity {
  tenantId: string;
  userId: string;
  label: string;
}

export const DEMO_IDENTITIES: DemoIdentity[] = [
  { tenantId: "achu-bank", userId: "demo-user", label: "Achu Bank · demo-user" },
  { tenantId: "globex-bank", userId: "globex-user", label: "Globex Bank · globex-user" },
];

export function useSession() {
  const [profile, setProfile] = useState<Profile | null>(null);

  useEffect(() => {
    getProfile().then(setProfile).catch(() => setProfile(null));
  }, []);

  const switchTo = useCallback((tenantId: string, userId: string) => {
    void switchIdentity(tenantId, userId);
  }, []);

  return {
    profile,
    bankName: displayName(profile?.tenantId),
    switchTo,
  };
}
