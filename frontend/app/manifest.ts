import type {MetadataRoute} from "next";
import {headers} from "next/headers";
import {fetchBrandingConfig} from "@/lib/config";
import {resolveManifestTheme, THEME_CHROME_COLORS, THEME_SPLASH_COLORS} from "@/lib/theme";

export default async function manifest(): Promise<MetadataRoute.Manifest> {
    const [branding, requestHeaders] = await Promise.all([fetchBrandingConfig(), headers()]);
    const appName = branding.appName;

    // OS-derived, because the splash and the standalone toolbar are painted before any page JS runs
    // and so cannot see the stored preference. Relies on the `Sec-CH-Prefers-Color-Scheme` client
    // hint opted into in next.config.ts — Chromium-only, and absent on the first request of a
    // session, which is why `resolveManifestTheme` falls back to the default dark palette.
    const theme = resolveManifestTheme(requestHeaders.get("sec-ch-prefers-color-scheme"));

    const icons: MetadataRoute.Manifest["icons"] = branding.hasLogo
        ? [
              {src: "/api/branding/icon-192.png", sizes: "192x192", type: "image/png"},
              {src: "/api/branding/icon-512.png", sizes: "512x512", type: "image/png"},
          ]
        : [
              {src: "/icon-192.png", sizes: "192x192", type: "image/png"},
              {src: "/icon-512.png", sizes: "512x512", type: "image/png"},
              {src: "/icon-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable"},
          ];

    return {
        name: appName,
        short_name: appName,
        description: "Minecraft server management dashboard",
        start_url: "/",
        scope: "/",
        display: "standalone",
        orientation: "portrait",
        // Follows the OS colour scheme (see `theme` above) — the splash cannot read the stored
        // preference, so a light-preference install on a dark OS still gets a dark splash.
        background_color: THEME_SPLASH_COLORS[theme],
        theme_color: THEME_CHROME_COLORS[theme],
        categories: ["utilities", "games"],
        icons,
    };
}
