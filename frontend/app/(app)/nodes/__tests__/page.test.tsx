import {describe, it, expect, vi, beforeEach} from "vitest";
import {render, screen, waitFor, act} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

const {subscribeMock} = vi.hoisted(() => ({
    subscribeMock: vi.fn(() => vi.fn()),
}));

vi.mock("@/lib/generated/sdk.gen", () => ({
    listNodes: vi.fn(),
    listServers: vi.fn(),
    trustNode: vi.fn(),
    rejectNode: vi.fn(),
    rotateNodeToken: vi.fn(),
    shutdownNode: vi.fn(),
    decommissionNode: vi.fn(),
    updateNode: vi.fn(),
}));

vi.mock("@/lib/auth-context", () => ({
    useAuth: vi.fn(() => ({user: {permissions: []}})),
}));

vi.mock("@/lib/ws-context", () => ({
    useWs: vi.fn(() => ({subscribe: subscribeMock})),
}));

vi.mock("@/app/components/PageHeader", () => ({
    default: vi.fn(
        ({title, subtitle}: { title: string; subtitle?: string }) => (
            <div data-testid="page-header">
                <h1>{title}</h1>
                {subtitle && <p>{subtitle}</p>}
            </div>
        ),
    ),
}));

import {
    listNodes, listServers, trustNode, rejectNode,
    rotateNodeToken, shutdownNode, decommissionNode, updateNode,
} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import NodesPage from "../page";

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
        total_cpu_shares: 4096,
        allocated_ram_mb: 8192,
        allocated_cpu_shares: 1024,
        system_ram_used_mb: null,
        port_range_start: 25565,
        port_range_end: 25600,
        agent_version: "1.0.0",
        last_seen_at: new Date(Date.now() - 120_000).toISOString(),
        created_at: "2026-01-01T00:00:00Z",
        updated_at: new Date(Date.now() - 120_000).toISOString(),
        ...overrides,
    } as Record<string, unknown>;
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
        nodes?: Record<string, unknown>[];
        servers?: Record<string, unknown>[];
        permissions?: string[];
    } = {},
) {
    const {nodes: nd = [node()], servers: s = [], permissions: p = []} = mocks;

    vi.mocked(listNodes).mockResolvedValue({data: nd} as never);
    vi.mocked(listServers).mockResolvedValue({data: s} as never);
    (vi.mocked(useAuth) as ReturnType<typeof vi.fn>).mockReturnValue({
        user: {permissions: p},
    });

    const result = render(<NodesPage/>);

    if (nd.length > 0) {
        await waitFor(() => {
            expect(
                screen.queryAllByText(nd[0].display_name as string).length,
            ).toBeGreaterThan(0);
        });
    } else {
        await waitFor(() => {
            expect(screen.queryByText(/No nodes registered/i)).toBeInTheDocument();
        });
    }

    return result;
}

function overflowButtons(): HTMLElement[] {
    return screen.getAllByRole("button").filter((btn) => !btn.getAttribute("title"));
}

async function openOverflow() {
    const btns = overflowButtons();
    const user = userEvent.setup();
    await user.click(btns[0]);
    return user;
}

async function openEdit() {
    const user = await openOverflow();
    await waitFor(() => {
        expect(screen.getAllByText("Edit").length).toBeGreaterThan(0);
    });
    await user.click(screen.getAllByText("Edit")[0]);
    await waitFor(() => {
        expect(screen.getByText("Edit Node")).toBeInTheDocument();
    });
    return user;
}

async function openMenuAndClick(label: string, user: ReturnType<typeof userEvent.setup>) {
    await waitFor(() => {
        expect(screen.getAllByText(label).length).toBeGreaterThan(0);
    });
    await user.click(screen.getAllByText(label)[0]);
}

describe("NodesPage", () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    describe("Loading state", () => {
        it("shows skeleton while loading", async () => {
            const def = deferred<Awaited<ReturnType<typeof listNodes>>>();
            vi.mocked(listNodes).mockReturnValue(def.promise);
            vi.mocked(listServers).mockResolvedValue({data: []} as never);

            render(<NodesPage/>);

            expect(document.querySelectorAll(".animate-pulse").length).toBeGreaterThan(0);

            def.resolve({data: []});
            await waitFor(() => {
                expect(screen.getByText(/No nodes registered/i)).toBeInTheDocument();
            });
        });
    });

    describe("Empty state", () => {
        it('shows "No nodes registered yet" when list is empty', async () => {
            await renderWith({nodes: []});
            expect(screen.getByText(/No nodes registered yet/i)).toBeInTheDocument();
        });
    });

    describe("Node list", () => {
        it("renders display_name, hostname, status badge, RAM/CPU bars, server count, last seen", async () => {
            const nd = node();
            await renderWith({
                nodes: [nd],
                servers: [{id: "s1", node_id: "n1"} as Record<string, unknown>],
            });

            expect(screen.getAllByText(nd.display_name as string).length).toBeGreaterThan(0);
            expect(screen.getAllByText(nd.hostname as string).length).toBeGreaterThan(0);
            expect(screen.getAllByText("Active").length).toBeGreaterThan(0);
            expect(screen.getAllByText(/8\.0 GB \/ 32\.0 GB/).length).toBeGreaterThan(0);
            expect(screen.getAllByText("1c / 4c").length).toBeGreaterThan(0);
            expect(screen.getAllByText("1").length).toBeGreaterThan(0);
            expect(screen.getAllByText(/ago/).length).toBeGreaterThan(0);
        });
    });

    describe("Status filter", () => {
        it("filter dropdown filters nodes by display status", async () => {
            const activeN = node({id: "n1", display_name: "Alpha", status: "ACTIVE", health: "HEALTHY"});
            const pendingN = node({id: "n2", display_name: "Beta", status: "PENDING", health: "HEALTHY"});
            await renderWith({nodes: [activeN, pendingN]});

            const selects = screen.getAllByRole("combobox");
            const user = userEvent.setup();
            await user.selectOptions(selects[0], "PENDING");

            await waitFor(() => {
                expect(screen.queryByText("Alpha")).not.toBeInTheDocument();
                expect(screen.getAllByText("Beta").length).toBeGreaterThan(0);
            });
        });

        it("shows no-match message when filter excludes all nodes", async () => {
            const nd = node({status: "ACTIVE"});
            await renderWith({nodes: [nd]});

            const selects = screen.getAllByRole("combobox");
            const user = userEvent.setup();
            await user.selectOptions(selects[0], "PENDING");

            await waitFor(() => {
                expect(screen.getByText(/No nodes match/i)).toBeInTheDocument();
            });
        });
    });

    describe("Pending callout", () => {
        it("shows pending node count in warning callout", async () => {
            const nd = node({id: "n1", display_name: "Pending Node", status: "PENDING"});
            await renderWith({nodes: [nd]});

            expect(screen.getByText(/1 node awaiting approval/i)).toBeInTheDocument();
        });

        it("does not show callout when no pending nodes", async () => {
            await renderWith({nodes: [node()]});
            expect(screen.queryByText(/awaiting approval/i)).not.toBeInTheDocument();
        });
    });

    describe("Error banner", () => {
        it("shows action error and dismisses on X click", async () => {
            const nd = node({id: "n1", status: "PENDING"});
            vi.mocked(trustNode).mockResolvedValue({
                error: {message: "Node registration failed"},
            } as never);
            await renderWith({
                nodes: [nd],
                permissions: ["system.nodes"],
            });

            const trustBtns = screen.getAllByTitle("Trust node");
            const user = userEvent.setup();
            await user.click(trustBtns[0]);

            await waitFor(() => {
                expect(screen.getByText("Node registration failed")).toBeInTheDocument();
            });

            const xBtn = screen.getByRole("button", {name: "Dismiss"});
            await user.click(xBtn);

            await waitFor(() => {
                expect(screen.queryByText("Node registration failed")).not.toBeInTheDocument();
            });
        });
    });

    describe("Trust button", () => {
        it("renders Trust button for PENDING node when canManage, clicking calls trustNode and reloads", async () => {
            const nd = node({id: "n1", status: "PENDING"});
            vi.mocked(trustNode).mockResolvedValue({data: {}} as never);
            await renderWith({
                nodes: [nd],
                permissions: ["system.nodes"],
            });

            const trustBtns = screen.getAllByTitle("Trust node");
            expect(trustBtns.length).toBeGreaterThan(0);

            const user = userEvent.setup();
            await user.click(trustBtns[0]);

            await waitFor(() => {
                expect(trustNode).toHaveBeenCalledWith({path: {id: "n1"}});
            });
            expect(listNodes).toHaveBeenCalledTimes(2);
        });

        it("does NOT render Trust/Reject for PENDING node without system.nodes permission", async () => {
            const nd = node({id: "n1", status: "PENDING"});
            await renderWith({nodes: [nd], permissions: []});

            expect(screen.queryByTitle("Trust node")).not.toBeInTheDocument();
            expect(screen.queryByTitle("Reject node")).not.toBeInTheDocument();
        });
    });

    describe("Reject via confirm", () => {
        it("Reject opens confirm dialog, confirming calls rejectNode", async () => {
            const nd = node({id: "n1", status: "PENDING"});
            vi.mocked(rejectNode).mockResolvedValue({data: {}} as never);
            await renderWith({
                nodes: [nd],
                permissions: ["system.nodes"],
            });

            const user = userEvent.setup();
            await user.click(screen.getAllByTitle("Reject node")[0]);

            await waitFor(() => {
                expect(screen.getByText("Reject Node?")).toBeInTheDocument();
            });
            expect(screen.getByText(/The agent will not be able to connect/i)).toBeInTheDocument();

            await user.click(screen.getByRole("button", {name: "Confirm"}));

            await waitFor(() => {
                expect(rejectNode).toHaveBeenCalledWith({path: {id: "n1"}});
            });
        });

        it("Cancel closes confirm dialog without calling rejectNode", async () => {
            const nd = node({id: "n1", status: "PENDING"});
            await renderWith({
                nodes: [nd],
                permissions: ["system.nodes"],
            });

            const user = userEvent.setup();
            await user.click(screen.getAllByTitle("Reject node")[0]);

            await waitFor(() => {
                expect(screen.getByText("Reject Node?")).toBeInTheDocument();
            });

            await user.click(screen.getByRole("button", {name: "Cancel"}));

            await waitFor(() => {
                expect(screen.queryByText("Reject Node?")).not.toBeInTheDocument();
            });
            expect(rejectNode).not.toHaveBeenCalled();
        });
    });

    describe("Overflow menu", () => {
        it('clicking ··· opens menu with Edit, Rotate Key, Shutdown, and Decommission when servers=0', async () => {
            const nd = node({id: "n1", status: "ACTIVE"});
            await renderWith({nodes: [nd]});

            expect(overflowButtons().length).toBeGreaterThan(0);

            await openOverflow();

            await waitFor(() => {
                expect(screen.getAllByText("Edit").length).toBeGreaterThan(0);
                expect(screen.getAllByText("Rotate Key").length).toBeGreaterThan(0);
                expect(screen.getAllByText("Shutdown").length).toBeGreaterThan(0);
                expect(screen.getAllByText("Decommission").length).toBeGreaterThan(0);
            });
        });

        it('Shutdown NOT shown for non-ACTIVE status', async () => {
            const nd = node({id: "n1", status: "DEGRADED", health: "DEGRADED"});
            await renderWith({nodes: [nd]});

            await openOverflow();

            await waitFor(() => {
                expect(screen.queryByText("Shutdown")).not.toBeInTheDocument();
            });
        });

        it('Decommission NOT shown when servers > 0', async () => {
            const nd = node({id: "n1", status: "ACTIVE"});
            await renderWith({
                nodes: [nd],
                servers: [{id: "s1", node_id: "n1"} as Record<string, unknown>],
            });

            await openOverflow();

            expect(screen.queryByText("Decommission")).not.toBeInTheDocument();
        });
    });

    describe("Edit modal", () => {
        it("clicking Edit opens modal, Save calls updateNode, Cancel closes without saving", async () => {
            const nd = node({id: "n1", display_name: "Node 1"});
            vi.mocked(updateNode).mockResolvedValue({data: {}} as never);
            await renderWith({nodes: [nd]});

            const user = await openEdit();

            const nameInput = screen.getByDisplayValue("Node 1");
            await user.clear(nameInput);
            await user.type(nameInput, "Node 1 Renamed");

            await user.click(screen.getByRole("button", {name: "Save"}));

            await waitFor(() => {
                expect(updateNode).toHaveBeenCalledWith({
                    path: {id: "n1"},
                    body: expect.objectContaining({display_name: "Node 1 Renamed"}),
                });
            });

            await waitFor(() => {
                expect(screen.queryByText("Edit Node")).not.toBeInTheDocument();
            });
        });

        it("Cancel closes modal without calling updateNode", async () => {
            const nd = node({id: "n1", display_name: "Node 1"});
            await renderWith({nodes: [nd]});

            const user = await openEdit();

            await user.click(screen.getByRole("button", {name: "Cancel"}));

            await waitFor(() => {
                expect(screen.queryByText("Edit Node")).not.toBeInTheDocument();
            });
            expect(updateNode).not.toHaveBeenCalled();
        });
    });

    describe("Edit modal error", () => {
        it("API error shown in modal", async () => {
            const nd = node({id: "n1"});
            vi.mocked(updateNode).mockResolvedValue({
                error: {message: "Invalid port range"},
            } as never);
            await renderWith({nodes: [nd]});

            const user = await openEdit();

            await user.click(screen.getByRole("button", {name: "Save"}));

            await waitFor(() => {
                expect(screen.getByText("Invalid port range")).toBeInTheDocument();
            });
        });
    });

    describe("Token modal", () => {
        it("Rotate Key opens confirm, on success sets token and shows TokenModal", async () => {
            const nd = node({id: "n1", status: "ACTIVE"});
            vi.mocked(rotateNodeToken).mockResolvedValue({
                data: {node_key: "new-token-abc"},
            } as never);
            await renderWith({nodes: [nd]});

            const user = await openOverflow();
            await openMenuAndClick("Rotate Key", user);

            await waitFor(() => {
                expect(screen.getByText("Rotate Node Key?")).toBeInTheDocument();
            });
            expect(
                screen.getByText(/The agent will need to re-register/i),
            ).toBeInTheDocument();

            await user.click(screen.getByRole("button", {name: "Confirm"}));

            await waitFor(() => {
                expect(rotateNodeToken).toHaveBeenCalledWith({path: {id: "n1"}});
            });

            await waitFor(() => {
                expect(screen.getByText("New Node Key")).toBeInTheDocument();
            });
            expect(screen.getByText("new-token-abc")).toBeInTheDocument();
        });

        it("Rotate Key error shows error banner", async () => {
            const nd = node({id: "n1", status: "ACTIVE"});
            vi.mocked(rotateNodeToken).mockResolvedValue({
                error: {message: "Rotation failed"},
            } as never);
            await renderWith({nodes: [nd]});

            const user = await openOverflow();
            await openMenuAndClick("Rotate Key", user);

            await waitFor(() => {
                expect(screen.getByText("Rotate Node Key?")).toBeInTheDocument();
            });
            await user.click(screen.getByRole("button", {name: "Confirm"}));

            await waitFor(() => {
                expect(screen.getByText("Rotation failed")).toBeInTheDocument();
            });
        });
    });

    describe("Shutdown", () => {
        it("calls shutdownNode via confirm dialog", async () => {
            const nd = node({id: "n1", display_name: "Node Alpha", status: "ACTIVE"});
            vi.mocked(shutdownNode).mockResolvedValue({data: {}} as never);
            await renderWith({nodes: [nd]});

            const user = await openOverflow();
            await openMenuAndClick("Shutdown", user);

            await waitFor(() => {
                expect(screen.getByText("Shutdown Node?")).toBeInTheDocument();
            });
            expect(screen.getByText(/Send shutdown command to "Node Alpha"\?/i)).toBeInTheDocument();

            await user.click(screen.getByRole("button", {name: "Confirm"}));

            await waitFor(() => {
                expect(shutdownNode).toHaveBeenCalledWith({path: {id: "n1"}});
            });
        });
    });

    describe("Decommission", () => {
        it("calls decommissionNode via confirm dialog when servers=0", async () => {
            const nd = node({id: "n1", display_name: "Node X", status: "ACTIVE"});
            vi.mocked(decommissionNode).mockResolvedValue({data: {}} as never);
            await renderWith({nodes: [nd]});

            const user = await openOverflow();
            await openMenuAndClick("Decommission", user);

            await waitFor(() => {
                expect(screen.getByText("Decommission Node?")).toBeInTheDocument();
            });
            expect(
                screen.getByText(/Decommission "Node X"\? This cannot be undone/i),
            ).toBeInTheDocument();

            await user.click(screen.getByRole("button", {name: "Confirm"}));

            await waitFor(() => {
                expect(decommissionNode).toHaveBeenCalledWith({path: {id: "n1"}});
            });
        });
    });

    describe("WS subscription", () => {
        it("subscribes to node.status on mount and handler updates health", async () => {
            const nd = node({id: "n1", health: "HEALTHY"});
            await renderWith({nodes: [nd]});

            expect(subscribeMock).toHaveBeenCalledWith("node.status", expect.any(Function));

            const handler = subscribeMock.mock.calls[0][1];
            act(() => {
                handler({node_id: "n1", health: "UNREACHABLE"});
            });

            await waitFor(() => {
                expect(screen.getAllByText("Unreachable").length).toBeGreaterThan(0);
            });
        });
    });

    describe("Stale detection", () => {
        it("last_seen_at older than 300s shows error color", async () => {
            const nd = node({last_seen_at: "2026-06-20T10:00:00Z"});
            await renderWith({nodes: [nd]});

            const staleEls = screen.getAllByText(/days ago/);
            expect(staleEls.length).toBeGreaterThan(0);
            const hasErrorClass = Array.from(staleEls).some(
                (el) => el.className.includes("text-error"),
            );
            expect(hasErrorClass).toBe(true);
        });
    });

    describe("Permission gating", () => {
        it("Trust/Reject buttons hidden without system.nodes permission for PENDING node", async () => {
            const nd = node({id: "n1", status: "PENDING"});
            await renderWith({nodes: [nd], permissions: []});

            expect(screen.queryByTitle("Trust node")).not.toBeInTheDocument();
            expect(screen.queryByTitle("Reject node")).not.toBeInTheDocument();
        });

        it("Trust/Reject buttons shown with system.nodes permission for PENDING node", async () => {
            const nd = node({id: "n1", status: "PENDING"});
            await renderWith({nodes: [nd], permissions: ["system.nodes"]});

            expect(screen.getAllByTitle("Trust node").length).toBeGreaterThan(0);
            expect(screen.getAllByTitle("Reject node").length).toBeGreaterThan(0);
        });

        it("overflow menu still shown regardless of permissions", async () => {
            const nd = node({id: "n1", status: "ACTIVE"});
            await renderWith({nodes: [nd], permissions: []});

            expect(overflowButtons().length).toBeGreaterThan(0);

            await openOverflow();

            await waitFor(() => {
                expect(screen.getAllByText("Edit").length).toBeGreaterThan(0);
            });
            expect(screen.getAllByText("Rotate Key").length).toBeGreaterThan(0);
        });
    });

    describe("Mobile cards", () => {
        it("mobile layout renders cards with same data", async () => {
            const nd = node({display_name: "Mobile Node"});
            await renderWith({nodes: [nd]});

            const mobileCards = document.querySelectorAll(".md\\:hidden");
            expect(mobileCards.length).toBeGreaterThan(0);
            expect(mobileCards[0].textContent).toContain("Mobile Node");
            expect(mobileCards[0].textContent).toContain("Active");
            expect(mobileCards[0].textContent).toContain("8.0 GB / 32.0 GB");
        });
    });

    describe("Node sorted/filtered", () => {
        it("filtered list respects status filter", async () => {
            const activeN = node({
                id: "n1",
                display_name: "Active Node",
                status: "ACTIVE",
                health: "HEALTHY",
            });
            const decomN = node({
                id: "n2",
                display_name: "Decommissioned Node",
                status: "DECOMMISSIONED",
                health: "HEALTHY",
            });
            await renderWith({nodes: [activeN, decomN]});

            expect(screen.getAllByText("Active Node").length).toBeGreaterThan(0);
            expect(screen.getAllByText("Decommissioned Node").length).toBeGreaterThan(0);

            const selects = screen.getAllByRole("combobox");
            const user = userEvent.setup();
            await user.selectOptions(selects[0], "DECOMMISSIONED");

            await waitFor(() => {
                expect(screen.queryByText("Active Node")).not.toBeInTheDocument();
                expect(screen.getAllByText("Decommissioned Node").length).toBeGreaterThan(0);
            });
        });
    });
});
