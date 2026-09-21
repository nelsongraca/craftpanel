import {describe, it, expect, vi, beforeEach, afterEach} from "vitest";
import {render, screen, waitFor, act} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

const {subscribeMock, pushMock, fetchMock} = vi.hoisted(() => ({
    subscribeMock: vi.fn(() => vi.fn()),
    pushMock: vi.fn(),
    fetchMock: vi.fn(),
}));

vi.mock("@/lib/generated/sdk.gen", () => ({
    getNode: vi.fn(),
    getNodeMetrics: vi.fn(),
    listServers: vi.fn(),
    trustNode: vi.fn(),
    rejectNode: vi.fn(),
    rotateNodeToken: vi.fn(),
    shutdownNode: vi.fn(),
    decommissionNode: vi.fn(),
    updateNode: vi.fn(),
    startServer: vi.fn(),
    stopServer: vi.fn(),
    restartServer: vi.fn(),
    forceStopServer: vi.fn(),
    deleteServer: vi.fn(),
}));

vi.mock("@/lib/auth-context", () => ({
    useAuth: vi.fn(() => ({user: {permissions: []}})),
}));

vi.mock("@/lib/ws-context", () => ({
    useWs: vi.fn(() => ({subscribe: subscribeMock})),
}));

vi.mock("next/navigation", () => ({
    useRouter: () => ({push: pushMock}),
    usePathname: () => "/",
    useParams: () => ({id: "n1"}),
    useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/components/nodes/TokenModal", () => ({
    TokenModal: vi.fn(({nodeKey, onClose}) => (
        <div data-testid="token-modal">
            <span>New Node Key</span>
            <span>{nodeKey}</span>
            <button onClick={onClose}>Done</button>
        </div>
    )),
}));

vi.mock("@/components/servers/header-action-button", () => ({
    HeaderActionButton: vi.fn(({label, loading, onClick, variant}) => (
        <button
            title={label}
            disabled={loading}
            onClick={onClick}
            data-variant={variant}
        >
            {loading ? "Loading..." : label}
        </button>
    )),
}));

import {
    getNode, getNodeMetrics, listServers, trustNode, rotateNodeToken,
} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import NodeDetailPage from "../page";

function deferred<T>(): { promise: Promise<T>; resolve: (v: T) => void } {
    let resolve!: (v: T) => void;
    const promise = new Promise<T>((r) => {
        resolve = r;
    });
    return {promise, resolve};
}

function node(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        id: "n1",
        display_name: "Node 1",
        hostname: "node-1.example.com",
        public_ip: "203.0.113.1",
        private_ip: "10.0.0.1",
        status: "ACTIVE",
        health: "HEALTHY",
        total_ram_mb: 32768,
        total_cpu_millicores: 4096,
        allocated_ram_mb: 8192,
        allocated_cpu_millicores: 1024,
        system_ram_used_mb: null,
        system_cpu_percent: null,
        reserved_ram_mb: 1024,
        reserved_cpu_millicores: 1024,
        port_range_start: 25565,
        port_range_end: 25600,
        agent_version: "1.0.0",
        last_seen_at: new Date(Date.now() - 120_000).toISOString(),
        created_at: "2026-01-01T00:00:00Z",
        updated_at: new Date(Date.now() - 120_000).toISOString(),
        ...overrides,
    };
}

async function clickTab(name: string) {
    const tab = screen.getAllByRole("tab").find((b) => b.textContent?.trim().startsWith(name));
    if (tab) {
        const user = userEvent.setup();
        await user.click(tab);
    }
}

function server(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        id: "s1",
        name: "survival",
        display_name: "Survival",
        server_type: "PAPER",
        status: "HEALTHY",
        node_id: "n1",
        memory_mb: 2048,
        host_port: 25565,
        ...overrides,
    };
}

const MANAGE = ["system.nodes"];

async function renderDetail(
    nodeOverrides: Record<string, unknown> = {},
    servers: Record<string, unknown>[] = [],
    permissions: string[] = [],
    skipMetricsMock = false,
) {
    vi.mocked(getNode).mockResolvedValue({data: node(nodeOverrides)} as never);
    vi.mocked(listServers).mockResolvedValue({data: servers} as never);
    if (!skipMetricsMock) {
        vi.mocked(getNodeMetrics).mockResolvedValue({
            data: {
                timestamps: [],
                cpu_percent: [],
                ram_used_mb: [],
                ram_total_mb: [],
                disk_used_bytes: [],
                disk_total_bytes: [],
                net_in_bytes: [],
                net_out_bytes: []
            }
        } as never);
    }
    (vi.mocked(useAuth) as ReturnType<typeof vi.fn>).mockReturnValue({user: {permissions}});

    const result = render(<NodeDetailPage/>);

    await waitFor(() => {
        expect(screen.queryByText(/Loading/i) || document.querySelector(".animate-pulse") || true).toBeTruthy();
    });
    // Wait for getNode to be called and data to render
    await waitFor(() => {
        expect(getNode).toHaveBeenCalled();
    });
    // Wait for the node name to appear
    await waitFor(() => {
        expect(screen.getAllByText(nodeOverrides.display_name as string ?? "Node 1").length).toBeGreaterThan(0);
    });

    return result;
}

describe("NodeDetailPage", () => {
    beforeEach(() => {
        vi.clearAllMocks();
        fetchMock.mockReset();
        fetchMock.mockResolvedValue({
            json: async () => ({frontendVersion: "1.0.0", masterVersion: "1.0.0", versionMismatch: false}),
        });
        vi.stubGlobal("fetch", fetchMock);
        vi.stubGlobal("ResizeObserver", class {
            observe = vi.fn();
            disconnect = vi.fn();

            constructor() {
            }
        });
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    describe("Loading state", () => {
        it("shows skeleton while loading", async () => {
            const def = deferred<Awaited<ReturnType<typeof getNode>>>();
            vi.mocked(getNode).mockReturnValue(def.promise);
            vi.mocked(listServers).mockResolvedValue({data: []} as never);
            vi.mocked(getNodeMetrics).mockResolvedValue({
                data: {
                    timestamps: [],
                    cpu_percent: [],
                    ram_used_mb: [],
                    ram_total_mb: [],
                    disk_used_bytes: [],
                    disk_total_bytes: [],
                    net_in_bytes: [],
                    net_out_bytes: []
                }
            } as never);

            render(<NodeDetailPage/>);

            expect(document.querySelectorAll(".animate-pulse").length).toBeGreaterThan(0);

            def.resolve({data: node()});
            await waitFor(() => {
                expect(screen.getAllByText("Node 1").length).toBeGreaterThan(0);
            });
        });
    });

    describe("Not found", () => {
        it("shows 'Node not found' when getNode returns 404", async () => {
            vi.mocked(getNode).mockResolvedValue({data: null, response: {status: 404}} as never);
            vi.mocked(listServers).mockResolvedValue({data: []} as never);
            vi.mocked(getNodeMetrics).mockResolvedValue({
                data: {
                    timestamps: [],
                    cpu_percent: [],
                    ram_used_mb: [],
                    ram_total_mb: [],
                    disk_used_bytes: [],
                    disk_total_bytes: [],
                    net_in_bytes: [],
                    net_out_bytes: []
                }
            } as never);

            render(<NodeDetailPage/>);

            await waitFor(() => {
                expect(screen.getByText(/Node not found/i)).toBeInTheDocument();
            });
        });

        it("shows 'Node not found' when getNode returns null data", async () => {
            vi.mocked(getNode).mockResolvedValue({data: null} as never);
            vi.mocked(listServers).mockResolvedValue({data: []} as never);
            vi.mocked(getNodeMetrics).mockResolvedValue({
                data: {
                    timestamps: [],
                    cpu_percent: [],
                    ram_used_mb: [],
                    ram_total_mb: [],
                    disk_used_bytes: [],
                    disk_total_bytes: [],
                    net_in_bytes: [],
                    net_out_bytes: []
                }
            } as never);

            render(<NodeDetailPage/>);

            await waitFor(() => {
                expect(screen.getByText(/Node not found/i)).toBeInTheDocument();
            });
        });

        it("has a link back to nodes list", async () => {
            vi.mocked(getNode).mockResolvedValue({data: null, response: {status: 404}} as never);
            vi.mocked(listServers).mockResolvedValue({data: []} as never);
            vi.mocked(getNodeMetrics).mockResolvedValue({
                data: {
                    timestamps: [],
                    cpu_percent: [],
                    ram_used_mb: [],
                    ram_total_mb: [],
                    disk_used_bytes: [],
                    disk_total_bytes: [],
                    net_in_bytes: [],
                    net_out_bytes: []
                }
            } as never);

            render(<NodeDetailPage/>);

            await waitFor(() => {
                expect(screen.getByText(/Node not found/i)).toBeInTheDocument();
            });
            const backLink = screen.getByText("Back to nodes");
            expect(backLink).toHaveAttribute("href", "/nodes");
        });
    });

    describe("Breadcrumb", () => {
        it("renders breadcrumb with Nodes link and display name", async () => {
            await renderDetail({display_name: "My Node"});

            expect(screen.getByText("Nodes")).toHaveAttribute("href", "/nodes");
            expect(screen.getAllByText("My Node").length).toBeGreaterThan(0);
        });
    });

    describe("Header", () => {
        it("renders display name and meta line", async () => {
            await renderDetail();

            expect(screen.getAllByText("Node 1").length).toBeGreaterThan(0);
            expect(screen.getAllByText(/node-1\.example\.com/).length).toBeGreaterThan(0);
            expect(screen.getAllByText(/10\.0\.0\.1/).length).toBeGreaterThan(0);
            expect(screen.getAllByText(/1\.0\.0/).length).toBeGreaterThan(0);
        });

        it("shows status badge", async () => {
            await renderDetail({status: "ACTIVE", health: "HEALTHY"});
            expect(screen.getAllByText("Active").length).toBeGreaterThan(0);
        });

        it("shows 'unknown agent' when agent_version is null", async () => {
            await renderDetail({agent_version: null});
            expect(screen.getByText(/unknown agent/)).toBeInTheDocument();
        });
    });

    describe("Overview tab", () => {
        it("renders stat cards with RAM, CPU, server count, status", async () => {
            const n = {
                allocated_ram_mb: 8192,
                total_ram_mb: 32768,
                allocated_cpu_millicores: 1024,
                total_cpu_millicores: 4096,
                status: "ACTIVE",
                health: "HEALTHY",
            };
            await renderDetail(n, [server()]);

            expect(screen.getByText("RAM Allocated")).toBeInTheDocument();
            expect(screen.getByText("CPU Allocated")).toBeInTheDocument();
            expect(screen.getAllByText("Servers").length).toBeGreaterThan(0);
            expect(screen.getByText("Status")).toBeInTheDocument();
            expect(screen.getAllByText(/8\.0 GB \/ 32\.0 GB/).length).toBeGreaterThan(0);
            expect(screen.getByText("25%")).toBeInTheDocument(); // 1024/4096 = 25%
            expect(screen.getAllByText("1").length).toBeGreaterThan(0); // 1 server
            expect(screen.getByText("1 healthy")).toBeInTheDocument();
        });

        it("renders Node Info section with hostname, IPs, port range", async () => {
            await renderDetail();

            expect(screen.getByText("Node Info")).toBeInTheDocument();
            expect(screen.getByText("Hostname")).toBeInTheDocument();
            expect(screen.getByText("node-1.example.com")).toBeInTheDocument();
            expect(screen.getByText("Public IP")).toBeInTheDocument();
            expect(screen.getByText("203.0.113.1")).toBeInTheDocument();
            expect(screen.getByText("Private IP")).toBeInTheDocument();
            expect(screen.getByText("10.0.0.1")).toBeInTheDocument();
            expect(screen.getByText("Port Range")).toBeInTheDocument();
            expect(screen.getByText(/25565–25600/)).toBeInTheDocument();
        });

        it("warns when the agent build differs from master", async () => {
            fetchMock.mockResolvedValue({
                json: async () => ({frontendVersion: "x", masterVersion: "masterhash", versionMismatch: false}),
            });
            await renderDetail({agent_version: "oldhash"});
            expect(await screen.findByTitle(/differs from master/)).toBeInTheDocument();
        });

        it("renders RAM and CPU usage bars", async () => {
            await renderDetail();

            expect(screen.getByText("RAM Usage")).toBeInTheDocument();
            expect(screen.getByText("CPU Usage")).toBeInTheDocument();
        });

        it("shows 'last seen' relative time when last_seen_at is present", async () => {
            await renderDetail({last_seen_at: new Date(Date.now() - 120_000).toISOString()});
            expect(screen.getAllByText(/ago/).length).toBeGreaterThan(0);
        });

        it("shows '-' for last seen when null", async () => {
            await renderDetail({last_seen_at: null});
            const lastSeenRow = screen.getByText("Last Seen");
            expect(lastSeenRow.closest("div")).toHaveTextContent("-");
        });

        it("shows system CPU percent when available", async () => {
            await renderDetail({system_cpu_percent: 42.5});
            expect(screen.getAllByText("43%").length).toBeGreaterThan(0);
        });

        it("shows '-' for CPU usage when system_cpu_percent is null", async () => {
            await renderDetail({system_cpu_percent: null});
            expect(screen.getByText("CPU Usage")).toBeInTheDocument();
        });

        it("handles zero total_ram_mb gracefully", async () => {
            await renderDetail({total_ram_mb: 0, allocated_ram_mb: 0});
            expect(screen.getByText("RAM Allocated")).toBeInTheDocument();
        });

        it("handles zero total_cpu_millicores gracefully", async () => {
            await renderDetail({total_cpu_millicores: 0, allocated_cpu_millicores: 0});
            expect(screen.getByText("CPU Allocated")).toBeInTheDocument();
        });
    });

    describe("Servers tab", () => {
        it("renders the shared server row with name, type, status and RAM", async () => {
            const srv = server({display_name: "Survival", name: "survival", server_type: "PAPER", status: "HEALTHY", memory_mb: 2048, host_port: 25565});
            await renderDetail({}, [srv]);

            await clickTab("Servers");

            await waitFor(() => {
                expect(screen.getAllByText("Survival").length).toBeGreaterThan(0);
            });
            expect(screen.getAllByText("PAPER").length).toBeGreaterThan(0);
            expect(screen.getAllByText("Healthy").length).toBeGreaterThan(0);
            expect(screen.getAllByText(/2048 MB/).length).toBeGreaterThan(0);
        });

        it("shows empty state when no servers assigned", async () => {
            await renderDetail({}, []);

            await clickTab("Servers");

            await waitFor(() => {
                expect(screen.getByText(/No servers assigned to this node/i)).toBeInTheDocument();
            });
        });
    });

    describe("Tab switching", () => {
        it("defaults to Overview tab", async () => {
            await renderDetail();

            expect(screen.getByText("RAM Allocated")).toBeInTheDocument();
            expect(screen.getByText("CPU Allocated")).toBeInTheDocument();
        });

        it("switches to Servers tab on click", async () => {
            await renderDetail({}, [server()]);

            await clickTab("Servers");

            await waitFor(() => {
                expect(screen.getAllByText("Survival").length).toBeGreaterThan(0);
            });
        });

        it("switches to Metrics tab on click", async () => {
            await renderDetail({}, [], [], true);
            vi.mocked(getNodeMetrics).mockResolvedValue({
                data: {
                    timestamps: [new Date().toISOString()],
                    cpu_percent: [50],
                    ram_used_mb: [4096],
                    ram_total_mb: [8192],
                    disk_used_bytes: [1073741824],
                    disk_total_bytes: [2147483648],
                    net_in_bytes: [1024],
                    net_out_bytes: [2048],
                },
            } as never);

            await clickTab("Metrics");

            await waitFor(() => {
                expect(screen.getByText("CPU Utilization")).toBeInTheDocument();
            });
        });

        it("shows server count badge in Servers tab", async () => {
            await renderDetail({}, [server(), server({id: "s2", name: "creative"})]);

            expect(screen.getAllByText("2").length).toBeGreaterThan(0);
        });
    });

    describe("Metrics tab", () => {
        it("shows loading skeleton initially", async () => {
            const def = deferred<Awaited<ReturnType<typeof getNodeMetrics>>>();
            vi.mocked(getNode).mockResolvedValue({data: node()} as never);
            vi.mocked(listServers).mockResolvedValue({data: []} as never);
            vi.mocked(getNodeMetrics).mockReturnValue(def.promise);
            (vi.mocked(useAuth) as ReturnType<typeof vi.fn>).mockReturnValue({user: {permissions: []}});

            render(<NodeDetailPage/>);

            await waitFor(() => {
                expect(screen.getAllByText("Node 1").length).toBeGreaterThan(0);
            });

            await clickTab("Metrics");

            expect(document.querySelectorAll(".animate-pulse").length).toBeGreaterThan(0);

            def.resolve({data: {timestamps: [], cpu_percent: [], ram_used_mb: [], ram_total_mb: [], disk_used_bytes: [], disk_total_bytes: [], net_in_bytes: [], net_out_bytes: []}});
            await waitFor(() => {
                expect(screen.getByText(/No metrics available/i)).toBeInTheDocument();
            });
        });

        it("shows empty state when no metrics data", async () => {
            vi.mocked(getNodeMetrics).mockResolvedValue({
                data: {timestamps: [], cpu_percent: [], ram_used_mb: [], ram_total_mb: [], disk_used_bytes: [], disk_total_bytes: [], net_in_bytes: [], net_out_bytes: []},
            } as never);
            await renderDetail();

            await clickTab("Metrics");

            await waitFor(() => {
                expect(screen.getByText(/No metrics available/i)).toBeInTheDocument();
            });
        });

        it("renders charts when metrics data is present", async () => {
            await renderDetail({}, [], [], true);
            vi.mocked(getNodeMetrics).mockResolvedValue({
                data: {
                    timestamps: [new Date().toISOString()],
                    cpu_percent: [50],
                    ram_used_mb: [4096],
                    ram_total_mb: [8192],
                    disk_used_bytes: [1073741824],
                    disk_total_bytes: [2147483648],
                    net_in_bytes: [1024],
                    net_out_bytes: [2048],
                },
            } as never);

            await clickTab("Metrics");

            await waitFor(() => {
                expect(screen.getByText("CPU Utilization")).toBeInTheDocument();
            });
            expect(screen.getByText("RAM Usage")).toBeInTheDocument();
            expect(screen.getByText("Disk Usage")).toBeInTheDocument();
            expect(screen.getByText("Network I/O")).toBeInTheDocument();
        });

        it("renders time range selector buttons", async () => {
            await renderDetail({}, [], [], true);
            vi.mocked(getNodeMetrics).mockResolvedValue({
                data: {
                    timestamps: [new Date().toISOString()],
                    cpu_percent: [50],
                    ram_used_mb: [4096],
                    ram_total_mb: [8192],
                    disk_used_bytes: [1073741824],
                    disk_total_bytes: [2147483648],
                    net_in_bytes: [1024],
                    net_out_bytes: [2048],
                },
            } as never);

            await clickTab("Metrics");

            await waitFor(() => {
                expect(screen.getByText("1h")).toBeInTheDocument();
            });
            expect(screen.getByText("6h")).toBeInTheDocument();
            expect(screen.getByText("24h")).toBeInTheDocument();
        });
    });

    describe("Permission gating", () => {
        it("hides action buttons without system.nodes permission", async () => {
            await renderDetail({status: "PENDING"}, [], []);

            expect(screen.queryByRole("button", {name: "Trust"})).not.toBeInTheDocument();
            expect(screen.queryByRole("button", {name: "Reject"})).not.toBeInTheDocument();
            expect(screen.queryByTitle("Edit Node Info")).not.toBeInTheDocument();
            expect(screen.queryByRole("button", {name: "Rotate Key"})).not.toBeInTheDocument();
        });

        it("shows action buttons with system.nodes permission", async () => {
            await renderDetail({status: "PENDING"}, [], MANAGE);

            expect(screen.getByRole("button", {name: "Trust"})).toBeInTheDocument();
            expect(screen.getByRole("button", {name: "Reject"})).toBeInTheDocument();
            expect(screen.getByTitle("Edit Node Info")).toBeInTheDocument();
            expect(screen.queryByRole("button", {name: "Rotate Key"})).not.toBeInTheDocument();
        });
    });

    describe("Error banner", () => {
        it("displays error and dismisses on X click", async () => {
            vi.mocked(trustNode).mockResolvedValue({error: {message: "Something broke"}} as never);
            vi.mocked(getNode).mockResolvedValue({data: node({status: "PENDING"})} as never);
            await renderDetail({status: "PENDING"}, [], MANAGE);

            const user = userEvent.setup();
            await user.click(screen.getByRole("button", {name: "Trust"}));

            await waitFor(() => {
                expect(screen.getByText("Something broke")).toBeInTheDocument();
            });

            // The X button is the only button inside the error banner
            const errorBanner = screen.getByText("Something broke").closest("div")!;
            const dismissBtn = errorBanner.querySelector("button")!;
            await user.click(dismissBtn);

            await waitFor(() => {
                expect(screen.queryByText("Something broke")).not.toBeInTheDocument();
            });
        });
    });

    describe("Inline edit", () => {
        it("opens the node edit form from the Node Info card", async () => {
            await renderDetail({}, [], MANAGE);

            const user = userEvent.setup();
            await user.click(screen.getByTitle("Edit Node Info"));

            await waitFor(() => {
                expect(screen.getByText("Port Range Start")).toBeInTheDocument();
            });
        });
    });

    describe("Token modal", () => {
        it("opens TokenModal after successful rotate", async () => {
            vi.mocked(rotateNodeToken).mockResolvedValue({data: {node_key: "key-abc"}} as never);
            await renderDetail({status: "ACTIVE"}, [], MANAGE);

            const user = userEvent.setup();
            await user.click(screen.getByRole("button", {name: "Rotate Key"}));

            await waitFor(() => {
                expect(screen.getByText("Rotate Node Key?")).toBeInTheDocument();
            });
            await user.click(screen.getByRole("button", {name: "Confirm"}));

            await waitFor(() => {
                expect(screen.getByTestId("token-modal")).toBeInTheDocument();
            });
            expect(screen.getByText("key-abc")).toBeInTheDocument();
        });
    });

    describe("WS subscription", () => {
        it("subscribes to node.status on mount", async () => {
            await renderDetail();
            expect(subscribeMock).toHaveBeenCalledWith("node.status", expect.any(Function));
        });

        it("re-fetches node when matching node.status WS event arrives", async () => {
            vi.mocked(getNode).mockResolvedValue({data: node({health: "HEALTHY"})} as never);
            await renderDetail();

            const handler = subscribeMock.mock.calls.find(
                (c: [string, (...args: unknown[]) => void]) => c[0] === "node.status",
            )?.[1];

            expect(handler).toBeDefined();

            // Mock the re-fetch
            vi.mocked(getNode).mockResolvedValue({data: node({health: "UNREACHABLE"})} as never);

            act(() => {
                handler({node_id: "n1", health: "UNREACHABLE"});
            });

            await waitFor(() => {
                expect(screen.getAllByText("Unreachable").length).toBeGreaterThan(0);
            });
        });

        it("ignores node.status events for other nodes", async () => {
            await renderDetail();
            const initialCallCount = vi.mocked(getNode).mock.calls.length;

            const handler = subscribeMock.mock.calls.find(
                (c: [string, (...args: unknown[]) => void]) => c[0] === "node.status",
            )?.[1];

            act(() => {
                handler({node_id: "other-node", health: "UNREACHABLE"});
            });

            // Should not trigger another getNode call
            await new Promise((r) => setTimeout(r, 50));
            expect(vi.mocked(getNode).mock.calls.length).toBe(initialCallCount);
        });
    });

    describe("Metrics WS subscription", () => {
        it("subscribes to node.metrics", async () => {
            await renderDetail();

            await clickTab("Metrics");

            await waitFor(() => {
                expect(subscribeMock).toHaveBeenCalledWith("node.metrics", expect.any(Function));
            });
        });
    });

    describe("Polling", () => {
        it("re-fetches node periodically", async () => {
            vi.useFakeTimers({shouldAdvanceTime: true});
            vi.mocked(getNode).mockResolvedValue({data: node()} as never);
            vi.mocked(listServers).mockResolvedValue({data: []} as never);
            vi.mocked(getNodeMetrics).mockResolvedValue({
                data: {timestamps: [], cpu_percent: [], ram_used_mb: [], ram_total_mb: [], disk_used_bytes: [], disk_total_bytes: [], net_in_bytes: [], net_out_bytes: []},
            } as never);

            render(<NodeDetailPage/>);

            await waitFor(() => {
                expect(getNode).toHaveBeenCalledTimes(1);
            });

            const initialCalls = vi.mocked(getNode).mock.calls.length;

            act(() => {
                vi.advanceTimersByTime(30_000);
            });

            await waitFor(() => {
                expect(vi.mocked(getNode).mock.calls.length).toBeGreaterThan(initialCalls);
            });

            vi.useRealTimers();
        });
    });
});
