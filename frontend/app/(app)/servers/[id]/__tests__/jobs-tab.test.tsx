import {describe, it, expect, vi, beforeEach} from "vitest";
import {render, screen, waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {JobsTab} from "../jobs-tab";

vi.mock("@/lib/generated/sdk.gen", () => ({
    listScheduledJobs: vi.fn(),
    createScheduledJob: vi.fn(),
    updateScheduledJob: vi.fn(),
    deleteScheduledJob: vi.fn(),
}));

import {createScheduledJob, deleteScheduledJob, listScheduledJobs, updateScheduledJob} from "@/lib/generated/sdk.gen";

function job(overrides: Record<string, unknown> = {}) {
    return {
        id: "j1",
        server_id: "s1",
        type: "RESTART",
        cron_expression: "0 4 * * *",
        payload: null,
        enabled: true,
        last_fired_at: null,
        ...overrides,
    };
}

async function renderWith(items: Record<string, unknown>[] = [], permissions: string[] = ["server.cron", "server.restart"]) {
    vi.mocked(listScheduledJobs).mockResolvedValue({data: items} as never);
    vi.mocked(updateScheduledJob).mockResolvedValue({data: job()} as never);
    vi.mocked(deleteScheduledJob).mockResolvedValue({data: undefined} as never);
    vi.mocked(createScheduledJob).mockResolvedValue({data: job()} as never);
    render(<JobsTab serverId="s1" permissions={permissions} />);
    await waitFor(() => expect(screen.queryByText("Loading scheduled jobs…")).not.toBeInTheDocument());
}

describe("JobsTab", () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it("shows loading state then empty state", async () => {
        vi.mocked(listScheduledJobs).mockResolvedValue({data: []} as never);
        render(<JobsTab serverId="s1" permissions={["server.cron"]} />);
        expect(screen.getByText("Loading scheduled jobs…")).toBeInTheDocument();
        await waitFor(() => expect(screen.getByText("No scheduled jobs yet")).toBeInTheDocument());
    });

    it("renders scheduled jobs", async () => {
        await renderWith([
            job({id: "j1", type: "RESTART", cron_expression: "0 4 * * *"}),
            job({id: "j2", type: "RCON_COMMAND", payload: "say hi", enabled: false}),
        ]);

        expect(screen.getByText("2 scheduled jobs")).toBeInTheDocument();
        expect(screen.getByText("Restart server")).toBeInTheDocument();
        expect(screen.getByText("Console command")).toBeInTheDocument();
        expect(screen.getByText("say hi")).toBeInTheDocument();
        expect(screen.getByText("(disabled)")).toBeInTheDocument();
    });

    it("disables Add Job when the user holds no schedulable type permission", async () => {
        await renderWith([], ["server.cron"]);
        expect(screen.getByRole("button", {name: /add job/i})).toBeDisabled();
    });

    it("creates a job from the dialog", async () => {
        const user = userEvent.setup();
        await renderWith([], ["server.cron", "server.restart"]);

        await user.click(screen.getByRole("button", {name: /add job/i}));
        await user.click(screen.getByRole("button", {name: /^save$/i}));

        await waitFor(() =>
            expect(createScheduledJob).toHaveBeenCalledWith({
                path: {id: "s1"},
                body: {type: "RESTART", cron_expression: "0 4 * * *", payload: null, enabled: true},
            }),
        );
    });

    it("toggles a job's enabled state", async () => {
        const user = userEvent.setup();
        await renderWith([job({id: "j1", enabled: true})]);

        await user.click(screen.getByRole("button", {name: /disable/i}));

        await waitFor(() => expect(updateScheduledJob).toHaveBeenCalledWith({path: {id: "s1", jobId: "j1"}, body: {enabled: false}}));
    });

    it("deletes a job", async () => {
        const user = userEvent.setup();
        await renderWith([job({id: "j1"})]);

        await user.click(screen.getByTitle("Delete job"));

        await waitFor(() => expect(deleteScheduledJob).toHaveBeenCalledWith({path: {id: "s1", jobId: "j1"}}));
    });
});
