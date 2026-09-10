const APP_NAME_DEFAULT = "CraftPanel"

let cachedAppName: string | null = null

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
