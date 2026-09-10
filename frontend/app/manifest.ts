import type {MetadataRoute} from "next"
import {fetchAppName, fetchBrandingConfig} from "@/lib/config"

export default async function manifest(): Promise<MetadataRoute.Manifest> {
    const appName = await fetchAppName()
    const branding = await fetchBrandingConfig()

    const icons: MetadataRoute.Manifest["icons"] = branding.hasLogo
        ? [
            {src: "/api/branding/icon-192.png", sizes: "192x192", type: "image/png"},
            {src: "/api/branding/icon-512.png", sizes: "512x512", type: "image/png"},
        ]
        : [
            {src: "/icon-192.png", sizes: "192x192", type: "image/png"},
            {src: "/icon-512.png", sizes: "512x512", type: "image/png"},
            {src: "/icon-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable"},
        ]

    return {
        name: appName,
        short_name: appName,
        description: "Minecraft server management dashboard",
        start_url: "/",
        scope: "/",
        display: "standalone",
        orientation: "portrait",
        background_color: "#0e0d0c",
        theme_color: "#d97706",
        categories: ["utilities", "games"],
        icons,
    }
}
