import {SerwistProvider} from "@serwist/turbopack/react";
import type {Metadata, Viewport} from "next";
import {Barlow, Barlow_Condensed, JetBrains_Mono} from "next/font/google";
import "./globals.css";
import {AuthProvider} from "@/lib/auth-context";
import {fetchAppName, fetchBrandingConfig} from "@/lib/config";

const barlow = Barlow({
    variable: "--font-sans",
    subsets: ["latin"],
    weight: ["400", "500", "600"],
});

const barlowCondensed = Barlow_Condensed({
    variable: "--font-condensed",
    subsets: ["latin"],
    weight: ["400", "500", "600", "700", "800"],
});

const jetbrainsMono = JetBrains_Mono({
    variable: "--font-mono",
    subsets: ["latin"],
    weight: ["400", "500", "600"],
});

export async function generateMetadata(): Promise<Metadata> {
    const appName = await fetchAppName()
    const branding = await fetchBrandingConfig()
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
        <body className="min-h-full flex flex-col bg-bg text-text-primary font-sans">
        <SerwistProvider swUrl="/serwist/sw.js">
            <AuthProvider>{children}</AuthProvider>
        </SerwistProvider>
        </body>
        </html>
    );
}
