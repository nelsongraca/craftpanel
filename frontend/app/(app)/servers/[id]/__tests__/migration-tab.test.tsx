import { describe, it, expect, vi, beforeEach, afterEach } from "vitest"
import { render, screen, waitFor, act } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { MigrationTab } from "../migration-tab"

vi.mock("@/lib/generated/sdk.gen", () => ({
    listMigrations: vi.fn(),
    listNodes: vi.fn(),
    startMigration: vi.fn(),
}))

import { listMigrations, listNodes, startMigration } from "@/lib/generated/sdk.gen"

function makeNode(overrides: Partial<{
    id: string
    display_name: string
    public_ip: string
    status: string
}> = {}) {
    return {
        id: "n2",
        display_name: "Node 2",
        public_ip: "192.168.1.2",
        status: "ACTIVE",
        ...overrides,
    }
}

function makeMigration(overrides: Partial<{
    id: string
    status: string
    source_node_id: string
    target_node_id: string
    steps: Array<Record<string, unknown>>
    completed_at: string | null
}> = {}) {
    return {
        id: "mig-1",
        server_id: "s1",
        source_node_id: "n1",
        target_node_id: "n2",
        status: "COMPLETED",
        steps: [],
        created_at: "2024-01-01T00:00:00Z",
        completed_at: "2024-01-01T00:05:00Z",
        ...overrides,
    }
}

// ── WebSocket mock controller ──────────────────────────────────────────

let wsController: {
    onopen: ((e: Event) => void) | null
    onclose: ((e: CloseEvent) => void) | null
    onmessage: ((e: MessageEvent) => void) | null
    onerror: ((e: Event) => void) | null
    close: ReturnType<typeof vi.fn>
}

function triggerWsOpen() {
    act(() => {
        wsController.onopen?.(new Event("open"))
    })
}

function sendWsMessage(data: Record<string, unknown>) {
    act(() => {
        wsController.onmessage?.(new MessageEvent("message", { data: JSON.stringify(data) }))
    })
}

// ── Render helpers ─────────────────────────────────────────────────────

async function renderTab(props: Partial<React.ComponentProps<typeof MigrationTab>> = {}) {
    const user = userEvent.setup()
    vi.mocked(listMigrations).mockResolvedValue({ data: { migrations: [] } } as never)
    render(
        <MigrationTab serverId="s1" nodeId="n1" canMigrate={true} {...props} />,
    )
    await waitFor(() => expect(screen.queryByText("Loading…")).not.toBeInTheDocument())
    return { user }
}

async function openModal(user: ReturnType<typeof userEvent.setup>) {
    vi.mocked(listNodes).mockResolvedValue({ data: [makeNode()] } as never)
    await user.click(screen.getByRole("button", { name: /migrate/i }))
    await waitFor(() => expect(screen.queryByText("Loading nodes…")).not.toBeInTheDocument())
}

async function submitMigration(user: ReturnType<typeof userEvent.setup>, migrationId = "mig-1") {
    vi.mocked(startMigration).mockResolvedValue({ data: { id: migrationId } } as never)
    await user.click(screen.getByRole("button", { name: /start migration/i }))
}

// ── Tests ──────────────────────────────────────────────────────────────

describe("MigrationTab", () => {
    beforeEach(() => {
        vi.clearAllMocks()
        const ctrl = {
            onopen: null as ((e: Event) => void) | null,
            onclose: null as ((e: CloseEvent) => void) | null,
            onmessage: null as ((e: MessageEvent) => void) | null,
            onerror: null as ((e: Event) => void) | null,
            close: vi.fn(),
        }
        wsController = ctrl
        vi.stubGlobal(
            "WebSocket",
            vi.fn(() => ({
                get onopen() {
                    return ctrl.onopen
                },
                set onopen(fn) {
                    ctrl.onopen = fn
                },
                get onclose() {
                    return ctrl.onclose
                },
                set onclose(fn) {
                    ctrl.onclose = fn
                },
                get onmessage() {
                    return ctrl.onmessage
                },
                set onmessage(fn) {
                    ctrl.onmessage = fn
                },
                get onerror() {
                    return ctrl.onerror
                },
                set onerror(fn) {
                    ctrl.onerror = fn
                },
                close: ctrl.close,
                readyState: WebSocket.OPEN,
            })),
        )
    })

    afterEach(() => {
        vi.unstubAllGlobals()
    })

    // ── Loading / Error / Empty ────────────────────────────────────────

    it("renders loading state while fetching migrations", () => {
        vi.mocked(listMigrations).mockImplementation(() => new Promise(() => {})) as never
        render(<MigrationTab serverId="s1" nodeId="n1" canMigrate={true} />)
        expect(screen.getByText("Loading…")).toBeInTheDocument()
    })

    it("renders error when listMigrations fails", async () => {
        vi.mocked(listMigrations).mockResolvedValue({ error: { message: "Server error" } } as never)
        render(<MigrationTab serverId="s1" nodeId="n1" canMigrate={true} />)
        await waitFor(() => expect(screen.getByText("Server error")).toBeInTheDocument())
    })

    it("renders empty state when no migrations exist", async () => {
        await renderTab()
        expect(screen.getByText("No migrations yet.")).toBeInTheDocument()
    })

    it("renders migration history list", async () => {
        vi.mocked(listMigrations).mockResolvedValue({
            data: { migrations: [makeMigration()] },
        } as never)
        render(<MigrationTab serverId="s1" nodeId="n1" canMigrate={true} />)
        await waitFor(() => expect(screen.queryByText("Loading…")).not.toBeInTheDocument())
        expect(screen.getByText("mig-1".slice(0, 8))).toBeInTheDocument()
        expect(screen.getByText("COMPLETED")).toBeInTheDocument()
    })

    // ── Migrate button visibility ──────────────────────────────────────

    it("shows Migrate button when canMigrate is true", async () => {
        await renderTab()
        expect(screen.getByRole("button", { name: /migrate/i })).toBeInTheDocument()
    })

    it("hides Migrate button when canMigrate is false", async () => {
        await renderTab({ canMigrate: false })
        expect(screen.queryByRole("button", { name: /migrate/i })).not.toBeInTheDocument()
    })

    it("hides Migrate button while migration is active", async () => {
        const { user } = await renderTab()
        await openModal(user)
        await submitMigration(user)
        await waitFor(() => expect(screen.getByText("Active Migration")).toBeInTheDocument())
        expect(screen.queryByRole("button", { name: /migrate/i })).not.toBeInTheDocument()
    })

    // ── MigrateModal ───────────────────────────────────────────────────

    it("opens modal with target node selector", async () => {
        const { user } = await renderTab()
        await openModal(user)
        expect(screen.getByText("Migrate Server")).toBeInTheDocument()
        expect(screen.getByText("Target Node")).toBeInTheDocument()
        expect(screen.getByText(/Node 2/)).toBeInTheDocument()
    })

    it("shows empty state when no eligible target nodes", async () => {
        const { user } = await renderTab()
        vi.mocked(listNodes).mockResolvedValue({
            data: [
                { id: "n1", display_name: "Only Node", public_ip: "10.0.0.1", status: "ACTIVE" },
            ],
        } as never)
        await user.click(screen.getByRole("button", { name: /migrate/i }))
        await waitFor(() => {
            expect(screen.getByText(/No eligible target nodes/)).toBeInTheDocument()
        })
    })

    it("filters out current node and non-ACTIVE nodes from target selector", async () => {
        const { user } = await renderTab()
        vi.mocked(listNodes).mockResolvedValue({
            data: [
                makeNode({ id: "n2", display_name: "Active Node", status: "ACTIVE" }),
                makeNode({ id: "n3", display_name: "Pending Node", status: "PENDING" }),
            ],
        } as never)
        await user.click(screen.getByRole("button", { name: /migrate/i }))
        await waitFor(() => expect(screen.queryByText("Loading nodes…")).not.toBeInTheDocument())
        expect(screen.getByText(/Active Node/)).toBeInTheDocument()
        expect(screen.queryByText("Pending Node")).not.toBeInTheDocument()
    })

    it("auto-selects first eligible node and enables Start Migration", async () => {
        const { user } = await renderTab()
        await openModal(user)
        const btn = screen.getByRole("button", { name: /start migration/i })
        expect(btn).toBeEnabled()
    })

    it("cancels modal and returns to main view", async () => {
        const { user } = await renderTab()
        await openModal(user)
        await user.click(screen.getByRole("button", { name: /cancel/i }))
        await waitFor(() => expect(screen.queryByText("Migrate Server")).not.toBeInTheDocument())
    })

    it("clicking Start Migration calls startMigration API with correct body", async () => {
        const { user } = await renderTab()
        await openModal(user)
        await submitMigration(user)
        await waitFor(() => {
            expect(startMigration).toHaveBeenCalledWith({
                path: { id: "s1" },
                body: {
                    target_node_id: "n2",
                    rsync_image: "alpine",
                    player_warning_message: "Server is restarting in 60 seconds",
                },
            })
        })
    })

    it("shows submitting state while migration starts", async () => {
        const { user } = await renderTab()
        await openModal(user)
        vi.mocked(startMigration).mockImplementation(() => new Promise(() => {})) as never
        await user.click(screen.getByRole("button", { name: /start migration/i }))
        await waitFor(() => expect(screen.getByText("Starting…")).toBeInTheDocument())
    })

    it("shows error on the modal when startMigration fails", async () => {
        const { user } = await renderTab()
        await openModal(user)
        vi.mocked(startMigration).mockResolvedValue({
            error: { message: "Target node is full" },
        } as never)
        await user.click(screen.getByRole("button", { name: /start migration/i }))
        await waitFor(() => expect(screen.getByText("Target node is full")).toBeInTheDocument())
    })

    it("closes modal and shows active migration on successful start", async () => {
        const { user } = await renderTab()
        await openModal(user)
        await submitMigration(user)
        await waitFor(() => {
            expect(screen.queryByText("Migrate Server")).not.toBeInTheDocument()
            expect(screen.getByText("Active Migration")).toBeInTheDocument()
        })
    })

    // ── ActiveMigration ────────────────────────────────────────────────

    it("shows connecting state before WebSocket opens", async () => {
        const { user } = await renderTab()
        await openModal(user)
        await submitMigration(user)
        await waitFor(() => expect(screen.getByText("Active Migration")).toBeInTheDocument())
        expect(screen.getByText("Connecting…")).toBeInTheDocument()
    })

    it("shows migration status after WebSocket connects", async () => {
        const { user } = await renderTab()
        await openModal(user)
        await submitMigration(user)
        await waitFor(() => expect(screen.getByText("Active Migration")).toBeInTheDocument())
        triggerWsOpen()
        await waitFor(() => expect(screen.queryByText("Connecting…")).not.toBeInTheDocument())
        expect(screen.getByText("PENDING")).toBeInTheDocument()
    })

    it("shows status steps during migration progress", async () => {
        const { user } = await renderTab()
        await openModal(user)
        await submitMigration(user)
        triggerWsOpen()
        sendWsMessage({ type: "status", status: "SYNCING" })
        sendWsMessage({ type: "step.started", step: 1, description: "Preparing rsync" })
        await waitFor(() => expect(screen.getByText("Preparing rsync")).toBeInTheDocument())
        expect(screen.getByText("SYNCING")).toBeInTheDocument()
    })

    it("shows rsync progress bar during sync step", async () => {
        const { user } = await renderTab()
        await openModal(user)
        await submitMigration(user)
        triggerWsOpen()
        sendWsMessage({ type: "step.started", step: 2, description: "Syncing world data" })
        sendWsMessage({ type: "rsync.progress", step: 2, percent: 45 })
        await waitFor(() => expect(screen.getByText("Syncing world data")).toBeInTheDocument())
        const bar = document.querySelector("[style*='45%']")
        expect(bar).not.toBeNull()
    })

    it("shows completed state on migration completion", async () => {
        const { user } = await renderTab()
        await openModal(user)
        await submitMigration(user)
        triggerWsOpen()
        sendWsMessage({ type: "completed" })
        await waitFor(() => {
            expect(screen.getByText("COMPLETED")).toBeInTheDocument()
        })
    })

    it("shows error state when migration fails", async () => {
        const { user } = await renderTab()
        await openModal(user)
        await submitMigration(user)
        triggerWsOpen()
        sendWsMessage({ type: "failed", error: "Rsync connection timeout" })
        await waitFor(() => {
            expect(screen.getByText("Rsync connection timeout")).toBeInTheDocument()
        })
        expect(screen.getByText("FAILED")).toBeInTheDocument()
    })

    it("reappears Migrate button after failure timeout (retry)", async () => {
        vi.useFakeTimers({ shouldAdvanceTime: true })
        const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTimeAsync })
        vi.mocked(listMigrations).mockResolvedValue({ data: { migrations: [] } } as never)
        render(<MigrationTab serverId="s1" nodeId="n1" canMigrate={true} />)
        await waitFor(() => expect(screen.queryByText("Loading…")).not.toBeInTheDocument())

        await openModal(user)
        await submitMigration(user)
        triggerWsOpen()
        sendWsMessage({ type: "failed", error: "Disk full" })
        await vi.advanceTimersByTimeAsync(3000)
        await waitFor(() => {
            expect(screen.getByRole("button", { name: /migrate/i })).toBeInTheDocument()
        })
        expect(screen.queryByText("Active Migration")).not.toBeInTheDocument()
        vi.useRealTimers()
    })

    it("reloads migration history after migration completes", async () => {
        vi.useFakeTimers({ shouldAdvanceTime: true })
        const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTimeAsync })
        vi.mocked(listMigrations).mockResolvedValue({ data: { migrations: [] } } as never)
        render(<MigrationTab serverId="s1" nodeId="n1" canMigrate={true} />)
        await waitFor(() => expect(screen.queryByText("Loading…")).not.toBeInTheDocument())

        await openModal(user)
        await submitMigration(user)
        triggerWsOpen()
        vi.mocked(listMigrations).mockResolvedValue({
            data: { migrations: [makeMigration()] },
        } as never)
        sendWsMessage({ type: "completed" })
        await vi.advanceTimersByTimeAsync(2000)
        await waitFor(() => {
            expect(screen.getByText("mig-1".slice(0, 8))).toBeInTheDocument()
        })
        vi.useRealTimers()
    })
})
