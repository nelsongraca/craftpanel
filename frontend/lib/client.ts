import { client } from "./generated/client.gen"

let _accessToken: string | null = null
let _refreshPromise: Promise<string | null> | null = null

export function setAccessToken(token: string | null) { _accessToken = token }
export function getAccessToken() { return _accessToken }

async function refreshToken(): Promise<string | null> {
  const res = await fetch("/api/v1/auth/refresh", { method: "POST", credentials: "include" })
  if (!res.ok) { _accessToken = null; return null }
  const data = await res.json() as { access_token: string }
  _accessToken = data.access_token
  return _accessToken
}

client.setConfig({ baseUrl: "", credentials: "include" })

client.interceptors.request.use((request) => {
  if (_accessToken) request.headers.set("Authorization", `Bearer ${_accessToken}`)
  return request
})

client.interceptors.response.use(async (response, request) => {
  if (response.status !== 401) return response
  if (!_refreshPromise) _refreshPromise = refreshToken().finally(() => { _refreshPromise = null })
  const newToken = await _refreshPromise
  if (!newToken) return response
  const retried = request.clone()
  retried.headers.set("Authorization", `Bearer ${newToken}`)
  return fetch(retried)
})

export { client }
