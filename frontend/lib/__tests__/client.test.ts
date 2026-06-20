import {describe, it, expect, vi, beforeEach} from 'vitest'

// vi.hoisted ensures these are initialized before vi.mock factory runs (vi.mock is hoisted)
const {requestInterceptorHolder, responseInterceptorHolder} = vi.hoisted(() => ({
    requestInterceptorHolder: {fn: null as ((req: Request) => Request) | null},
    responseInterceptorHolder: {fn: null as ((res: Response, req: Request) => Promise<Response> | Response) | null},
}))

vi.mock('../generated/client.gen', () => ({
    client: {
        setConfig: vi.fn(),
        interceptors: {
            request: {
                use: vi.fn((cb: (req: Request) => Request) => {
                    requestInterceptorHolder.fn = cb
                }),
            },
            response: {
                use: vi.fn((cb: (res: Response, req: Request) => Promise<Response> | Response) => {
                    responseInterceptorHolder.fn = cb
                }),
            },
        },
    },
}))

import {setAccessToken, getAccessToken} from '../client'

describe('client token management', () => {
    beforeEach(() => {
        setAccessToken(null)
    })

    it('getAccessToken returns null by default', () => {
        expect(getAccessToken()).toBeNull()
    })

    it('setAccessToken / getAccessToken round-trip', () => {
        setAccessToken('tok-abc')
        expect(getAccessToken()).toBe('tok-abc')
    })

    it('setAccessToken null clears token', () => {
        setAccessToken('tok-abc')
        setAccessToken(null)
        expect(getAccessToken()).toBeNull()
    })
})

describe('request interceptor', () => {
    beforeEach(() => {
        setAccessToken(null)
    })

    it('adds Authorization header when token is set', () => {
        setAccessToken('my-token')
        const req = new Request('http://localhost/api/test')
        const result = requestInterceptorHolder.fn!(req)
        expect(result.headers.get('Authorization')).toBe('Bearer my-token')
    })

    it('does not add Authorization header when token is null', () => {
        setAccessToken(null)
        const req = new Request('http://localhost/api/test')
        const result = requestInterceptorHolder.fn!(req)
        expect(result.headers.get('Authorization')).toBeNull()
    })
})

describe('response interceptor', () => {
    beforeEach(() => {
        setAccessToken(null)
        vi.stubGlobal('fetch', vi.fn())
    })

    afterEach(() => {
        vi.unstubAllGlobals()
    })

    it('passes through non-401 responses unchanged', async () => {
        const res = new Response('ok', {status: 200})
        const req = new Request('http://localhost/api/test')
        const result = await responseInterceptorHolder.fn!(res, req)
        expect(result.status).toBe(200)
    })

    it('on 401 refreshes token and retries request with new token', async () => {
        const refreshed = new Response(JSON.stringify({access_token: 'new-tok'}), {
            status: 200,
            headers: {'Content-Type': 'application/json'},
        })
        const retried = new Response('ok', {status: 200})
        vi.mocked(fetch)
            .mockResolvedValueOnce(refreshed)
            .mockResolvedValueOnce(retried)

        const res = new Response('unauthorized', {status: 401})
        const req = new Request('http://localhost/api/protected')
        const result = await responseInterceptorHolder.fn!(res, req)

        expect(result.status).toBe(200)
        expect(getAccessToken()).toBe('new-tok')
        const retriedReq = vi.mocked(fetch).mock.calls[1][0] as Request
        expect(retriedReq.headers.get('Authorization')).toBe('Bearer new-tok')
    })

    it('on 401 returns original response when refresh fails', async () => {
        vi.mocked(fetch).mockResolvedValueOnce(new Response('', {status: 401}))

        const res = new Response('unauthorized', {status: 401})
        const req = new Request('http://localhost/api/protected')
        const result = await responseInterceptorHolder.fn!(res, req)

        expect(result.status).toBe(401)
        expect(getAccessToken()).toBeNull()
    })

    it('concurrent 401 responses deduplicate refresh call', async () => {
        const refreshed = new Response(JSON.stringify({access_token: 'tok-dedup'}), {
            status: 200,
            headers: {'Content-Type': 'application/json'},
        })
        const retried = new Response('ok', {status: 200})
        vi.mocked(fetch)
            .mockResolvedValueOnce(refreshed)
            .mockResolvedValue(retried)

        const res1 = new Response('unauthorized', {status: 401})
        const res2 = new Response('unauthorized', {status: 401})
        const req1 = new Request('http://localhost/api/a')
        const req2 = new Request('http://localhost/api/b')

        await Promise.all([
            responseInterceptorHolder.fn!(res1, req1),
            responseInterceptorHolder.fn!(res2, req2),
        ])

        // refresh is called as fetch("/api/auth/refresh", ...) — first arg is a string
        const refreshCalls = vi.mocked(fetch).mock.calls.filter(([req]) => {
            const url = typeof req === 'string' ? req : (req as Request).url
            return url?.includes('/api/auth/refresh')
        })
        expect(refreshCalls).toHaveLength(1)
    })
})
