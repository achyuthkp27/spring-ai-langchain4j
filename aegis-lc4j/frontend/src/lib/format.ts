export const money = (n: number) =>
  n.toLocaleString(undefined, { style: "currency", currency: "USD" });

export const shortDate = (iso: string) =>
  new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });
