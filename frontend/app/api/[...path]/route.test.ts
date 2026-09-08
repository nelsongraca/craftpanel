import {describe, it, expect, vi, beforeEach} from 'vitest'
import {NextRequest} from 'next/server'

const {fetchMock} = vi.hoisted(() => ({fetchMock: vi.fn()}))

vi.stubGlobal('fetch', fetchMock)

import {GET, POST, PUT, PATCH, DELETE} from './route'

function makeReq(url: string, init?: RequestInit): NextRequest {
    return new NextRequest(url, init)
}

describe('api proxy route', () => {
    const upstream = {
        status: 200,
        ok: true,
        headers: new Headers({'content-type': 'application/json'}),
        body: '{"ok":true}',
    }

    beforeEach(() => {
        fetchMock.mockReset()
        delete process.env.MASTER_URL
    })

    it('GET forwards path and query to the master and returns the response', async () => {
        fetchMock.mockResolvedValue(upstream)

        const req = makeReq('http://frontend.local/api/servers?page=1', {
            method: 'GET',
            headers: {host: 'frontend.local', origin: 'http://frontend.local', 'x-custom': 'yes'},
        })
        const res = await GET(req)

        expect(fetchMock).toHaveBeenCalledTimes(1)
        const [target, init] = fetchMock.mock.calls[0]
        expect(target).toBe('http://localhost:8080/api/servers?page=1')
        expect(init.method).toBe('GET')
        expect(init.headers.get('host')).toBeNull()
        expect(init.headers.get('origin')).toBeNull()
        expect(init.headers.get('referer')).toBeNull()
        expect(init.headers.get('x-custom')).toBe('yes')
        expect(res.status).toBe(200)
        expect(await res.text()).toBe('{"ok":true}')
    })

    it('POST forwards method and body upstream', async () => {
        fetchMock.mockResolvedValue(upstream)

        const req = makeReq('http://frontend.local/api/servers', {
            method: 'POST',
            body: '{"name":"test"}',
        })
        const res = await POST(req)

        const [target, init] = fetchMock.mock.calls[0]
        expect(target).toBe('http://localhost:8080/api/servers')
        expect(init.method).toBe('POST')
        expect(init.body).toBeDefined()
        expect(res.status).toBe(200)
    })

    it('forwards PUT, PATCH and DELETE with matching methods', async () => {
        fetchMock.mockResolvedValue(upstream)

        await PUT(makeReq('http://frontend.local/api/servers/1', {method: 'PUT', body: '{}'}))
        await PATCH(makeReq('http://frontend.local/api/servers/1', {method: 'PATCH', body: '{}'}))
        await DELETE(makeReq('http://frontend.local/api/servers/1', {method: 'DELETE'}))

        expect(fetchMock.mock.calls.map(([, init]) => init.method)).toEqual(['PUT', 'PATCH', 'DELETE'])
    })

    it('does not send a body for GET and HEAD', async () => {
        fetchMock.mockResolvedValue(upstream)

        await GET(makeReq('http://frontend.local/api/servers', {method: 'GET'}))
        await GET(makeReq('http://frontend.local/api/servers', {method: 'HEAD'}))

        const [, getInit] = fetchMock.mock.calls[0]
        const [, headInit] = fetchMock.mock.calls[1]
        expect(getInit.body).toBeUndefined()
        expect(headInit.body).toBeUndefined()
    })

    it('strips transfer-encoding from the response headers', async () => {
        fetchMock.mockResolvedValue({
            status: 200,
            headers: new Headers({'transfer-encoding': 'chunked', 'content-type': 'text/plain'}),
            body: 'chunks',
        })

        const res = await GET(makeReq('http://frontend.local/api/health', {method: 'GET'}))

        expect(res.headers.get('transfer-encoding')).toBeNull()
        expect(res.headers.get('content-type')).toBe('text/plain')
    })

    it('uses MASTER_URL when set', async () => {
        vi.stubEnv('MASTER_URL', 'https://master.internal')
        fetchMock.mockResolvedValue(upstream)

        await GET(makeReq('http://frontend.local/api/servers', {method: 'GET'}))

        const [target] = fetchMock.mock.calls[0]
        expect(target).toBe('https://master.internal/api/servers')
        vi.unstubAllEnvs()
    })
})