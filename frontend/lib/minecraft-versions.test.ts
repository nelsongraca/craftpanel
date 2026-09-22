import {describe, it, expect, vi, afterEach} from 'vitest'
import {fetchReleaseVersions} from './minecraft-versions'

describe('fetchReleaseVersions', () => {
    afterEach(() => { vi.restoreAllMocks() })

    it('filters to release versions and maps id', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
            json: async () => ({
                versions: [
                    { id: '1.21.5', type: 'release' },
                    { id: '1.21.5-pre1', type: 'snapshot' },
                    { id: '1.21.4', type: 'release' },
                ],
            }),
        }))
        const result = await fetchReleaseVersions()
        expect(result).toEqual(['1.21.5', '1.21.4'])
        vi.unstubAllGlobals()
    })

    it('returns [] on fetch error', async () => {
        vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network')))
        const result = await fetchReleaseVersions()
        expect(result).toEqual([])
        vi.unstubAllGlobals()
    })
})
