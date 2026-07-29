
export function displayName(tenantId: string | null | undefined): string {
  if (!tenantId) return "your bank";
  return tenantId
    .split("-")
    .filter(Boolean)
    .map((p) => p.charAt(0).toUpperCase() + p.slice(1))
    .join(" ");
}
