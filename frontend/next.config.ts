import {withSerwist} from "@serwist/turbopack";
import type {NextConfig} from "next";

const nextConfig: NextConfig = {
    output: "standalone",
    // Dev-only: allow accessing the dev server (HMR/client chunks) from a LAN IP,
    // e.g. testing responsive layout on a phone. Set DEV_ALLOWED_ORIGINS=ip1,ip2.
    allowedDevOrigins: process.env.DEV_ALLOWED_ORIGINS?.split(",").filter(Boolean) ?? [],
    // Dev-only: `next dev` serves on :3000 but the app calls /api with a relative
    // baseUrl. In prod traefik routes /api → master; set DEV_API_PROXY to proxy it
    // to a running master (e.g. http://localhost:8080) during local dev.
    async rewrites() {
        const target = process.env.DEV_API_PROXY;
        return target ? [{source: "/api/:path*", destination: `${target}/api/:path*`}] : [];
    },
    // `/manifest.webmanifest` picks its splash and toolbar colours from the OS colour scheme, which
    // it can only learn through the `Sec-CH-Prefers-Color-Scheme` client hint. The hint is opt-in
    // (high entropy), so the browser sends nothing until a response asks for it — hence `Accept-CH`
    // on every route, not just the manifest. `Vary` is scoped to the manifest so it cannot clobber
    // the `Vary` Next sets on RSC/HTML responses.
    async headers() {
        return [
            {
                source: "/:path*",
                headers: [{key: "Accept-CH", value: "Sec-CH-Prefers-Color-Scheme"}],
            },
            {
                source: "/manifest.webmanifest",
                headers: [{key: "Vary", value: "Sec-CH-Prefers-Color-Scheme"}],
            },
        ];
    },
};

export default withSerwist(nextConfig);
