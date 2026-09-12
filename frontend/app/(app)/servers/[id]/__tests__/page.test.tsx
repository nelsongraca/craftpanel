import {describe, it, expect, vi, beforeEach} from "vitest";
import {render, screen, waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

vi.mock("@/lib/generated/sdk.gen", () => ({
    getServer: vi.fn(),
    getNode: vi.fn(),
    getNetwork: vi.fn(),
    listNetworks: vi.fn(),
    startServer: vi.fn(),
    stopServer: vi.fn(),
    restartServer: vi.fn(),
    forceStopServer: vi.fn(),
    deleteServer: vi.fn(),
}));

vi.mock("@/lib/auth-context", () => ({
    useAuth: vi.fn(() => ({user: {permissions: [], server_permissions: {}}})),
}));

vi.mock("@/lib/ws-context", () => ({
    useWs: vi.fn(() => ({subscribe: vi.fn(() => () => {})})),
}));

vi.mock("next/navigation", () => ({
    useRouter: () => ({push: vi.fn()}),
    usePathname: () => "/",
    useParams: () => ({id: "s1"}),
    useSearchParams: () => new URLSearchParams(),
}));

import {
    getServer,
    getNode,
    getNetwork,
    listNetworks,
    startServer,
} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import ServerDetailPage from "../page";

function detailServer(overrides: Record<string, unknown> = {}): Record<string, unknown> {
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
        disabled: false,
        config_mode: "MANAGED",
        stop_command: "stop",
        last_player_count: null,
        last_player_names: null,
        created_at: "2025-01-01T00:00:00Z",
        updated_at: "2025-01-01T00:00:00Z",
        ...overrides,
    };
}

async function renderDetail(
    overrides: Record<string, unknown> = {},
    permissions: string[] = [],
) {
    vi.mocked(getServer).mockResolvedValue({
        data: detailServer(overrides),
        response: new Response(),
    } as never);
    vi.mocked(getNode).mockResolvedValue({data: null, response: new Response()} as never);
    vi.mocked(getNetwork).mockResolvedValue({data: null, response: new Response()} as never);
    vi.mocked(listNetworks).mockResolvedValue({data: [], response: new Response()} as never);
    (vi.mocked(useAuth) as ReturnType<typeof vi.fn>).mockReturnValue({
        user: {permissions, server_permissions: {}},
    });

    render(<ServerDetailPage/>);

    await waitFor(() => {
        expect(screen.getAllByText("Survival").length).toBeGreaterThan(0);
    });
}

describe("ServerDetailPage", () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    describe("Expired server", () => {
        it("shows the Expired badge when expires_at is in the past", async () => {
            await renderDetail({status: "STOPPED", expires_at: "2024-01-01T00:00:00Z"});

            expect(screen.getByText("Expired")).toBeInTheDocument();
        });

        it("does not show the Expired badge when there is no expiry", async () => {
            await renderDetail({status: "STOPPED"});

            expect(screen.queryByText("Expired")).not.toBeInTheDocument();
        });

        it("hides the Start button for an expired stopped server even with server.start permission", async () => {
            await renderDetail(
                {status: "STOPPED", expires_at: "2024-01-01T00:00:00Z"},
                ["server.start"],
            );

            expect(screen.queryByRole("button", {name: "Start"})).not.toBeInTheDocument();
        });

        it("shows the Start button for a stopped server with a future expiry", async () => {
            await renderDetail(
                {status: "STOPPED", expires_at: "2999-01-01T00:00:00Z"},
                ["server.start"],
            );

            expect(screen.getByRole("button", {name: "Start"})).toBeInTheDocument();
        });

        it("hides Restart for an expired running server but keeps Stop", async () => {
            await renderDetail(
                {status: "HEALTHY", expires_at: "2024-01-01T00:00:00Z"},
                ["server.restart", "server.stop"],
            );

            expect(screen.queryByRole("button", {name: "Restart"})).not.toBeInTheDocument();
            expect(screen.getByRole("button", {name: "Stop"})).toBeInTheDocument();
        });

        it("hides the Restart Now link in the needs_recreate banner when expired", async () => {
            await renderDetail(
                {status: "HEALTHY", expires_at: "2024-01-01T00:00:00Z", needs_recreate: true},
                ["server.restart"],
            );

            expect(screen.getByText(/Settings saved/)).toBeInTheDocument();
            expect(screen.queryByRole("button", {name: "Restart Now"})).not.toBeInTheDocument();
        });
    });

    describe("Disabled server", () => {
        it("shows the Disabled badge when disabled flag is set", async () => {
            await renderDetail({status: "STOPPED", disabled: true});

            expect(screen.getByText("Disabled")).toBeInTheDocument();
        });

        it("hides the Start button for a disabled stopped server even with server.start permission", async () => {
            await renderDetail(
                {status: "STOPPED", disabled: true},
                ["server.start"],
            );

            expect(screen.queryByRole("button", {name: "Start"})).not.toBeInTheDocument();
        });

        it("hides Restart for a disabled running server but keeps Stop", async () => {
            await renderDetail(
                {status: "HEALTHY", disabled: true},
                ["server.restart", "server.stop"],
            );

            expect(screen.queryByRole("button", {name: "Restart"})).not.toBeInTheDocument();
            expect(screen.getByRole("button", {name: "Stop"})).toBeInTheDocument();
        });
    });

    describe("Header actions (non-expired)", () => {
        it("calls startServer when Start is clicked", async () => {
            vi.mocked(startServer).mockResolvedValue({data: {}, response: new Response()} as never);
            await renderDetail({status: "STOPPED"}, ["server.start"]);

            const user = userEvent.setup();
            await user.click(screen.getByRole("button", {name: "Start"}));

            await waitFor(() => {
                expect(startServer).toHaveBeenCalledWith({path: {id: "s1"}});
            });
        });
    });
});