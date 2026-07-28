/**
 * Client-side mirror of the backend's TenantNames.displayName (aegis-merged,
 * assistant/TenantNames.java) — same hyphen -> title-case rule, kept in sync manually since
 * there's no shared package between the Java backend and this Next.js frontend.
 */
export function displayName(tenantId: string | null | undefined): string {
  if (!tenantId) return "your bank";
  return tenantId
    .split("-")
    .filter(Boolean)
    .map((p) => p.charAt(0).toUpperCase() + p.slice(1))
    .join(" ");
}
