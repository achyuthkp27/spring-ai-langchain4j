import type { NextConfig } from "next";

// API proxying is handled by src/app/api/[...path]/route.ts, which probes for
// whichever Aegis backend is running (8082 merged → 8081 lc4j → 8080 aegis-ai).
const nextConfig: NextConfig = {};

export default nextConfig;
