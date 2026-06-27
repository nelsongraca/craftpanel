import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import { ConsoleTab } from '../console-tab'

const { mockTerminal } = vi.hoisted(() => ({
    mockTerminal: {
        loadAddon: vi.fn(),
        open: vi.fn(),
        write: vi.fn(),
        onData: vi.fn(),
        dispose: vi.fn(),
    },
}))

vi.mock('@/lib/generated/sdk.gen', () => ({
    authWsTicket: vi.fn(),
}))

vi.mock('@xterm/xterm', () => ({
    Terminal: vi.fn(() => mockTerminal),
}))

vi.mock('@xterm/addon-fit', () => ({
    FitAddon: vi.fn(() => ({ fit: vi.fn() })),
}))

vi.mock('@xterm/xterm/css/xterm.css', () => ({}))

import { authWsTicket } from '@/lib/generated/sdk.gen'

class MockWebSocket {
    static instances: MockWebSocket[] = []
    static readonly CONNECTING = 0
    static readonly OPEN = 1
    static readonly CLOSING = 2
    static readonly CLOSED = 3

    onmessage: ((ev: { data: string }) => void) | null = null
    onclose: (() => void) | null = null
    onopen: (() => void) | null = null
    onerror: (() => void) | null = null
    close = vi.fn()
    send = vi.fn()
    url: string
    readyState = MockWebSocket.OPEN

    constructor(url: string) {
        this.url = url
        MockWebSocket.instances.push(this)
    }
}

function stubGlobals() {
    vi.stubGlobal('WebSocket', MockWebSocket)
    vi.stubGlobal('ResizeObserver', vi.fn(() => ({
        observe: vi.fn(),
        disconnect: vi.fn(),
    })))
}

/** Flush microtasks so the async init() inside useEffect completes. */
async function flushMicrotasks(times = 10) {
    for (let i = 0; i < times; i++) {
        await act(async () => { await Promise.resolve() })
    }
}

/** Render with HEALTHY, mock ticket, and wait for WS instance to appear. */
async function renderConsoleTab(props: Partial<React.ComponentProps<typeof ConsoleTab>> = {}) {
    vi.mocked(authWsTicket).mockResolvedValue({ data: { ticket: 'tkt', expires_in: 900 } } as never)
    render(<ConsoleTab serverId="s1" serverStatus="HEALTHY" {...props} />)
    await flushMicrotasks()
}

function getWs() {
    return MockWebSocket.instances[0]
}

describe('ConsoleTab', () => {
    beforeEach(() => {
        MockWebSocket.instances = []
        stubGlobals()
        vi.clearAllMocks()
    })

    afterEach(() => {
        vi.unstubAllGlobals()
    })

    describe('status gating', () => {
        it('shows not-running message when server status is not HEALTHY', () => {
            render(<ConsoleTab serverId="s1" serverStatus="STOPPED" />)
            expect(screen.getByText('Server is not running')).toBeInTheDocument()
        })

        it('does not connect WebSocket when server is not HEALTHY', () => {
            render(<ConsoleTab serverId="s1" serverStatus="STOPPED" />)
            expect(MockWebSocket.instances).toHaveLength(0)
        })
    })

    describe('connection lifecycle', () => {
        it('shows connecting status initially', () => {
            render(<ConsoleTab serverId="s1" serverStatus="HEALTHY" />)
            expect(screen.getByText('Connecting…')).toBeInTheDocument()
        })

        it('opens terminal in container div', async () => {
            await renderConsoleTab()
            expect(mockTerminal.open).toHaveBeenCalled()
        })

        it('fetches WebSocket ticket on mount', async () => {
            await renderConsoleTab()
            expect(authWsTicket).toHaveBeenCalledOnce()
        })

        it('connects WebSocket to console endpoint with ticket query param', async () => {
            await renderConsoleTab()
            const ws = getWs()
            expect(ws.url).toContain('/api/ws/console/s1')
            expect(ws.url).toContain('ticket=tkt')
        })

        it('clears status on console.ready', async () => {
            await renderConsoleTab()
            act(() => { getWs().onmessage?.({ data: JSON.stringify({ type: 'console.ready' }) }) })
            expect(screen.queryByText('Connecting…')).not.toBeInTheDocument()
        })

        it('disconnects WebSocket and disposes terminal on unmount', async () => {
            vi.mocked(authWsTicket).mockResolvedValue({ data: { ticket: 'tkt', expires_in: 900 } } as never)
            const { unmount } = render(<ConsoleTab serverId="s1" serverStatus="HEALTHY" />)
            await flushMicrotasks()
            const ws = getWs()
            unmount()
            expect(ws.close).toHaveBeenCalled()
            expect(mockTerminal.dispose).toHaveBeenCalled()
        })
    })

    describe('console.output', () => {
        it('writes output to terminal with \\r\\n line endings', async () => {
            await renderConsoleTab()
            act(() => {
                getWs().onmessage?.({ data: JSON.stringify({ type: 'console.output', data: 'Hello\nWorld' }) })
            })
            expect(mockTerminal.write).toHaveBeenCalledWith('Hello\r\nWorld')
        })

        it('writes empty string when data field is missing', async () => {
            await renderConsoleTab()
            act(() => {
                getWs().onmessage?.({ data: JSON.stringify({ type: 'console.output' }) })
            })
            expect(mockTerminal.write).toHaveBeenCalledWith('')
        })
    })

    describe('console.disconnected', () => {
        it('writes yellow message and shows reason as status', async () => {
            await renderConsoleTab()
            act(() => {
                getWs().onmessage?.({ data: JSON.stringify({ type: 'console.disconnected', reason: 'Server stopped' }) })
            })
            expect(mockTerminal.write).toHaveBeenCalledWith('\r\n\x1b[33m[Server stopped]\x1b[0m\r\n')
            expect(screen.getByText('Server stopped')).toBeInTheDocument()
        })

        it('shows generic "Disconnected" when no reason given', async () => {
            await renderConsoleTab()
            act(() => {
                getWs().onmessage?.({ data: JSON.stringify({ type: 'console.disconnected' }) })
            })
            expect(mockTerminal.write).toHaveBeenCalledWith('\r\n\x1b[33m[Disconnected]\x1b[0m\r\n')
        })
    })

    describe('stdin', () => {
        it('sends console.input JSON on terminal data', async () => {
            await renderConsoleTab()
            const onDataCb = mockTerminal.onData.mock.calls[0][0]
            act(() => { onDataCb('help\n') })
            expect(getWs().send).toHaveBeenCalledWith(
                JSON.stringify({ type: 'console.input', data: 'help\n' })
            )
        })

        it('echoes input to terminal', async () => {
            await renderConsoleTab()
            const onDataCb = mockTerminal.onData.mock.calls[0][0]
            act(() => { onDataCb('help\n') })
            expect(mockTerminal.write).toHaveBeenCalledWith('help\n')
        })
    })

    describe('error handling', () => {
        it('shows error when ticket fetch fails', async () => {
            vi.mocked(authWsTicket).mockResolvedValue({ error: { message: 'unauthorized' } } as never)
            render(<ConsoleTab serverId="s1" serverStatus="HEALTHY" />)
            await flushMicrotasks()
            expect(screen.getByText('Failed to get WebSocket ticket')).toBeInTheDocument()
        })

        it('shows error on WebSocket connection failure', async () => {
            await renderConsoleTab()
            act(() => { getWs().onerror?.() })
            expect(screen.getByText('WebSocket connection failed')).toBeInTheDocument()
        })

        it('retains "Connecting…" status on close when no ready message was received', async () => {
            await renderConsoleTab()
            act(() => { getWs().onclose?.() })
            expect(screen.getByText('Connecting…')).toBeInTheDocument()
        })

        it('ignores malformed WS frames without throwing', async () => {
            await renderConsoleTab()
            expect(() => {
                act(() => { getWs().onmessage?.({ data: 'not-json' }) })
            }).not.toThrow()
        })
    })

    describe('reconnection', () => {
        beforeEach(() => {
            vi.useFakeTimers()
        })
        afterEach(() => {
            vi.useRealTimers()
        })

        it('reconnects after 3 seconds on close', async () => {
            vi.mocked(authWsTicket).mockResolvedValue({ data: { ticket: 'tkt', expires_in: 900 } } as never)
            render(<ConsoleTab serverId="s1" serverStatus="HEALTHY" />)
            await flushMicrotasks()

            expect(MockWebSocket.instances).toHaveLength(1)
            const ws = getWs()
            act(() => { ws.onclose?.() })

            vi.advanceTimersByTime(3000)
            await flushMicrotasks()

            expect(MockWebSocket.instances).toHaveLength(2)
        })

        it('does not reconnect after unmount', async () => {
            vi.mocked(authWsTicket).mockResolvedValue({ data: { ticket: 'tkt', expires_in: 900 } } as never)
            const { unmount } = render(<ConsoleTab serverId="s1" serverStatus="HEALTHY" />)
            await flushMicrotasks()

            const ws = getWs()
            unmount()
            act(() => { ws.onclose?.() })

            vi.advanceTimersByTime(3000)
            await flushMicrotasks()

            expect(MockWebSocket.instances).toHaveLength(1)
        })
    })
})
