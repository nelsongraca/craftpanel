const APP_NAME_DEFAULT = "CraftPanel";

export interface BrandingConfig {
    appName: string;
    hasLogo: boolean;
    logoHash: string;
    logoUrl: string;
}

// Client-side cache only. Server renders (generateMetadata/manifest) must not cache across
// requests, or a logo/app-name change would not surface without a restart.
let cachedBranding: BrandingConfig | null = null;

// Client-side subscribers (e.g. the shell header) so a branding change surfaces without a reload.
const brandingListeners = new Set<() => void>();

function emitBrandingChange() {
    brandingListeners.forEach((listener) => listener());
}

/** Subscribes to client-side branding changes. Returns an unsubscribe function. */
export function subscribeBranding(listener: () => void): () => void {
    brandingListeners.add(listener);
    return () => {
        brandingListeners.delete(listener);
    };
}

/** Current cached branding, for use as a `useSyncExternalStore` snapshot. */
export function getBrandingSnapshot(): BrandingConfig | null {
    return cachedBranding;
}

function apiBase(): string {
    if (typeof window === "undefined") return "";
    return (window as unknown as {__API_URL__?: string}).__API_URL__ ?? "";
}

async function loadBrandingConfig(): Promise<BrandingConfig> {
    try {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 2000);
        const res = await fetch(`${apiBase()}/api/config`, {signal: controller.signal});
        clearTimeout(timeout);
        if (!res.ok) {
            return {appName: APP_NAME_DEFAULT, hasLogo: false, logoHash: "", logoUrl: "/api/branding/logo"};
        }
        const raw = (await res.json()) as Record<string, unknown>;
        return {
            appName: (raw.app_name as string)?.trim() || APP_NAME_DEFAULT,
            hasLogo: raw.has_logo === true,
            logoHash: (raw.logo_hash as string) ?? "",
            logoUrl: (raw.logo_url as string) || "/api/branding/logo",
        };
    } catch {
        return {appName: APP_NAME_DEFAULT, hasLogo: false, logoHash: "", logoUrl: "/api/branding/logo"};
    }
}

export async function fetchBrandingConfig(): Promise<BrandingConfig> {
    if (cachedBranding) return cachedBranding;
    const cfg = await loadBrandingConfig();
    if (typeof window !== "undefined") {
        cachedBranding = cfg;
        emitBrandingChange();
    }
    return cfg;
}

/**
 * Refetches branding and notifies subscribers. Does not clear the cache first, so subscribers
 * never observe a null snapshot and the UI does not flash the default name.
 */
export async function refreshBrandingConfig(): Promise<BrandingConfig> {
    if (typeof window === "undefined") return fetchBrandingConfig();
    const cfg = await loadBrandingConfig();
    cachedBranding = cfg;
    emitBrandingChange();
    return cfg;
}

/** Drops the client-side branding cache (call after settings that change the logo/app name). */
export function resetBrandingCache() {
    cachedBranding = null;
}

export function logoUrl(cfg?: BrandingConfig | null): string {
    const hash = cfg?.logoHash;
    if (hash) return `/api/branding/logo?h=${hash}`;
    return "/api/branding/logo";
}
