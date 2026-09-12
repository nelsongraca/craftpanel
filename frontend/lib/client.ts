import {client} from "./generated/client.gen"
import {getCachedFingerprint} from "./fingerprint"

let _accessToken: string | null = null
let _refreshPromise: Promise<string | null> | null = null

export function setAccessToken(token: string | null) {
    _accessToken = token
}

export function getAccessToken() {
    return _accessToken
}

function apiBase(): string {
    if (typeof window === "undefined") return ""
    return (window as unknown as { __API_URL__?: string }).__API_URL__ ?? ""
}

async function refreshToken(): Promise<string | null> {
    const headers: Record<string, string> = {}
    const fp = getCachedFingerprint()
    if (fp) headers["X-Device-Fingerprint"] = fp
    const res = await fetch(`${apiBase()}/api/auth/refresh`, {method: "POST", credentials: "include", headers})
    if (!res.ok) {
        _accessToken = null;
        return null
    }
    const data = await res.json() as { access_token: string }
    _accessToken = data.access_token
    return _accessToken
}

client.setConfig({baseUrl: apiBase(), credentials: "include"})

client.interceptors.request.use((request) => {
    if (_accessToken) request.headers.set("Authorization", `Bearer ${_accessToken}`)
    const fp = getCachedFingerprint()
    if (fp) request.headers.set("X-Device-Fingerprint", fp)
    return request
})

client.interceptors.response.use(async (response, request) => {
    if (response.status !== 401) return response
    if (!_refreshPromise) _refreshPromise = refreshToken().finally(() => {
        _refreshPromise = null
    })
    const newToken = await _refreshPromise
    if (!newToken) return response
    const retried = request.clone()
    retried.headers.set("Authorization", `Bearer ${newToken}`)
    const fp = getCachedFingerprint()
    if (fp) retried.headers.set("X-Device-Fingerprint", fp)
    return fetch(retried)
})

export {client}
