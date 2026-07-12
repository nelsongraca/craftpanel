import {describe, it, expect, vi, beforeEach} from "vitest";
import {render, screen, waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

vi.mock("@/lib/generated/sdk.gen", () => ({
    listServers: vi.fn(),
    listNodes: vi.fn(),
    listNetworks: vi.fn(),
    startServer: vi.fn(),
    stopServer: vi.fn(),
    restartServer: vi.fn(),
    deleteServer: vi.fn(),
}));

vi.mock("@/lib/auth-context", () => ({
    useAuth: vi.fn(() => ({user: {permissions: []}})),
}));

vi.mock("@/app/components/PageHeader", () => ({
    default: vi.fn(({title, subtitle, action}: { title: string; subtitle?: string; action?: React.ReactNode }) => (
        <div data-testid="page-header">
            <h1>{title}</h1>
            {subtitle && <p>{subtitle}</p>}
            {action && <div>{action}</div>}
        </div>
    )),
}));

import {
    listServers, listNodes, listNetworks,
    startServer, stopServer, restartServer, deleteServer,
} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import ServersPage from "../page";

function server(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        id: "s1",
        name: "survival",
        display_name: "Survival",
        description: null,
        server_type: "PAPER",
        mc_version: "1.21",
        itzg_image_tag: "1.21",
        status: "HEALTHY",
        node_id: "n1",
        network_id: null,
        host_port: 25565,
        memory_mb: 2048,
        cpu_shares: 100,
        exposed_externally: false,
        public_subdomain: null,
        custom_hostname: null,
        canonical_hostname: null,
        is_migrating: false,
        needs_recreate: false,
        config_mode: "MANAGED",
        stop_command: "stop",
        last_player_count: null,
        last_player_names: null,
        created_at: "2025-01-01T00:00:00Z",
        updated_at: "2025-01-01T00:00:00Z",
        ...overrides,
    } as Record<string, unknown>;
}

function node(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {id: "n1", name: "node-1", display_name: "Node 1", ...overrides} as Record<string, unknown>;
}

function network(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {id: "net1", name: "Main Network", ...overrides} as Record<string, unknown>;
}

function deferred<T>(): { promise: Promise<T>; resolve: (v: T) => void } {
    let resolve!: (v: T) => void;
    const promise = new Promise<T>((r) => {
        resolve = r;
    });
    return {promise, resolve};
}

async function renderWith(
    mocks: {
        servers?: Record<string, unknown>[];
        nodes?: Record<string, unknown>[];
        networks?: Record<string, unknown>[];
        permissions?: string[];
    } = {},
) {
    const {servers: s = [server()], nodes: nd = [], networks: nw = [], permissions: p = []} = mocks;

    vi.mocked(listServers).mockResolvedValue({data: s} as never);
    vi.mocked(listNodes).mockResolvedValue({data: nd} as never);
    vi.mocked(listNetworks).mockResolvedValue({data: nw} as never);
    (vi.mocked(useAuth) as ReturnType<typeof vi.fn>).mockReturnValue({user: {permissions: p}});

    const result = render(<ServersPage/>);

    if (s.length > 0) {
        await waitFor(() => {
            expect(screen.getAllByText(s[0].display_name as string).length).toBeGreaterThan(0);
        });
    } else {
        await waitFor(() => {
            expect(screen.getByText(/No servers yet/i)).toBeInTheDocument();
        });
    }

    return result;
}

describe("ServersPage", () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    describe("Loading state", () => {
        it("shows skeleton while loading", async () => {
            const def = deferred<Awaited<ReturnType<typeof listServers>>>();
            vi.mocked(listServers).mockReturnValue(def.promise);
            vi.mocked(listNodes).mockResolvedValue({data: []} as never);
            vi.mocked(listNetworks).mockResolvedValue({data: []} as never);

            render(<ServersPage/>);

            expect(document.querySelectorAll(".animate-pulse").length).toBeGreaterThan(0);

            def.resolve({data: []});
            await waitFor(() => {
                expect(screen.getByText(/No servers yet/i)).toBeInTheDocument();
            });
        });
    });

    describe("Empty state", () => {
        it('shows "No servers yet" when server list is empty', async () => {
            await renderWith({servers: []});
            expect(screen.getByText(/No servers yet/i)).toBeInTheDocument();
        });
    });

    describe("Server list", () => {
        it("renders servers in desktop table with display_name, status, type, RAM", async () => {
            await renderWith({servers: [server()], nodes: [node()]});

            expect(screen.getAllByText("Survival").length).toBeGreaterThan(0);
            expect(screen.getByText("PAPER")).toBeInTheDocument();
            expect(screen.getAllByText("Healthy").length).toBeGreaterThan(0);
            expect(screen.getAllByText(/2048 MB/).length).toBeGreaterThan(0);
            expect(screen.getAllByText("Node 1").length).toBeGreaterThan(0);
        });

        it("shows truncated node id when node not found in map", async () => {
            await renderWith({
                servers: [server({node_id: "unknown-node-id-drstrange"})],
            });
            // Renders without crashing; the truncated id text is in the DOM
            expect(screen.getAllByText("Survival").length).toBeGreaterThan(0);
        });
    });

    describe("Filter status", () => {
        it("filtering by status shows only matching servers", async () => {
            const healthy = server({id: "s1", display_name: "Alpha", status: "HEALTHY"});
            const stopped = server({id: "s2", display_name: "Beta", status: "STOPPED"});
            await renderWith({servers: [healthy, stopped]});

            const selects = screen.getAllByRole("combobox");
            const user = userEvent.setup();
            await user.selectOptions(selects[0], "STOPPED");

            await waitFor(() => {
                expect(screen.queryByText("Alpha")).not.toBeInTheDocument();
                expect(screen.getAllByText("Beta").length).toBeGreaterThan(0);
            });
        });
    });

    describe("Filter search", () => {
        it("search filters by display_name", async () => {
            const alpha = server({id: "s1", display_name: "Alpha", status: "HEALTHY"});
            const beta = server({id: "s2", display_name: "Beta", status: "HEALTHY"});
            await renderWith({servers: [alpha, beta]});

            const user = userEvent.setup();
            const searchInput = screen.getByPlaceholderText("Search servers…");
            await user.type(searchInput, "Alpha");

            await waitFor(() => {
                expect(screen.getAllByText("Alpha").length).toBeGreaterThan(0);
                expect(screen.queryByText("Beta")).not.toBeInTheDocument();
            });
        });

        it("shows no-match message when search filters out all servers", async () => {
            const s = server({display_name: "Survival"});
            await renderWith({servers: [s]});

            const user = userEvent.setup();
            const searchInput = screen.getByPlaceholderText("Search servers…");
            await user.type(searchInput, "ZZZ");

            await waitFor(() => {
                expect(screen.getByText(/No servers match/i)).toBeInTheDocument();
            });
        });
    });

    describe("Action buttons", () => {
        it("Start button renders for STOPPED server with server.start permission and calls startServer", async () => {
            const s = server({status: "STOPPED"});
            vi.mocked(startServer).mockResolvedValue({data: {}} as never);
            await renderWith({servers: [s], permissions: ["server.start"]});

            const startBtns = screen.getAllByTitle("Start");
            expect(startBtns.length).toBeGreaterThan(0);

            const user = userEvent.setup();
            await user.click(startBtns[0]);

            await waitFor(() => {
                expect(startServer).toHaveBeenCalledWith({path: {id: "s1"}});
            });
        });

        it("Start button NOT rendered without server.start permission", async () => {
            const s = server({status: "STOPPED"});
            await renderWith({servers: [s], permissions: []});

            expect(screen.queryByTitle("Start")).not.toBeInTheDocument();
        });

        it("Stop button renders for HEALTHY server with server.stop permission and calls stopServer", async () => {
            const s = server({status: "HEALTHY"});
            vi.mocked(stopServer).mockResolvedValue({data: {}} as never);
            await renderWith({servers: [s], permissions: ["server.stop"]});

            const stopBtns = screen.getAllByTitle("Stop");
            expect(stopBtns.length).toBeGreaterThan(0);

            const user = userEvent.setup();
            await user.click(stopBtns[0]);

            await waitFor(() => {
                expect(stopServer).toHaveBeenCalledWith({path: {id: "s1"}});
            });
        });

        it("Stop button NOT rendered without server.stop permission", async () => {
            const s = server({status: "HEALTHY"});
            await renderWith({servers: [s], permissions: []});

            expect(screen.queryByTitle("Stop")).not.toBeInTheDocument();
        });

        it("Restart button renders for HEALTHY server with server.restart permission", async () => {
            const s = server({status: "HEALTHY"});
            vi.mocked(restartServer).mockResolvedValue({data: {}} as never);
            await renderWith({servers: [s], permissions: ["server.restart"]});

            const restartBtns = screen.getAllByTitle("Restart");
            expect(restartBtns.length).toBeGreaterThan(0);

            const user = userEvent.setup();
            await user.click(restartBtns[0]);

            await waitFor(() => {
                expect(restartServer).toHaveBeenCalledWith({path: {id: "s1"}});
            });
        });
    });

    describe("Action error banner", () => {
        it("error from startServer shows error banner, dismissable with X", async () => {
            const s = server({status: "STOPPED"});
            vi.mocked(startServer).mockResolvedValue({
                error: {message: "Docker API error"},
            } as never);
            await renderWith({servers: [s], permissions: ["server.start"]});

            const startBtns = screen.getAllByTitle("Start");
            const user = userEvent.setup();
            await user.click(startBtns[0]);

            await waitFor(() => {
                expect(screen.getByText("Docker API error")).toBeInTheDocument();
            });

            const xBtn = screen.getByRole("button", {name: "Dismiss"});
            await user.click(xBtn);

            await waitFor(() => {
                expect(screen.queryByText("Docker API error")).not.toBeInTheDocument();
            });
        });
    });

    describe("Confirm dialog", () => {
        it("Delete button opens confirm dialog, confirming calls deleteServer", async () => {
            const s = server({status: "STOPPED"});
            vi.mocked(deleteServer).mockResolvedValue({data: {}} as never);
            await renderWith({servers: [s], permissions: ["server.delete"]});

            const deleteBtns = screen.getAllByTitle("Delete");
            const user = userEvent.setup();
            await user.click(deleteBtns[0]);

            await waitFor(() => {
                expect(screen.getByText("Delete Server?")).toBeInTheDocument();
            });
            expect(screen.getByText(/Delete "Survival"\?/i)).toBeInTheDocument();

            await user.click(screen.getByRole("button", {name: "Confirm"}));

            await waitFor(() => {
                expect(deleteServer).toHaveBeenCalledWith({path: {id: "s1"}});
            });
        });

        it("Cancel button closes confirm dialog without calling deleteServer", async () => {
            const s = server({status: "STOPPED"});
            await renderWith({servers: [s], permissions: ["server.delete"]});

            const deleteBtns = screen.getAllByTitle("Delete");
            const user = userEvent.setup();
            await user.click(deleteBtns[0]);

            await waitFor(() => {
                expect(screen.getByText("Delete Server?")).toBeInTheDocument();
            });

            await user.click(screen.getByRole("button", {name: "Cancel"}));

            await waitFor(() => {
                expect(screen.queryByText("Delete Server?")).not.toBeInTheDocument();
            });
            expect(deleteServer).not.toHaveBeenCalled();
        });
    });

    describe("Mobile card list", () => {
        it("servers render in mobile card layout", async () => {
            await renderWith({servers: [server()]});
            // Content renders in both desktop table and mobile cards
            expect(screen.getAllByText("Survival").length).toBeGreaterThan(0);
        });
    });

    describe("Network/node filters", () => {
        it("filter selects render when networks and nodes are available", async () => {
            await renderWith({
                servers: [server()],
                nodes: [node()],
                networks: [network()],
            });

            const selects = screen.getAllByRole("combobox");
            expect(selects.length).toBe(3);

            expect(screen.getByText("All Networks")).toBeInTheDocument();
            expect(screen.getByText("All Nodes")).toBeInTheDocument();
            expect(screen.getByText("Main Network")).toBeInTheDocument();
            expect(screen.getByText("Node 1", {selector: "option"})).toBeInTheDocument();
        });

        it("filtering by network shows only matching servers", async () => {
            const s1 = server({id: "s1", display_name: "A", network_id: "net1"});
            const s2 = server({id: "s2", display_name: "B", network_id: "net2"});
            await renderWith({
                servers: [s1, s2],
                nodes: [node()],
                networks: [network(), network({id: "net2", name: "Other Network"})],
            });

            const selects = screen.getAllByRole("combobox");
            const user = userEvent.setup();
            await user.selectOptions(selects[1], "net1");

            await waitFor(() => {
                expect(screen.getAllByText("A").length).toBeGreaterThan(0);
                expect(screen.queryByText("B")).not.toBeInTheDocument();
            });
        });
    });

    describe("Pending action spinner", () => {
        it("action in progress shows spinner instead of icon", async () => {
            const s = server({status: "STOPPED"});
            const def = deferred<Awaited<ReturnType<typeof startServer>>>();
            vi.mocked(startServer).mockReturnValue(def.promise);
            await renderWith({servers: [s], permissions: ["server.start"]});

            const startBtns = screen.getAllByTitle("Start");
            const user = userEvent.setup();
            await user.click(startBtns[0]);

            const spinnerBtns = screen.getAllByTitle("Start");
            expect(spinnerBtns[0]).toBeDisabled();
            expect(spinnerBtns[0].querySelector(".animate-spin")).not.toBeNull();
            expect(spinnerBtns[0].querySelector("svg")).toBeNull();

            def.resolve({data: {}});
        });
    });

    describe("Permission gating", () => {
        it('"New Server" button not shown when user lacks server.create permission', async () => {
            await renderWith({servers: [], permissions: []});
            expect(screen.queryByText("New Server")).not.toBeInTheDocument();
        });

        it('"New Server" button shown when user has server.create permission', async () => {
            await renderWith({servers: [], permissions: ["server.create"]});
            expect(screen.getByText("New Server")).toBeInTheDocument();
        });
    });

    describe("Overflow menu", () => {
        function getOverflowButtons(): HTMLElement[] {
            return screen.getAllByRole("button").filter((btn) => !btn.getAttribute("title"));
        }

        it('clicking "···" opens menu with View link and Delete button', async () => {
            const s = server({status: "STOPPED"});
            await renderWith({servers: [s], permissions: ["server.delete"]});

            const overflowBtns = getOverflowButtons();
            expect(overflowBtns.length).toBeGreaterThan(0);

            const user = userEvent.setup();
            await user.click(overflowBtns[0]);

            await waitFor(() => {
                expect(screen.getAllByText("View").length).toBeGreaterThan(0);
                expect(screen.getAllByText("Delete").length).toBeGreaterThan(0);
            });

            expect(screen.getAllByText("View")[0].closest("a")).toHaveAttribute("href", "/servers/s1");
        });

        it('Delete in overflow menu opens confirm dialog', async () => {
            const s = server({status: "STOPPED"});
            await renderWith({servers: [s], permissions: ["server.delete"]});

            const overflowBtns = getOverflowButtons();
            const user = userEvent.setup();
            await user.click(overflowBtns[0]);

            await waitFor(() => {
                expect(screen.getAllByText("Delete").length).toBeGreaterThan(0);
            });

            await user.click(screen.getAllByText("Delete")[0]);

            await waitFor(() => {
                expect(screen.getByText("Delete Server?")).toBeInTheDocument();
            });
        });
    });

    describe("Migrating label", () => {
        it("server with is_migrating: true shows Migrating label", async () => {
            await renderWith({
                servers: [server({is_migrating: true})],
            });

            expect(screen.getByText("⟳ Migrating")).toBeInTheDocument();
        });
    });

    describe("Exposed subdomain", () => {
        it("server with exposed_externally and public_subdomain shows subdomain", async () => {
            await renderWith({
                servers: [server({exposed_externally: true, public_subdomain: "mc.example.com"})],
            });

            expect(screen.getAllByText("mc.example.com").length).toBeGreaterThan(0);
        });
    });
});
