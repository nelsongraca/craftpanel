import {describe, it, expect, vi, beforeEach} from "vitest";
import {render, screen, waitFor, act} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

const {subscribeMock} = vi.hoisted(() => ({
    subscribeMock: vi.fn(() => vi.fn()),
}));

vi.mock("@/lib/generated/sdk.gen", () => ({
    listAlertThresholds: vi.fn(),
    listAlertEvents: vi.fn(),
    createAlertThreshold: vi.fn(),
    deleteAlertThreshold: vi.fn(),
    listNodes: vi.fn(),
    listServers: vi.fn(),
}));

vi.mock("@/lib/auth-context", () => ({
    useAuth: vi.fn(() => ({user: {permissions: []}})),
}));

vi.mock("@/lib/ws-context", () => ({
    useWs: vi.fn(() => ({subscribe: subscribeMock})),
}));

import {
    listAlertThresholds, listAlertEvents, createAlertThreshold, deleteAlertThreshold,
    listNodes, listServers,
} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import AlertsPage from "../page";

function threshold(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        id: "th-1",
        scope_type: "NODE",
        scope_id: "node-1",
        metric: "cpu_percent",
        threshold_value: 80,
        threshold_state: null,
        created_at: "2026-06-01T00:00:00Z",
        ...overrides,
    };
}

function event(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        id: "ev-1",
        threshold_id: "th-1",
        message: "CPU usage exceeded threshold",
        fired_at: "2026-06-27T10:00:00Z",
        resolved_at: null,
        ...overrides,
    };
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
        thresholds?: Record<string, unknown>[];
        events?: Record<string, unknown>[];
        permissions?: string[];
        nodes?: Record<string, unknown>[];
        servers?: Record<string, unknown>[];
    } = {},
) {
    const {
        thresholds: t = [threshold()],
        events: e = [event()],
        permissions: p = [],
        nodes: nd = [],
        servers: sv = [],
    } = mocks;

    vi.mocked(listAlertThresholds).mockResolvedValue({data: {thresholds: t}} as never);
    vi.mocked(listAlertEvents).mockResolvedValue({data: {events: e}} as never);
    vi.mocked(listNodes).mockResolvedValue({data: nd} as never);
    vi.mocked(listServers).mockResolvedValue({data: sv} as never);
    (vi.mocked(useAuth) as ReturnType<typeof vi.fn>).mockReturnValue({
        user: {permissions: p},
    });

    const result = render(<AlertsPage/>);

    await waitFor(() => {
        expect(screen.queryAllByText("Loading…").length).toBe(0);
    });

    return result;
}

describe("AlertsPage", () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    describe("Loading state", () => {
        it("shows Loading… in both tables while data is loading", async () => {
            const def = deferred<{ data: { thresholds: Record<string, unknown>[] } }>();
            vi.mocked(listAlertThresholds).mockReturnValue(def.promise as never);
            vi.mocked(listAlertEvents).mockResolvedValue({data: {events: []}} as never);

            render(<AlertsPage/>);

            expect(screen.getAllByText("Loading…").length).toBe(4);

            def.resolve({data: {thresholds: []}});
            await waitFor(() => {
                expect(screen.getAllByText("No thresholds configured.").length).toBe(2);
            });
        });
    });

    describe("Empty states", () => {
        it('shows "No thresholds configured." when thresholds list is empty', async () => {
            await renderWith({thresholds: [], events: [], permissions: ["system.settings"]});
            expect(screen.getAllByText("No thresholds configured.").length).toBe(2);
        });

        it('shows "No alert events." when events list is empty', async () => {
            await renderWith({thresholds: [], events: []});
            expect(screen.getAllByText("No alert events.").length).toBe(2);
        });
    });

    describe("Thresholds list", () => {
        it("renders scope type, scope id, metric, trigger, and created time", async () => {
            const th = threshold();
            await renderWith({thresholds: [th]});

            expect(screen.getAllByText("NODE").length).toBeGreaterThan(0);
            expect(screen.getAllByText("node-1…").length).toBeGreaterThan(0);
            expect(screen.getAllByText("cpu_percent").length).toBeGreaterThan(0);
            expect(screen.getAllByText("> 80").length).toBeGreaterThan(0);
            expect(screen.getAllByText(/ago/).length).toBeGreaterThan(0);
        });

        it("renders numeric trigger > 80 in warning color", async () => {
            const th = threshold({threshold_value: 80, threshold_state: null});
            await renderWith({thresholds: [th]});

            const trigger = screen.getAllByText("> 80")[0];
            expect(trigger.className).toContain("text-warning");
        });

        it("renders state trigger as = UNHEALTHY", async () => {
            const th = threshold({
                threshold_value: null,
                threshold_state: "UNHEALTHY",
            });
            await renderWith({thresholds: [th]});

            const trigger = screen.getAllByText("= UNHEALTHY")[0];
            expect(trigger.className).toContain("text-text-dim");
        });
    });

    describe("Delete threshold", () => {
        it("clicking trash calls deleteAlertThreshold and removes row and associated events", async () => {
            const th = threshold({id: "th-1", scope_type: "SERVER", scope_id: "sv-1"});
            const ev1 = event({id: "ev-1", threshold_id: "th-1", message: "Alert 1"});
            const ev2 = event({id: "ev-2", threshold_id: "th-2", message: "Alert 2"});
            vi.mocked(deleteAlertThreshold).mockResolvedValue({} as never);

            await renderWith({
                thresholds: [th],
                events: [ev1, ev2],
                permissions: ["system.settings"],
            });

            await userEvent.setup().click(screen.getAllByTitle("Delete threshold")[0]);

            await waitFor(() => {
                expect(deleteAlertThreshold).toHaveBeenCalledWith({path: {id: "th-1"}});
            });

            expect(screen.queryByText("SERVER")).not.toBeInTheDocument();
            expect(screen.queryByText("Alert 1")).not.toBeInTheDocument();
            expect(screen.getAllByText("Alert 2").length).toBeGreaterThan(0);
        });

        it("shows error banner on delete failure and can be dismissed", async () => {
            const th = threshold();
            vi.mocked(deleteAlertThreshold).mockResolvedValue({
                error: {message: "Cannot delete threshold in use"},
            } as never);

            await renderWith({
                thresholds: [th],
                events: [],
                permissions: ["system.settings"],
            });

            const user = userEvent.setup();
            await user.click(screen.getAllByTitle("Delete threshold")[0]);

            await waitFor(() => {
                expect(
                    screen.getByText("Cannot delete threshold in use"),
                ).toBeInTheDocument();
            });

            const closeBtn = screen.getByRole("button", {name: "Dismiss"});
            await user.click(closeBtn);

            await waitFor(() => {
                expect(
                    screen.queryByText("Cannot delete threshold in use"),
                ).not.toBeInTheDocument();
            });
        });
    });

    describe("Events list", () => {
        it("renders message, threshold id, fired time, and Active state for unresolved event", async () => {
            const ev = event();
            await renderWith({
                thresholds: [threshold()],
                events: [ev],
            });

            expect(screen.getAllByText("CPU usage exceeded threshold").length).toBeGreaterThan(0);
            expect(screen.getAllByText("th-1…").length).toBeGreaterThan(0);
            expect(screen.getAllByText(/ago/).length).toBeGreaterThan(0);
            expect(screen.getAllByText("Active").length).toBeGreaterThan(0);
        });

        it("resolved event shows resolved time instead of Active label", async () => {
            const ev = event({
                resolved_at: "2026-06-27T12:00:00Z",
            });
            await renderWith({
                thresholds: [threshold()],
                events: [ev],
            });

            expect(screen.queryAllByText("Active").length).toBe(0);
        });

        it("active event shows AlertTriangle icon and Active label", async () => {
            const ev = event();
            await renderWith({
                thresholds: [threshold()],
                events: [ev],
            });

            expect(screen.getAllByText("Active").length).toBeGreaterThan(0);
        });
    });

    describe("Active only toggle", () => {
        it("clicking toggle filters to unresolved events and updates button text", async () => {
            const activeEv = event({
                id: "ev-1",
                resolved_at: null,
                message: "Active alert",
            });
            const resolvedEv = event({
                id: "ev-2",
                resolved_at: "2026-06-27T12:00:00Z",
                message: "Resolved alert",
            });

            await renderWith({
                thresholds: [threshold()],
                events: [activeEv, resolvedEv],
            });

            expect(screen.getAllByText("Active alert").length).toBeGreaterThan(0);
            expect(screen.getAllByText("Resolved alert").length).toBeGreaterThan(0);

            const user = userEvent.setup();
            await user.click(screen.getByRole("button", {name: "All Events"}));

            await waitFor(() => {
                expect(screen.queryAllByText("Resolved alert").length).toBe(0);
            });
            expect(screen.getAllByText("Active alert").length).toBeGreaterThan(0);
            expect(screen.getByText("Active Only")).toBeInTheDocument();
        });
    });

    describe("WS subscriptions", () => {
        it("alert.fired prepends event to list", async () => {
            await renderWith({
                thresholds: [threshold()],
                events: [event({id: "ev-1", message: "Old event"})],
            });

            const firedHandler = subscribeMock.mock.calls.find(
                (c: unknown[]) => c[0] === "alert.fired",
            )?.[1];
            expect(firedHandler).toBeDefined();

            act(() => {
                firedHandler({
                    event_id: "ev-2",
                    threshold_id: "th-1",
                    message: "New live event",
                    fired_at: "2026-06-27T11:00:00Z",
                });
            });

            await waitFor(() => {
                expect(screen.getAllByText("New live event").length).toBeGreaterThan(0);
            });
        });

        it("alert.resolved updates event with resolved_at", async () => {
            await renderWith({
                thresholds: [threshold()],
                events: [event({id: "ev-1", message: "Will resolve"})],
            });

            expect(screen.getByText("Active")).toBeInTheDocument();

            const resolvedHandler = subscribeMock.mock.calls.find(
                (c: unknown[]) => c[0] === "alert.resolved",
            )?.[1];
            expect(resolvedHandler).toBeDefined();

            act(() => {
                resolvedHandler({
                    event_id: "ev-1",
                    resolved_at: "2026-06-27T12:00:00Z",
                });
            });

            await waitFor(() => {
                expect(screen.queryAllByText("Active").length).toBe(0);
            });
        });
    });

    describe("Create threshold modal", () => {
        it("opens modal, fills form, calls createAlertThreshold, and prepends threshold on success", async () => {
            const created = threshold({
                id: "th-new",
                scope_id: "n1",
                threshold_value: 95,
                created_at: "2026-06-27T10:00:00Z",
            });
            vi.mocked(createAlertThreshold).mockResolvedValue({
                data: created,
            } as never);

            await renderWith({
                thresholds: [],
                events: [],
                permissions: ["system.settings"],
                nodes: [{id: "n1", display_name: "Node 1"}],
            });

            const user = userEvent.setup();
            await user.click(screen.getByRole("button", {name: /New Threshold/i}));

            await waitFor(() => {
                expect(screen.getByText("New Alert Threshold")).toBeInTheDocument();
            });

            const selects = screen.getAllByRole("combobox");
            await user.selectOptions(selects[1], "n1");

            const valueInput = screen.getByRole("spinbutton");
            await user.clear(valueInput);
            await user.type(valueInput, "95");

            await user.click(screen.getByRole("button", {name: "Create"}));

            await waitFor(() => {
                expect(createAlertThreshold).toHaveBeenCalledWith({
                    body: expect.objectContaining({
                        scope_type: "NODE",
                        scope_id: "n1",
                        metric: "cpu_percent",
                        threshold_value: 95,
                    }),
                });
            });

            expect(screen.queryByText("New Alert Threshold")).not.toBeInTheDocument();
            expect(screen.getByText("> 95")).toBeInTheDocument();
        });

        it("switching to state trigger shows state input instead of value input", async () => {
            await renderWith({
                thresholds: [],
                events: [],
                permissions: ["system.settings"],
                nodes: [{id: "n1", display_name: "Node 1"}],
            });

            const user = userEvent.setup();
            await user.click(screen.getByRole("button", {name: /New Threshold/i}));

            await waitFor(() => {
                expect(screen.getByText("New Alert Threshold")).toBeInTheDocument();
            });

            expect(screen.getByRole("spinbutton")).toBeInTheDocument();
            expect(screen.queryByDisplayValue("UNHEALTHY")).not.toBeInTheDocument();

            await user.click(screen.getByRole("button", {name: "state"}));

            expect(screen.queryByRole("spinbutton")).not.toBeInTheDocument();
            expect(screen.getByDisplayValue("UNHEALTHY")).toBeInTheDocument();
        });
    });

    describe("Permission gating", () => {
        it("shows New Threshold button with system.settings permission", async () => {
            await renderWith({thresholds: [], events: [], permissions: ["system.settings"]});
            expect(
                screen.getByRole("button", {name: /New Threshold/i}),
            ).toBeInTheDocument();
        });

        it("hides New Threshold button without system.settings permission", async () => {
            await renderWith({thresholds: [], events: [], permissions: []});
            expect(
                screen.queryByRole("button", {name: /New Threshold/i}),
            ).not.toBeInTheDocument();
        });
    });
});
