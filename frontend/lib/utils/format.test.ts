import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { fmtBytes, timeAgo } from './format'

describe('fmtBytes', () => {
    it('formats bytes below 1 KB', () => {
        expect(fmtBytes(0)).toBe('0 B')
        expect(fmtBytes(500)).toBe('500 B')
        expect(fmtBytes(1023)).toBe('1023 B')
    })

    it('formats KB boundary', () => {
        expect(fmtBytes(1024)).toBe('1.0 KB')
        expect(fmtBytes(1536)).toBe('1.5 KB')
    })

    it('formats MB boundary', () => {
        expect(fmtBytes(1_048_576)).toBe('1.0 MB')
        expect(fmtBytes(52_428_800)).toBe('50.0 MB')
    })

    it('formats GB boundary', () => {
        expect(fmtBytes(1_073_741_824)).toBe('1.0 GB')
        expect(fmtBytes(2_147_483_648)).toBe('2.0 GB')
    })
})

describe('timeAgo', () => {
    const now = new Date('2024-01-01T12:00:00.000Z').getTime()

    beforeEach(() => {
        vi.spyOn(Date, 'now').mockReturnValue(now)
    })

    afterEach(() => {
        vi.restoreAllMocks()
    })

    it('shows seconds for < 60s', () => {
        const iso = new Date(now - 30_000).toISOString()
        expect(timeAgo(iso)).toBe('30 seconds ago')
    })

    it('shows minutes for < 1h', () => {
        const iso = new Date(now - 5 * 60_000).toISOString()
        expect(timeAgo(iso)).toBe('5 minutes ago')
    })

    it('shows hours for < 24h', () => {
        const iso = new Date(now - 3 * 3_600_000).toISOString()
        expect(timeAgo(iso)).toBe('3 hours ago')
    })

    it('shows days for >= 24h', () => {
        const iso = new Date(now - 2 * 86_400_000).toISOString()
        expect(timeAgo(iso)).toBe('2 days ago')
    })
})
