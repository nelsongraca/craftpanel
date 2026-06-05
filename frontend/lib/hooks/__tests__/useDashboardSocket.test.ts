import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useDashboardSocket } from '../useDashboardSocket'

vi.mock('@/lib/generated/sdk.gen', () => ({
    authWsTicket: vi.fn(),
}))

vi.mock('@/lib/client', () => ({
    setAccessToken: vi.fn(),
    getAccessToken: vi.fn(),
    client: { setConfig: vi.fn(), interceptors: { request: { use: vi.fn() }, response: { use: vi.fn() } } },
}))

import * as sdkGen from '@/lib/generated/sdk.gen'
import * as clientModule from '@/lib/client'

class MockWebSocket {
    static instances: MockWebSocket[] = []
    onmessage: ((ev: { data: string }) => void) | null = null
    onclose: (() => void) | null = null
    onopen: (() => void) | null = null
    close = vi.fn()
    url: string

    constructor(url: string) {
        this.url = url
        MockWebSocket.instances.push(this)
    }
}

describe('useDashboardSocket', () => {
    beforeEach(() => {
        MockWebSocket.instances = []
        vi.stubGlobal('WebSocket', MockWebSocket)
        vi.useFakeTimers()
        vi.clearAllMocks()
    })

    afterEach(() => {
        vi.useRealTimers()
        vi.unstubAllGlobals()
    })

    it('connects when access token is present', async () => {
        vi.mocked(clientModule.getAccessToken).mockReturnValue('tok')
        vi.mocked(sdkGen.authWsTicket).mockResolvedValue({ data: { ticket: 'abc123' } } as never)

        const onEvent = vi.fn()
        renderHook(() => useDashboardSocket(onEvent))

        await act(async () => {
            await Promise.resolve()
        })

        expect(MockWebSocket.instances).toHaveLength(1)
        expect(MockWebSocket.instances[0].url).toContain('ticket=abc123')
    })

    it('does not connect when no access token', async () => {
        vi.mocked(clientModule.getAccessToken).mockReturnValue(null)

        const onEvent = vi.fn()
        renderHook(() => useDashboardSocket(onEvent))

        await act(async () => {
            await Promise.resolve()
        })

        expect(MockWebSocket.instances).toHaveLength(0)
    })

    it('does not connect when disabled', async () => {
        vi.mocked(clientModule.getAccessToken).mockReturnValue('tok')

        const onEvent = vi.fn()
        renderHook(() => useDashboardSocket(onEvent, { enabled: false }))

        await act(async () => {
            await Promise.resolve()
        })

        expect(MockWebSocket.instances).toHaveLength(0)
    })

    it('calls onEvent when message is received', async () => {
        vi.mocked(clientModule.getAccessToken).mockReturnValue('tok')
        vi.mocked(sdkGen.authWsTicket).mockResolvedValue({ data: { ticket: 'abc123' } } as never)

        const onEvent = vi.fn()
        renderHook(() => useDashboardSocket(onEvent))

        await act(async () => {
            await Promise.resolve()
        })

        const ws = MockWebSocket.instances[0]
        act(() => {
            ws.onmessage?.({ data: JSON.stringify({ type: 'server.status', payload: { id: '1' } }) })
        })

        expect(onEvent).toHaveBeenCalledWith('server.status', { id: '1' })
    })

    it('schedules reconnect with backoff on close', async () => {
        vi.mocked(clientModule.getAccessToken).mockReturnValue('tok')
        vi.mocked(sdkGen.authWsTicket).mockResolvedValue({ data: { ticket: 'abc123' } } as never)

        const onEvent = vi.fn()
        renderHook(() => useDashboardSocket(onEvent))

        await act(async () => {
            await Promise.resolve()
        })

        const ws = MockWebSocket.instances[0]
        act(() => { ws.onclose?.() })

        // first retry: 1000ms
        await act(async () => {
            vi.advanceTimersByTime(1000)
            await Promise.resolve()
        })

        expect(MockWebSocket.instances.length).toBeGreaterThan(1)
    })

    it('cleans up WebSocket and timers on unmount', async () => {
        vi.mocked(clientModule.getAccessToken).mockReturnValue('tok')
        vi.mocked(sdkGen.authWsTicket).mockResolvedValue({ data: { ticket: 'abc123' } } as never)

        const onEvent = vi.fn()
        const { unmount } = renderHook(() => useDashboardSocket(onEvent))

        await act(async () => {
            await Promise.resolve()
        })

        const ws = MockWebSocket.instances[0]
        unmount()

        expect(ws.close).toHaveBeenCalled()

        // close after unmount should not trigger reconnect
        act(() => { ws.onclose?.() })
        vi.advanceTimersByTime(5000)
        expect(MockWebSocket.instances).toHaveLength(1)
    })
})
