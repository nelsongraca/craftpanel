import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  async rewrites() {
    if (process.env.NODE_ENV !== "production") {
      return [
        {
          source: "/api/v1/:path*",
          destination: "http://localhost:8080/api/v1/:path*",
        },
      ];
    }
    return [];
  },
};

export default nextConfig;
