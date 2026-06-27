import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { WsProvider, useWs } from '../ws-context'

type AnyFn = (...args: unknown[]) => void

const { capturedOnEvent } = vi.hoisted(() => ({
    capturedOnEvent: { current: null as ((type: string, payload: Record<string, unknown>) => void) | null },
}))

vi.mock('@/lib/hooks/useDashboardSocket', () => ({
    useDashboardSocket: (onEvent: (type: string, payload: Record<string, unknown>) => void) => {
        capturedOnEvent.current = onEvent
    },
}))

describe('WsProvider', () => {
    beforeEach(() => {
        capturedOnEvent.current = null
    })

    it('renders children', () => {
        render(<WsProvider><div data-testid="child">hi</div></WsProvider>)
        expect(screen.getByTestId('child')).toHaveTextContent('hi')
    })

    it('subscribe returns an unsubscribe function', () => {
        let capturedSubscribe: ((type: string, listener: AnyFn) => () => void) | null = null
        function Consumer() {
            const { subscribe } = useWs()
            capturedSubscribe = subscribe
            return null
        }

        render(<WsProvider><Consumer /></WsProvider>)

        const listener = vi.fn()
        const unsub = capturedSubscribe!('server.status', listener)
        capturedOnEvent.current!('server.status', { id: '1' })
        expect(listener).toHaveBeenCalledWith({ id: '1' })

        unsub()
        capturedOnEvent.current!('server.status', { id: '2' })
        expect(listener).toHaveBeenCalledTimes(1)
    })

    it('after unsubscribe, listener is not called on event', () => {
        let capturedSubscribe: ((type: string, listener: AnyFn) => () => void) | null = null
        function Consumer() {
            const { subscribe } = useWs()
            capturedSubscribe = subscribe
            return null
        }

        render(<WsProvider><Consumer /></WsProvider>)

        const listener = vi.fn()
        const unsub = capturedSubscribe!('server.status', listener)
        unsub()
        capturedOnEvent.current!('server.status', { id: '1' })

        expect(listener).not.toHaveBeenCalled()
    })

    it('useWs throws without WsProvider', () => {
        function Bare() {
            useWs()
            return null
        }
        expect(() => render(<Bare />)).toThrow('useWs must be used within WsProvider')
    })

    it('useWs returns subscribe inside WsProvider', () => {
        let capturedSubscribe: unknown = null
        function Consumer() {
            const ctx = useWs()
            capturedSubscribe = ctx.subscribe
            return null
        }

        render(<WsProvider><Consumer /></WsProvider>)
        expect(capturedSubscribe).toBeDefined()
        expect(typeof capturedSubscribe).toBe('function')
    })
})
