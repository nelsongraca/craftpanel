const BASE = '/api/v1';

let _accessToken: string | null = null;
let _refreshPromise: Promise<string | null> | null = null;

export function setAccessToken(token: string | null) {
  _accessToken = token;
}

export function getAccessToken(): string | null {
  return _accessToken;
}

async function doRefresh(): Promise<string | null> {
  const res = await fetch(`${BASE}/auth/refresh`, {
    method: 'POST',
    credentials: 'include',
  });
  if (!res.ok) return null;
  const data = await res.json();
  _accessToken = data.access_token;
  return data.access_token;
}

function refreshAccessToken(): Promise<string | null> {
  if (!_refreshPromise) {
    _refreshPromise = doRefresh().finally(() => {
      _refreshPromise = null;
    });
  }
  return _refreshPromise;
}

async function apiFetch(path: string, options?: RequestInit): Promise<Response> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options?.headers as Record<string, string>),
  };
  if (_accessToken) headers['Authorization'] = `Bearer ${_accessToken}`;

  const res = await fetch(`${BASE}${path}`, { ...options, headers, credentials: 'include' });

  if (res.status === 401) {
    const newToken = await refreshAccessToken();
    if (!newToken) {
      if (typeof window !== 'undefined') window.location.href = '/login';
      return res;
    }
    headers['Authorization'] = `Bearer ${newToken}`;
    return fetch(`${BASE}${path}`, { ...options, headers, credentials: 'include' });
  }

  return res;
}

export const api = {
  get: (path: string, options?: RequestInit) =>
    apiFetch(path, { ...options, method: 'GET' }),
  post: (path: string, body?: unknown, options?: RequestInit) =>
    apiFetch(path, {
      ...options,
      method: 'POST',
      body: body !== undefined ? JSON.stringify(body) : undefined,
    }),
  patch: (path: string, body?: unknown, options?: RequestInit) =>
    apiFetch(path, {
      ...options,
      method: 'PATCH',
      body: body !== undefined ? JSON.stringify(body) : undefined,
    }),
  delete: (path: string, options?: RequestInit) =>
    apiFetch(path, { ...options, method: 'DELETE' }),
};
