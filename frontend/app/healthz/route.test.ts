import {describe, it, expect, vi, beforeEach, afterEach} from 'vitest'

const {fetchMock} = vi.hoisted(() => ({fetchMock: vi.fn()}))

vi.stubGlobal('fetch', fetchMock)

import {GET} from './route'

describe('healthz GET', () => {
    beforeEach(() => {
        fetchMock.mockReset()
        delete process.env.APP_VERSION
        delete process.env.MASTER_URL
    })

    afterEach(() => {
        vi.unstubAllEnvs()
    })

    it('reports ok with matching versions', async () => {
        vi.stubEnv('APP_VERSION', '1.0.0')
        fetchMock.mockResolvedValue({
            ok: true,
            json: async () => ({version: '1.0.0'}),
        })

        const res = await GET()
        const body = await res.json()

        expect(res.status).toBe(200)
        expect(body.status).toBe('ok')
        expect(body.frontendVersion).toBe('1.0.0')
        expect(body.masterVersion).toBe('1.0.0')
        expect(body.versionMismatch).toBe(false)
    })

    it('flags a version mismatch', async () => {
        vi.stubEnv('APP_VERSION', '2.0.0')
        fetchMock.mockResolvedValue({
            ok: true,
            json: async () => ({version: '1.0.0'}),
        })

        const res = await GET()
        const body = await res.json()

        expect(body.versionMismatch).toBe(true)
    })

    it('falls back to unknown when master is unhealthy', async () => {
        vi.stubEnv('APP_VERSION', '1.0.0')
        fetchMock.mockResolvedValue({ok: false})

        const res = await GET()
        const body = await res.json()

        expect(body.masterVersion).toBe('unknown')
        expect(body.versionMismatch).toBe(false)
    })

    it('falls back to unknown when master is unreachable', async () => {
        vi.stubEnv('APP_VERSION', '1.0.0')
        fetchMock.mockRejectedValue(new Error('ECONNREFUSED'))

        const res = await GET()
        const body = await res.json()

        expect(body.masterVersion).toBe('unknown')
        expect(res.status).toBe(200)
    })

    it('falls back to unknown when master omits a version', async () => {
        vi.stubEnv('APP_VERSION', '1.0.0')
        fetchMock.mockResolvedValue({
            ok: true,
            json: async () => ({}),
        })

        const res = await GET()
        const body = await res.json()

        expect(body.masterVersion).toBe('unknown')
        expect(body.versionMismatch).toBe(false)
    })

    it('reports unknown versions when APP_VERSION is unset', async () => {
        fetchMock.mockResolvedValue({
            ok: true,
            json: async () => ({version: '1.0.0'}),
        })

        const res = await GET()
        const body = await res.json()

        expect(body.frontendVersion).toBe('unknown')
        expect(body.versionMismatch).toBe(false)
    })
})