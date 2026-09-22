import type { NextConfig } from "next";

// The source repo rewrote /api/:path* to its own Express backend on :3001. That backend does not
// exist in this project - every API call now goes straight to the real Spring Boot backend via the
// shared axios instance in src/lib/api.ts (baseURL = NEXT_PUBLIC_API_URL), so no rewrite is needed.
const nextConfig: NextConfig = {};

export default nextConfig;
