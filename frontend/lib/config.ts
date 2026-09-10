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
        const res = await fetch(`${apiBase()}/api/config`)
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
        const res = await fetch(`${apiBase()}/api/config`)
        if (!res.ok) {
            return {appName: APP_NAME_DEFAULT, hasLogo: false, logoHash: "", logoUrl: "/api/branding/logo"}
        }
        const data = await res.json() as BrandingConfig
        data.appName = data.appName?.trim() || APP_NAME_DEFAULT
        data.logoUrl = data.logoUrl || "/api/branding/logo"
        return data
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