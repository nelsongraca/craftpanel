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
        onTitleChange: vi.fn(),
        attachCustomKeyEventHandler: vi.fn(),
    },
}))

vi.mock('@/lib/generated/sdk.gen', () => ({
    authWsTicket: vi.fn(),
    fetchServerConsoleLogs: vi.fn(),
}))

vi.mock('@/lib/client', () => ({
    getAccessToken: vi.fn(() => 'token'),
}))

vi.mock('@xterm/xterm', () => ({
    Terminal: class {
        loadAddon = mockTerminal.loadAddon
        open = mockTerminal.open
        write = mockTerminal.write
        onData = mockTerminal.onData
        dispose = mockTerminal.dispose
        onTitleChange = mockTerminal.onTitleChange
        attachCustomKeyEventHandler = mockTerminal.attachCustomKeyEventHandler
        constructor() {}
    },
}))

vi.mock('@xterm/addon-fit', () => ({
    FitAddon: class {
        fit = vi.fn()
        constructor() {}
    },
}))

vi.mock('@xterm/xterm/css/xterm.css', () => ({}))

import { authWsTicket, fetchServerConsoleLogs } from '@/lib/generated/sdk.gen'

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
    vi.stubGlobal('ResizeObserver', class {
        observe = vi.fn()
        disconnect = vi.fn()
        constructor() {}
    })
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
        it('shows log view when server is not HEALTHY (STOPPED)', () => {
            vi.mocked(fetchServerConsoleLogs).mockResolvedValue({ data: { lines: ['last log line\n'] } } as never)
            render(<ConsoleTab serverId="s1" serverStatus="STOPPED" />)
            expect(fetchServerConsoleLogs).toHaveBeenCalledWith({ path: { id: 's1' } })
        })

        it('does not connect WebSocket when server is not HEALTHY', () => {
            render(<ConsoleTab serverId="s1" serverStatus="STOPPED" />)
            expect(MockWebSocket.instances).toHaveLength(0)
        })
    })

    describe('UNHEALTHY crash log fallback', () => {
        it('fetches and shows crash log lines instead of attaching console', async () => {
            vi.mocked(fetchServerConsoleLogs).mockResolvedValue({ data: { lines: ['line1\n', 'line2\n'] } } as never)
            render(<ConsoleTab serverId="s1" serverStatus="UNHEALTHY" />)
            await flushMicrotasks()

            expect(fetchServerConsoleLogs).toHaveBeenCalledWith({ path: { id: 's1' } })
            expect(MockWebSocket.instances).toHaveLength(0)
            const pre = screen.getByText(/line1/)
            expect(pre).toBeInTheDocument()
        })

        it('shows empty state when no log lines are returned', async () => {
            vi.mocked(fetchServerConsoleLogs).mockResolvedValue({ data: { lines: [] } } as never)
            render(<ConsoleTab serverId="s1" serverStatus="UNHEALTHY" />)
            await flushMicrotasks()

            expect(screen.getByText('No log output available')).toBeInTheDocument()
        })

        it('shows error when log fetch fails', async () => {
            vi.mocked(fetchServerConsoleLogs).mockResolvedValue({ error: { message: 'forbidden' } } as never)
            render(<ConsoleTab serverId="s1" serverStatus="UNHEALTHY" />)
            await flushMicrotasks()

            expect(screen.getByText('forbidden')).toBeInTheDocument()
        })
    })

    describe('connection lifecycle', () => {
        it('shows connecting status initially', () => {
            render(<ConsoleTab serverId="s1" serverStatus="HEALTHY" />)
            expect(screen.getByText('Connecting\u2026')).toBeInTheDocument()
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
            expect(screen.queryByText('Connecting\u2026')).not.toBeInTheDocument()
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
        it('sends full command on Enter', async () => {
            await renderConsoleTab()
            const onDataCb = mockTerminal.onData.mock.calls[0][0]
            act(() => {
                onDataCb('h')
                onDataCb('e')
                onDataCb('l')
                onDataCb('p')
                onDataCb('\r')
            })
            expect(getWs().send).toHaveBeenCalledWith(
                JSON.stringify({ type: 'console.input', data: 'help\n' })
            )
        })

        it('echoes characters to terminal and writes \\r\\n on Enter', async () => {
            await renderConsoleTab()
            const onDataCb = mockTerminal.onData.mock.calls[0][0]
            act(() => {
                onDataCb('h')
                onDataCb('e')
                onDataCb('l')
                onDataCb('p')
                onDataCb('\r')
            })
            expect(mockTerminal.write).toHaveBeenCalledWith('h')
            expect(mockTerminal.write).toHaveBeenCalledWith('e')
            expect(mockTerminal.write).toHaveBeenCalledWith('l')
            expect(mockTerminal.write).toHaveBeenCalledWith('p')
            expect(mockTerminal.write).toHaveBeenCalledWith('\r\n')
        })

        it('backspace removes last character and erases it on terminal', async () => {
            await renderConsoleTab()
            const onDataCb = mockTerminal.onData.mock.calls[0][0]
            act(() => {
                onDataCb('a')
                onDataCb('b')
                onDataCb('c')
                onDataCb('\x7f')
            })
            const writes = vi.mocked(mockTerminal.write).mock.calls.map(c => c[0])
            expect(writes).toContain('a')
            expect(writes).toContain('b')
            expect(writes).toContain('\b \b')
        })

        it('backspace on empty buffer is no-op', async () => {
            await renderConsoleTab()
            const onDataCb = mockTerminal.onData.mock.calls[0][0]
            mockTerminal.write.mockClear()
            act(() => { onDataCb('\x7f') })
            expect(mockTerminal.write).not.toHaveBeenCalled()
        })

        it('Ctrl+C aborts line and writes ^C', async () => {
            await renderConsoleTab()
            const onDataCb = mockTerminal.onData.mock.calls[0][0]
            act(() => {
                onDataCb('t')
                onDataCb('e')
                onDataCb('s')
                onDataCb('t')
                onDataCb('\x03')
            })
            expect(mockTerminal.write).toHaveBeenCalledWith('^C\r\n')
            expect(getWs().send).not.toHaveBeenCalled()
        })

        it('Tab is silently ignored', async () => {
            await renderConsoleTab()
            const onDataCb = mockTerminal.onData.mock.calls[0][0]
            mockTerminal.write.mockClear()
            act(() => { onDataCb('\t') })
            expect(mockTerminal.write).not.toHaveBeenCalled()
            expect(getWs().send).not.toHaveBeenCalled()
        })

        it('Arrow Up recalls previous command', async () => {
            await renderConsoleTab()
            const onDataCb = mockTerminal.onData.mock.calls[0][0]
            act(() => {
                onDataCb('h')
                onDataCb('e')
                onDataCb('l')
                onDataCb('p')
                onDataCb('\r')
            })
            vi.mocked(mockTerminal.write).mockClear()
            vi.mocked(getWs().send).mockClear()

            act(() => { onDataCb('\x1b[A') })
            expect(mockTerminal.write).toHaveBeenCalledWith('\r\x1b[Khelp')
        })

        it('Arrow Down after Arrow Up restores draft line', async () => {
            await renderConsoleTab()
            const onDataCb = mockTerminal.onData.mock.calls[0][0]
            act(() => {
                onDataCb('h')
                onDataCb('e')
                onDataCb('l')
                onDataCb('p')
                onDataCb('\r')
            })

            act(() => {
                onDataCb('n')
                onDataCb('e')
                onDataCb('x')
                onDataCb('t')
            })

            vi.mocked(mockTerminal.write).mockClear()
            act(() => { onDataCb('\x1b[A') })
            act(() => { onDataCb('\x1b[B') })
            expect(mockTerminal.write).toHaveBeenCalledWith('\r\x1b[Knext')
        })

        it('Arrow Down on fresh line is no-op', async () => {
            await renderConsoleTab()
            const onDataCb = mockTerminal.onData.mock.calls[0][0]
            mockTerminal.write.mockClear()
            act(() => { onDataCb('\x1b[B') })
            expect(mockTerminal.write).not.toHaveBeenCalled()
        })

        it('Arrow Up on empty history is no-op', async () => {
            await renderConsoleTab()
            const onDataCb = mockTerminal.onData.mock.calls[0][0]
            mockTerminal.write.mockClear()
            act(() => { onDataCb('\x1b[A') })
            expect(mockTerminal.write).not.toHaveBeenCalled()
        })

        it('duplicate consecutive commands are not pushed to history twice', async () => {
            await renderConsoleTab()
            const onDataCb = mockTerminal.onData.mock.calls[0][0]
            act(() => {
                onDataCb('o')
                onDataCb('p')
                onDataCb('\r')
            })
            vi.mocked(mockTerminal.write).mockClear()
            act(() => {
                onDataCb('o')
                onDataCb('p')
                onDataCb('\r')
            })

            // History should have 1 entry -> Arrow Up recalls once, second is same entry
            act(() => { onDataCb('\x1b[A') })
            expect(mockTerminal.write).toHaveBeenCalledWith('\r\x1b[Kop')

            vi.mocked(mockTerminal.write).mockClear()
            act(() => { onDataCb('\x1b[A') })
            expect(mockTerminal.write).toHaveBeenCalledWith('\r\x1b[Kop')
        })

        it('empty Enter writes \\r\\n but does not send', async () => {
            await renderConsoleTab()
            const onDataCb = mockTerminal.onData.mock.calls[0][0]
            vi.mocked(mockTerminal.write).mockClear()
            act(() => { onDataCb('\r') })
            expect(mockTerminal.write).toHaveBeenCalledWith('\r\n')
            expect(getWs().send).not.toHaveBeenCalled()
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

        it('retains "Connecting\u2026" status on close when no ready message was received', async () => {
            await renderConsoleTab()
            act(() => { getWs().onclose?.() })
            expect(screen.getByText('Connecting\u2026')).toBeInTheDocument()
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