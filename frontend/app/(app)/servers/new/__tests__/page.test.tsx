import {describe, it, expect, vi, beforeEach} from "vitest";
import {render, screen, waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

vi.mock("@/lib/generated/sdk.gen", () => ({
    listNodes: vi.fn(),
    listNetworks: vi.fn(),
    createServer: vi.fn(),
}));

const mockAuth = vi.hoisted(() => ({
    useAuth: vi.fn(() => ({user: {permissions: ["server.create"]}})),
}));

vi.mock("@/lib/auth-context", () => ({
    useAuth: mockAuth.useAuth,
}));

import {listNodes, listNetworks, createServer} from "@/lib/generated/sdk.gen";
import NewServerPage from "../page";

function node(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        id: "node-1",
        display_name: "Main Node",
        status: "ACTIVE",
        total_ram_mb: 32768,
        ...overrides,
    };
}

function network(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        id: "net-1",
        name: "Survival",
        description: null,
        server_count: 0,
        ...overrides,
    };
}

function deferred<T>(): { promise: Promise<T>; resolve: (v: T) => void } {
    let resolve!: (v: T) => void;
    const promise = new Promise<T>((r) => { resolve = r; });
    return {promise, resolve};
}

async function renderWith(mocks: {
    nodes?: Record<string, unknown>[];
    networks?: Record<string, unknown>[];
    mojangVersions?: string[];
    permissions?: string[];
} = {}) {
    const {
        nodes: ns = [node()],
        networks: nets = [],
        mojangVersions: vs = ["1.21.4", "1.21.3"],
        permissions: p = ["server.create"],
    } = mocks;

    mockAuth.useAuth.mockReturnValue({user: {permissions: p}});
    vi.mocked(listNodes).mockResolvedValue({data: ns as any});
    vi.mocked(listNetworks).mockResolvedValue({data: nets as any});

    const mockFetch = vi.fn().mockResolvedValue({
        json: () => Promise.resolve({
            versions: vs.map((id) => ({id, type: "release", releaseTime: "2026-01-01T00:00:00Z"})),
        }),
    });
    vi.stubGlobal("fetch", mockFetch);

    const ui = render(<NewServerPage/>);
    await waitFor(() => expect(screen.queryByText(/loading/i)).toBeNull());
    return ui;
}

describe("NewServerPage", () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.unstubAllGlobals();
    });

    it("shows permission denied when user lacks server.create", () => {
        mockAuth.useAuth.mockReturnValue({user: {permissions: []}});
        vi.mocked(listNodes).mockResolvedValue({data: undefined as any} as any);
        vi.mocked(listNetworks).mockResolvedValue({data: undefined as any} as any);
        const fetchMock = vi.fn().mockResolvedValue({json: () => Promise.resolve({versions: []})});
        vi.stubGlobal("fetch", fetchMock);
        render(<NewServerPage/>);
        expect(screen.getByText(/do not have permission/i)).toBeTruthy();
    });

    it("renders form fields", async () => {
        await renderWith();
        expect(screen.getByPlaceholderText("survival-1")).toBeTruthy();
        expect(screen.getByPlaceholderText("Survival SMP")).toBeTruthy();
        expect(screen.getByDisplayValue("2048")).toBeTruthy();
    });

    it("renders node options in select", async () => {
        await renderWith({nodes: [node({id: "n1", display_name: "Alpha"}), node({id: "n2", display_name: "Beta"})]});
        const opts = screen.getAllByRole("option");
        const alphaOpt = opts.find((o) => o.textContent?.includes("Alpha"));
        const betaOpt = opts.find((o) => o.textContent?.includes("Beta"));
        expect(alphaOpt).toBeTruthy();
        expect(betaOpt).toBeTruthy();
    });

    it("creates server with correct body", async () => {
        vi.mocked(createServer).mockResolvedValue({
            data: {id: "new-srv"},
            error: undefined,
        } as any);

        await renderWith({nodes: [node({id: "n1"})], networks: [network({id: "net-1"})]});

        await userEvent.setup().type(screen.getByPlaceholderText("survival-1"), "my-server");
        await userEvent.setup().click(screen.getByText("Create Server"));

        await waitFor(() => {
            expect(createServer).toHaveBeenCalledWith(
                expect.objectContaining({
                    body: expect.objectContaining({
                        name: "my-server",
                        server_type: "PAPER",
                        node_id: "n1",
                        memory_mb: 2048,
                    }),
                }),
            );
        });
    });

    it("shows error from API", async () => {
        vi.mocked(createServer).mockResolvedValue({
            data: undefined,
            error: {message: "Name taken"} as any,
        } as any);

        await renderWith();
        await userEvent.setup().type(screen.getByPlaceholderText("survival-1"), "test");
        await userEvent.setup().click(screen.getByText("Create Server"));

        await waitFor(() => {
            expect(screen.getByText("Name taken")).toBeTruthy();
        });
    });

    it("shows default error on exception", async () => {
        vi.mocked(createServer).mockRejectedValue(new Error("network error"));

        await renderWith();
        await userEvent.setup().type(screen.getByPlaceholderText("survival-1"), "test");
        await userEvent.setup().click(screen.getByText("Create Server"));

        await waitFor(() => {
            expect(screen.getByText("Failed to create server")).toBeTruthy();
        });
    });

    it("renders version dropdown with Mojang versions", async () => {
        await renderWith({mojangVersions: ["1.21.4", "1.21.3"]});
        await waitFor(() => {
            expect(screen.getByText("1.21.4")).toBeTruthy();
            expect(screen.getByText("1.21.3")).toBeTruthy();
        });
    });

    it("shows text input when Mojang fetch returns empty", async () => {
        await renderWith({mojangVersions: []});
        await waitFor(() => {
            expect(screen.getByPlaceholderText("1.21.4")).toBeTruthy();
        });
    });

    it("submit button disabled while loading data", async () => {
        const nodeD = deferred();
        const netD = deferred();
        const fetchD = deferred();
        vi.mocked(listNodes).mockReturnValue(nodeD.promise as any);
        vi.mocked(listNetworks).mockReturnValue(netD.promise as any);
        const mockFetch = vi.fn().mockReturnValue(fetchD.promise);
        vi.stubGlobal("fetch", mockFetch);

        mockAuth.useAuth.mockReturnValue({user: {permissions: ["server.create"]}});
        render(<NewServerPage/>);

        expect(screen.getByText("Create Server")).toBeDisabled();

        nodeD.resolve({data: [node() as any]});
        netD.resolve({data: [] as any});
        fetchD.resolve({json: () => Promise.resolve({versions: []})});

        await waitFor(() => {
            expect(screen.getByText("Create Server")).not.toBeDisabled();
        });
    });

    it("hides version selector for proxy types", async () => {
        await renderWith();
        await userEvent.setup().selectOptions(
            screen.getByDisplayValue("PAPER"),
            "VELOCITY",
        );
        expect(screen.queryByText("Minecraft Version")).toBeNull();
    });

    it("renders network options", async () => {
        await renderWith({networks: [network({id: "n1", name: "Survival"})]});
        expect(screen.getByText("Survival")).toBeTruthy();
    });

    it("renders back link to /servers", async () => {
        await renderWith();
        expect(screen.getByText("Servers")).toBeTruthy();
    });
});
