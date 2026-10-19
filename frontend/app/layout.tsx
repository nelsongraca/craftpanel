import {SerwistProvider} from "@serwist/turbopack/react";
import type {Metadata, Viewport} from "next";
import localFont from "next/font/local";
import "./globals.css";
import {AuthProvider} from "@/lib/auth-context";
import {fetchBrandingConfig} from "@/lib/config";
import ThemeSync from "@/app/components/ThemeSync";
import {THEME_CHROME_COLORS} from "@/lib/theme";

// Runs during HTML parsing, before first paint, so the stored theme is applied without a flash.
// Mirrors the resolution logic in lib/theme.ts (which is the source of truth) — an invalid or
// absent value falls back to the OS preference, matching DEFAULT_THEME_MODE.
const THEME_INIT_SCRIPT = `(function(){try{var m=localStorage.getItem("craftpanel:theme");var t=(m==="dark"||m==="light")?m:(window.matchMedia&&window.matchMedia("(prefers-color-scheme: dark)").matches?"dark":"light");document.documentElement.setAttribute("data-theme",t)}catch(e){}})()`;

// Self-hosted (latin subset) rather than next/font/google: the Google loader fetches font files
// over the network at dev/build time, which flakes on CI runners with a transient outage and fails
// the whole dev server ("Can't resolve '@vercel/turbopack-next/internal/font/google/font'"). Local
// fonts keep the build deterministic and offline.
const barlow = localFont({
    src: [
        {path: "./fonts/Barlow-400.woff2", weight: "400", style: "normal"},
        {path: "./fonts/Barlow-500.woff2", weight: "500", style: "normal"},
        {path: "./fonts/Barlow-600.woff2", weight: "600", style: "normal"},
        {path: "./fonts/Barlow-700.woff2", weight: "700", style: "normal"},
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
    const branding = await fetchBrandingConfig();
    const appName = branding.appName;
    const icons: Metadata["icons"] = branding.hasLogo
        ? {
              icon: [
                  {url: "/api/branding/logo", type: "image/svg+xml"},
                  {url: "/api/branding/icon-192.png", sizes: "192x192", type: "image/png"},
              ],
              apple: "/api/branding/icon-192.png",
          }
        : {
              icon: [
                  {url: "/logo.svg", type: "image/svg+xml"},
                  {url: "/icon-192.png", sizes: "192x192", type: "image/png"},
              ],
              apple: "/apple-touch-icon.png",
          };
    return {
        title: appName,
        description: "Minecraft server management dashboard",
        manifest: "/manifest",
        icons,
        appleWebApp: {
            capable: true,
            title: appName,
            // The one piece of theme metadata that cannot follow the theme: iOS reads this at
            // launch, there is no client-side API to change it afterwards, and Safari does not
            // implement the client hint that would let the server vary it (unlike the manifest
            // colours). `black-translucent` gives white status-bar text, which is right for the
            // default dark palette — where the solid header sits behind the bar, thanks to
            // `viewportFit: cover` and `--header-h`'s safe-area inset. Switching to "default"
            // (dark text) would simply break dark mode instead of light.
            statusBarStyle: "black-translucent",
        },
    };
}

export const viewport: Viewport = {
    // Two `prefers-color-scheme`-scoped tags, so the browser chrome matches the OS before hydration
    // and keeps working with JS disabled. `lib/theme.ts` replaces them once the stored preference
    // is known, so an explicit light/dark pick outranks the OS. The colours mirror `--surface`.
    themeColor: [
        {media: "(prefers-color-scheme: light)", color: THEME_CHROME_COLORS.light},
        {media: "(prefers-color-scheme: dark)", color: THEME_CHROME_COLORS.dark},
    ],
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
        // data-theme is the dark default the server always ships; THEME_INIT_SCRIPT may rewrite it
        // before paint, and ThemeSync re-applies it after hydration. suppressHydrationWarning is
        // required because that attribute intentionally disagrees with the RSC payload.
        <html
            lang="en"
            data-theme="dark"
            suppressHydrationWarning
            className={`${barlow.variable} ${barlowCondensed.variable} ${jetbrainsMono.variable} h-full antialiased`}
        >
            <head>
                {/* Runtime (not build-time) API origin — lets a prebuilt image be deployed with
                frontend and master on different subdomains via the PUBLIC_API_URL env var. */}
                <script
                    dangerouslySetInnerHTML={{
                        __html: `window.__API_URL__=${JSON.stringify(process.env.PUBLIC_API_URL ?? "")};`,
                    }}
                />
                {/* Must stay ahead of the body: applies the stored theme before the first paint. */}
                <script dangerouslySetInnerHTML={{__html: THEME_INIT_SCRIPT}} />
            </head>
            <body className="flex h-full flex-col bg-bg font-sans text-text-primary">
                <SerwistProvider swUrl="/serwist/sw.js">
                    <ThemeSync />
                    <AuthProvider>{children}</AuthProvider>
                </SerwistProvider>
            </body>
        </html>
    );
}
