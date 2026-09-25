import {act, render, screen} from "@testing-library/react";
import {describe, expect, it, vi} from "vitest";
import {ServerList} from "../server-list";
import type {Server} from "@/lib/types";

const ws = vi.hoisted(() => ({listeners: {} as Record<string, (payload: unknown) => void>}));

vi.mock("@/lib/ws-context", () => ({
    useWs: () => ({
        subscribe: (type: string, listener: (payload: unknown) => void) => {
            ws.listeners[type] = listener;
            return () => {
                delete ws.listeners[type];
            };
        },
    }),
}));

const makeServer = (overrides?: Partial<Server>): Server => ({
    id: "s1",
    name: "my-server",
    display_name: "My Server",
    description: null,
    server_type: "PAPER",
    mc_version: "1.21",
    itzg_image_tag: "latest",
    status: "RUNNING",
    node_id: "n1",
    network_id: null,
    host_port: 25565,
    memory_mb: 2048,
    cpu_limit_millicores: 100,
    exposed_externally: false,
    public_subdomain: null,
    custom_hostname: null,
    canonical_hostname: null,
    is_migrating: false,
    disabled: false,
    config_mode: "MANAGED",
    stop_command: "stop",
    expires_at: null,
    last_player_count: null,
    last_player_names: null,
    created_at: "2026-01-01T00:00:00Z",
    updated_at: "2026-01-01T00:00:00Z",
    ...overrides,
});

describe("ServerList players column", () => {
    it("shows the last known player count from the API row", () => {
        render(<ServerList servers={[makeServer({last_player_count: 7})]} nodes={[]} />);
        expect(screen.getByText("7")).toBeInTheDocument();
    });

    it("shows a dash when the player count is unknown", () => {
        render(<ServerList servers={[makeServer({last_player_count: null})]} nodes={[]} />);
        expect(screen.getByText("-")).toBeInTheDocument();
    });

    it("updates the count from a live server.players event", () => {
        render(<ServerList servers={[makeServer({last_player_count: 0})]} nodes={[]} />);
        act(() => {
            ws.listeners["server.players"]({
                server_id: "s1",
                player_count: 3,
                player_list: [],
                recorded_at: "2026-01-01T00:00:00Z",
            });
        });
        expect(screen.getByText("3")).toBeInTheDocument();
    });
});
