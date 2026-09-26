import {act, render, screen, waitFor} from "@testing-library/react";
import {beforeEach, describe, expect, it, vi} from "vitest";
import {StatusHistoryPanel} from "../status-history";

const {subscribeMock, listeners} = vi.hoisted(() => ({
    subscribeMock: vi.fn(),
    listeners: {} as Record<string, (payload: unknown) => void>,
}));

const getServerStatusHistory = vi.hoisted(() => vi.fn());

vi.mock("@/lib/generated/sdk.gen", () => ({getServerStatusHistory}));
vi.mock("@/lib/ws-context", () => ({
    useWs: () => ({subscribe: subscribeMock}),
}));

describe("StatusHistoryPanel", () => {
    beforeEach(() => {
        getServerStatusHistory.mockReset();
        subscribeMock.mockReset();
        subscribeMock.mockImplementation((type: string, cb: (payload: unknown) => void) => {
            listeners[type] = cb;
            return () => {
                delete listeners[type];
            };
        });
    });

    it("renders persisted transitions newest first", async () => {
        getServerStatusHistory.mockResolvedValue({
            data: {
                server_id: "s1",
                events: [
                    {status: "HEALTHY", recorded_at: "2026-01-01T02:00:00Z"},
                    {status: "STARTING", recorded_at: "2026-01-01T01:00:00Z"},
                    {status: "STOPPED", recorded_at: "2026-01-01T00:00:00Z"},
                ],
            },
        });

        render(<StatusHistoryPanel serverId="s1" />);

        await waitFor(() => expect(screen.getByText("Healthy")).toBeInTheDocument());
        expect(screen.getByText("Starting")).toBeInTheDocument();
        expect(screen.getByText("Stopped")).toBeInTheDocument();
        expect(screen.getByText("current")).toBeInTheDocument();
    });

    it("shows empty state when there is no history", async () => {
        getServerStatusHistory.mockResolvedValue({data: {server_id: "s1", events: []}});

        render(<StatusHistoryPanel serverId="s1" />);

        await waitFor(() => expect(screen.getByText(/No status changes/i)).toBeInTheDocument());
    });

    it("live-appends a new transition and drops repeats", async () => {
        getServerStatusHistory.mockResolvedValue({
            data: {server_id: "s1", events: [{status: "STOPPED", recorded_at: "2026-01-01T00:00:00Z"}]},
        });

        render(<StatusHistoryPanel serverId="s1" />);
        await waitFor(() => expect(screen.getByText("Stopped")).toBeInTheDocument());

        act(() => {
            listeners["server.status"]({server_id: "s1", status: "STARTING", recorded_at: "2026-01-01T01:00:00Z"});
        });
        expect(screen.getByText("Starting")).toBeInTheDocument();

        // A reconnect reaffirm of the same status must not add a duplicate row.
        act(() => {
            listeners["server.status"]({server_id: "s1", status: "STARTING", recorded_at: "2026-01-01T01:00:01Z"});
        });
        expect(screen.getAllByText("Starting")).toHaveLength(1);
    });
});
