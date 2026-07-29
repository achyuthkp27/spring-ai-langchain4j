import type { Metadata } from "next";

/**
 * A thin Server Component wrapper: `page.tsx` is a client component and so
 * cannot export metadata itself, which left the ops dashboard inheriting the
 * customer-facing tab title.
 */
export const metadata: Metadata = {
  title: "Admin",
  description: "Traffic, guardrail and compliance visibility across tenants.",
};

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return children;
}
