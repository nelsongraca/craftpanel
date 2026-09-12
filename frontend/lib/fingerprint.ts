let _fingerprint: string | null = null
let _promise: Promise<string> | null = null

async function compute(): Promise<string> {
  const parts = [
    navigator.userAgent,
    screen.width,
    screen.height,
    screen.colorDepth,
  ]
  const input = parts.join("|")
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(input))
  return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, "0")).join("")
}

export async function initFingerprint(): Promise<void> {
  if (!_promise) _promise = compute()
  _fingerprint = await _promise
}

export function getCachedFingerprint(): string | null {
  return _fingerprint
}