const APP_NAME_DEFAULT = "CraftPanel"

export interface BrandingConfig {
    appName: string;
    hasLogo: boolean;
    logoHash: string;
    logoUrl: string;
}

let cachedAppName: string | null = null
let cachedBranding: BrandingConfig | null = null

function apiBase(): string {
    if (typeof window === "undefined") return ""
    return (window as unknown as { __API_URL__?: string }).__API_URL__ ?? ""
}

export async function fetchAppName(): Promise<string> {
    try {
        const controller = new AbortController()
        const timeout = setTimeout(() => controller.abort(), 2000)
        const res = await fetch(`${apiBase()}/api/config`, {signal: controller.signal})
        clearTimeout(timeout)
        if (!res.ok) return APP_NAME_DEFAULT
        const data = await res.json() as { app_name?: string }
        return data.app_name?.trim() || APP_NAME_DEFAULT
    } catch {
        return APP_NAME_DEFAULT
    }
}

export async function getAppName(): Promise<string> {
    if (cachedAppName) return cachedAppName
    cachedAppName = await fetchAppName()
    return cachedAppName
}

export function resetAppNameCache() {
    cachedAppName = null
}

export async function fetchBrandingConfig(): Promise<BrandingConfig> {
    try {
        const controller = new AbortController()
        const timeout = setTimeout(() => controller.abort(), 2000)
        const res = await fetch(`${apiBase()}/api/config`, {signal: controller.signal})
        clearTimeout(timeout)
        if (!res.ok) {
            return {appName: APP_NAME_DEFAULT, hasLogo: false, logoHash: "", logoUrl: "/api/branding/logo"}
        }
        const raw = await res.json() as Record<string, unknown>
        return {
            appName: (raw.app_name as string)?.trim() || APP_NAME_DEFAULT,
            hasLogo: raw.has_logo === true,
            logoHash: (raw.logo_hash as string) ?? "",
            logoUrl: (raw.logo_url as string) || "/api/branding/logo",
        }
    } catch {
        return {appName: APP_NAME_DEFAULT, hasLogo: false, logoHash: "", logoUrl: "/api/branding/logo"}
    }
}

export function getBrandingConfig(): BrandingConfig | null {
    return cachedBranding
}

export function setBrandingConfig(cfg: BrandingConfig) {
    cachedBranding = cfg
}

export function resetBrandingCache() {
    cachedBranding = null
    cachedAppName = null
}

export function logoUrl(cfg?: BrandingConfig | null): string {
    const hash = cfg?.logoHash
    if (hash) return `/api/branding/logo?h=${hash}`
    return `/api/branding/logo`
}