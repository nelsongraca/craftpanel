import {SerwistProvider} from "@serwist/turbopack/react";
import type {Metadata, Viewport} from "next";
import localFont from "next/font/local";
import "./globals.css";
import {AuthProvider} from "@/lib/auth-context";
import {fetchBrandingConfig} from "@/lib/config";

// Self-hosted (latin subset) rather than next/font/google: the Google loader fetches font files
// over the network at dev/build time, which flakes on CI runners with a transient outage and fails
// the whole dev server ("Can't resolve '@vercel/turbopack-next/internal/font/google/font'"). Local
// fonts keep the build deterministic and offline.
const barlow = localFont({
    src: [
        {path: "./fonts/Barlow-400.woff2", weight: "400", style: "normal"},
        {path: "./fonts/Barlow-500.woff2", weight: "500", style: "normal"},
        {path: "./fonts/Barlow-600.woff2", weight: "600", style: "normal"},
    ],
    variable: "--font-sans",
    display: "swap",
    fallback: ["system-ui", "sans-serif"],
});

const barlowCondensed = localFont({
    src: [
        {path: "./fonts/BarlowCondensed-400.woff2", weight: "400", style: "normal"},
        {path: "./fonts/BarlowCondensed-500.woff2", weight: "500", style: "normal"},
        {path: "./fonts/BarlowCondensed-600.woff2", weight: "600", style: "normal"},
        {path: "./fonts/BarlowCondensed-700.woff2", weight: "700", style: "normal"},
        {path: "./fonts/BarlowCondensed-800.woff2", weight: "800", style: "normal"},
    ],
    variable: "--font-condensed",
    display: "swap",
    fallback: ["system-ui", "sans-serif"],
});

const jetbrainsMono = localFont({
    src: [{path: "./fonts/JetBrainsMono-Variable.woff2", weight: "100 800", style: "normal"}],
    variable: "--font-mono",
    display: "swap",
    fallback: ["ui-monospace", "monospace"],
});

export async function generateMetadata(): Promise<Metadata> {
    const branding = await fetchBrandingConfig()
    const appName = branding.appName
    const icons: Metadata["icons"] = branding.hasLogo
        ? {
            icon: [{url: "/api/branding/logo", type: "image/svg+xml"}, {url: "/api/branding/icon-192.png", sizes: "192x192", type: "image/png"}],
            apple: "/api/branding/icon-192.png",
        }
        : {
            icon: [{url: "/logo.svg", type: "image/svg+xml"}, {url: "/icon-192.png", sizes: "192x192", type: "image/png"}],
            apple: "/apple-touch-icon.png",
        }
    return {
        title: appName,
        description: "Minecraft server management dashboard",
        manifest: "/manifest",
        icons,
        appleWebApp: {
            capable: true,
            title: appName,
            statusBarStyle: "black-translucent",
        },
    }
}

export const viewport: Viewport = {
    themeColor: "#d97706",
    width: "device-width",
    initialScale: 1,
    viewportFit: "cover",
};

export default function RootLayout({
                                       children,
                                   }: Readonly<{
    children: React.ReactNode;
}>) {
    return (
        <html
            lang="en"
            className={`${barlow.variable} ${barlowCondensed.variable} ${jetbrainsMono.variable} dark h-full antialiased`}
        >
        <head>
            {/* Runtime (not build-time) API origin — lets a prebuilt image be deployed with
                frontend and master on different subdomains via the PUBLIC_API_URL env var. */}
            <script
                dangerouslySetInnerHTML={{__html: `window.__API_URL__=${JSON.stringify(process.env.PUBLIC_API_URL ?? "")};`}}
            />
        </head>
        <body className="h-full flex flex-col bg-bg text-text-primary font-sans">
        <SerwistProvider swUrl="/serwist/sw.js">
            <AuthProvider>{children}</AuthProvider>
        </SerwistProvider>
        </body>
        </html>
    );
}
