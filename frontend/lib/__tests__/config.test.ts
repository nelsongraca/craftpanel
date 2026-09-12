import {describe, it, expect, vi, beforeEach, afterEach} from 'vitest'
import {fetchAppName, fetchBrandingConfig, BrandingConfig} from "@/lib/config"

function mockApiResponse(body: Record<string, unknown>, status = 200) {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
        new Response(JSON.stringify(body), {
            status,
            headers: {'Content-Type': 'application/json'},
        })
    ))
}

describe('fetchAppName', () => {
    beforeEach(() => {
        vi.useFakeTimers()
    })
    afterEach(() => {
        vi.unstubAllGlobals()
        vi.useRealTimers()
    })

    it('returns app_name from API when present', async () => {
        mockApiResponse({app_name: 'MyPanel'})
        await expect(fetchAppName()).resolves.toBe('MyPanel')
    })

    it('falls back to CraftPanel when API returns blank app_name', async () => {
        mockApiResponse({app_name: '  '})
        await expect(fetchAppName()).resolves.toBe('CraftPanel')
    })

    it('falls back to CraftPanel on network error', async () => {
        vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
        await expect(fetchAppName()).resolves.toBe('CraftPanel')
    })

    it('falls back to CraftPanel on non-ok response', async () => {
        mockApiResponse({}, 500)
        await expect(fetchAppName()).resolves.toBe('CraftPanel')
    })
})

describe('fetchBrandingConfig', () => {
    beforeEach(() => {
        vi.useFakeTimers()
    })
    afterEach(() => {
        vi.unstubAllGlobals()
        vi.useRealTimers()
    })

    it('BUG REPRODUCTION: returns appName from snake_case API response', async () => {
        // The backend sends snake_case keys: app_name, has_logo, logo_hash, logo_url
        mockApiResponse({
            app_name: 'MyPanel',
            has_logo: true,
            logo_hash: 'abc123',
            logo_url: '/api/branding/logo',
        })
        const cfg = await fetchBrandingConfig()
        expect(cfg.appName).toBe('MyPanel')
    })

    it('falls back to CraftPanel when API returns blank app_name', async () => {
        mockApiResponse({
            app_name: '  ',
            has_logo: false,
            logo_hash: '',
            logo_url: '/api/branding/logo',
        })
        const cfg = await fetchBrandingConfig()
        expect(cfg.appName).toBe('CraftPanel')
    })

    it('falls back to CraftPanel on network error', async () => {
        vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
        const cfg = await fetchBrandingConfig()
        expect(cfg.appName).toBe('CraftPanel')
        expect(cfg.hasLogo).toBe(false)
    })

    it('falls back to CraftPanel on non-ok response', async () => {
        mockApiResponse({}, 500)
        const cfg = await fetchBrandingConfig()
        expect(cfg.appName).toBe('CraftPanel')
    })

    it('passes through logo fields from API', async () => {
        mockApiResponse({
            app_name: 'MyPanel',
            has_logo: true,
            logo_hash: 'def456',
            logo_url: '/api/branding/logo',
        })
        const cfg = await fetchBrandingConfig()
        expect(cfg.hasLogo).toBe(true)
        expect(cfg.logoHash).toBe('def456')
        expect(cfg.logoUrl).toBe('/api/branding/logo')
    })

    it('provides default logoUrl when API returns empty', async () => {
        mockApiResponse({
            app_name: 'MyPanel',
            has_logo: false,
            logo_hash: '',
            logo_url: '',
        })
        const cfg = await fetchBrandingConfig()
        expect(cfg.logoUrl).toBe('/api/branding/logo')
    })
})
