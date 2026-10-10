import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Static export served by nginx: no SSR, API routes, or middleware.
  output: "export",
  // Emit /path/index.html so nginx's `try_files $uri $uri/` finds every page.
  trailingSlash: true,
};

export default nextConfig;
