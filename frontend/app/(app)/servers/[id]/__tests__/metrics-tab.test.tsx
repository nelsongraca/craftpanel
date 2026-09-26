import {render, screen, waitFor} from "@testing-library/react";
import {beforeEach, describe, expect, it, vi} from "vitest";
import {MetricsTab} from "../metrics-tab";

const {subscribeMock} = vi.hoisted(() => ({subscribeMock: vi.fn(() => vi.fn())}));
const getServerMetrics = vi.hoisted(() => vi.fn());

vi.mock("@/lib/generated/sdk.gen", () => ({getServerMetrics}));
vi.mock("@/lib/ws-context", () => ({
    useWs: () => ({subscribe: subscribeMock}),
}));

function series(overrides?: {heap?: boolean}) {
    const t = new Date().toISOString();
    return {
        data: {
            server_id: "s1",
            series: {
                cpu_percent: [{t, v: 12.5}],
                ram_used_mb: [{t, v: 512}],
                net_in_bytes: [{t, v: 1024}],
                net_out_bytes: [{t, v: 2048}],
                block_in_bytes: [{t, v: 4096}],
                block_out_bytes: [{t, v: 8192}],
                ...(overrides?.heap
                    ? {
                          heap_used_bytes: [{t, v: 1_000_000}],
                          heap_max_bytes: [{t, v: 2_000_000}],
                      }
                    : {}),
            },
        },
    };
}

describe("MetricsTab", () => {
    beforeEach(() => {
        getServerMetrics.mockReset();
        subscribeMock.mockClear();
    });

    it("shows empty state when no samples are returned", async () => {
        getServerMetrics.mockResolvedValue({
            data: {
                server_id: "s1",
                series: {
                    cpu_percent: [],
                    ram_used_mb: [],
                    net_in_bytes: [],
                    net_out_bytes: [],
                    block_in_bytes: [],
                    block_out_bytes: [],
                },
            },
        });

        render(<MetricsTab serverId="s1" ramLimitMb={2048} />);

        await waitFor(() => expect(screen.getByText(/No metrics available/i)).toBeInTheDocument());
    });

    it("renders CPU, RAM, Network and Block charts but hides JVM Heap without samples", async () => {
        getServerMetrics.mockResolvedValue(series());

        render(<MetricsTab serverId="s1" ramLimitMb={2048} />);

        await waitFor(() => expect(screen.getByText("CPU Utilization")).toBeInTheDocument());
        expect(screen.getByText("RAM Usage")).toBeInTheDocument();
        expect(screen.getByText("Network I/O")).toBeInTheDocument();
        expect(screen.getByText("Block I/O")).toBeInTheDocument();
        expect(screen.queryByText("JVM Heap")).not.toBeInTheDocument();
    });

    it("renders the JVM Heap chart when heap samples are present", async () => {
        getServerMetrics.mockResolvedValue(series({heap: true}));

        render(<MetricsTab serverId="s1" ramLimitMb={2048} />);

        await waitFor(() => expect(screen.getByText("JVM Heap")).toBeInTheDocument());
    });

    it("subscribes to server.metrics for live samples", async () => {
        getServerMetrics.mockResolvedValue(series());

        render(<MetricsTab serverId="s1" ramLimitMb={2048} />);

        await waitFor(() => expect(screen.getByText("CPU Utilization")).toBeInTheDocument());
        expect(subscribeMock).toHaveBeenCalledWith("server.metrics", expect.any(Function));
    });
});
