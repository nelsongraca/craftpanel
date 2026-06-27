import {describe, it, expect, vi, beforeEach} from "vitest";
import {render, screen, waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {BackupsTab} from "../backups-tab";

vi.mock("@/lib/generated/sdk.gen", () => ({
    listBackups: vi.fn(),
    getBackupSchedule: vi.fn(),
    triggerBackup: vi.fn(),
    deleteBackup: vi.fn(),
    updateBackupSchedule: vi.fn(),
}));

vi.mock("@/lib/ws-context", () => ({
    useWs: vi.fn(() => ({subscribe: vi.fn(() => vi.fn())})),
}));

import {
    listBackups,
    getBackupSchedule,
    triggerBackup,
    deleteBackup,
    updateBackupSchedule,
} from "@/lib/generated/sdk.gen";

function b(overrides: Record<string, unknown> = {}) {
    return {
        id: "b1",
        server_id: "s1",
        node_id: "n1",
        trigger: "MANUAL",
        status: "COMPLETED",
        file_path: null,
        size_bytes: null,
        error_message: null,
        created_at: "2026-01-15T10:00:00Z",
        completed_at: "2026-01-15T10:05:00Z",
        ...overrides,
    };
}

function sched(overrides: Record<string, unknown> = {}) {
    return {
        backup_schedule: "0 2 * * *",
        backup_max_count: 10,
        ...overrides,
    };
}

async function renderWith(items: Record<string, unknown>[] = []) {
    vi.mocked(listBackups).mockResolvedValue({data: {backups: items}} as never);
    vi.mocked(getBackupSchedule).mockResolvedValue({data: sched()} as never);
    vi.mocked(triggerBackup).mockResolvedValue({data: {}} as never);
    vi.mocked(deleteBackup).mockResolvedValue({data: undefined} as never);
    vi.mocked(updateBackupSchedule).mockResolvedValue({data: sched()} as never);
    render(<BackupsTab serverId="s1"/>);
    await waitFor(() => expect(screen.queryByText("Loading backups…")).not.toBeInTheDocument());
}

describe("BackupsTab", () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it("shows loading state then empty state", async () => {
        vi.mocked(listBackups).mockResolvedValue({data: {backups: []}} as never);
        vi.mocked(getBackupSchedule).mockResolvedValue({data: sched()} as never);
        render(<BackupsTab serverId="s1"/>);
        expect(screen.getByText("Loading backups…")).toBeInTheDocument();
        await waitFor(() => expect(screen.getByText("No backups yet")).toBeInTheDocument());
    });

    it("renders backup count and rows", async () => {
        await renderWith([
            b({id: "b1", size_bytes: 2_048_000, created_at: "2026-01-15T10:00:00Z"}),
            b({id: "b2", status: "FAILED", error_message: "Disk full"}),
            b({id: "b3", status: "IN_PROGRESS"}),
        ]);
        expect(screen.getByText("3 backups")).toBeInTheDocument();
        expect(screen.getByText("COMPLETED")).toBeInTheDocument();
        expect(screen.getByText("FAILED")).toBeInTheDocument();
        expect(screen.getByText("IN_PROGRESS")).toBeInTheDocument();
        expect(screen.getByText(/Disk full/)).toBeInTheDocument();
        expect(screen.getByText("2.0 MB")).toBeInTheDocument();
    });

    it("shows (scheduled) label for SCHEDULED trigger", async () => {
        await renderWith([b({trigger: "SCHEDULED"})]);
        expect(screen.getByText(/(scheduled)/)).toBeInTheDocument();
    });

    it("triggerBackup calls API and refreshes", async () => {
        const user = userEvent.setup();
        await renderWith([b()]);
        expect(listBackups).toHaveBeenCalledTimes(1);

        await user.click(screen.getByRole("button", {name: /trigger backup/i}));
        expect(triggerBackup).toHaveBeenCalledWith({path: {id: "s1"}});

        await waitFor(() => expect(listBackups).toHaveBeenCalledTimes(2));
        expect(getBackupSchedule).toHaveBeenCalledTimes(2);
    });

    it("disables trigger button while triggering", async () => {
        const user = userEvent.setup();
        let resolveTrigger: (v: unknown) => void;
        await renderWith([b()]);
        vi.mocked(triggerBackup).mockImplementation(
            () => new Promise((r) => {
                resolveTrigger = r;
            }) as never,
        );

        await user.click(screen.getByRole("button", {name: /trigger backup/i}));
        expect(screen.getByRole("button", {name: /triggering…/i})).toBeDisabled();
        resolveTrigger!({data: {}});
        await waitFor(() => expect(screen.getByRole("button", {name: /trigger backup/i})).not.toBeDisabled());
    });

    it("deleteBackup calls API and removes row", async () => {
        const user = userEvent.setup();
        await renderWith([b({id: "b1"}), b({id: "b2"})]);
        expect(screen.getByText("2 backups")).toBeInTheDocument();

        const deleteBtns = screen.getAllByTitle("Delete backup");
        await user.click(deleteBtns[0]);

        expect(deleteBackup).toHaveBeenCalledWith({path: {id: "s1", backupId: "b1"}});
        await waitFor(() => expect(screen.getByText("1 backup")).toBeInTheDocument());
    });

    it("disables delete button while deleting", async () => {
        const user = userEvent.setup();
        let resolveDelete: (v: unknown) => void;
        await renderWith([b({id: "b1"})]);
        vi.mocked(deleteBackup).mockImplementation(
            () => new Promise((r) => {
                resolveDelete = r;
            }) as never,
        );

        const deleteBtn = screen.getByTitle("Delete backup");
        await user.click(deleteBtn);
        expect(deleteBtn).toBeDisabled();
        resolveDelete!({error: {message: "fail"}});
        await waitFor(() => expect(deleteBtn).not.toBeDisabled());
    });

    it("shows error banner on trigger failure", async () => {
        const user = userEvent.setup();
        await renderWith([b()]);
        vi.mocked(triggerBackup).mockResolvedValue({error: {message: "Backup in progress"}} as never);

        await user.click(screen.getByRole("button", {name: /trigger backup/i}));
        await waitFor(() => expect(screen.getByText("Backup in progress")).toBeInTheDocument());
    });

    it("shows error banner on delete failure", async () => {
        const user = userEvent.setup();
        await renderWith([b({id: "b1"})]);
        vi.mocked(deleteBackup).mockResolvedValue({error: {message: "Cannot delete"}} as never);

        await user.click(screen.getByTitle("Delete backup"));
        await waitFor(() => expect(screen.getByText("Cannot delete")).toBeInTheDocument());
    });

    it("shows error banner with fallback message when error has no message", async () => {
        const user = userEvent.setup();
        await renderWith([b()]);
        vi.mocked(triggerBackup).mockResolvedValue({error: {}} as never);

        await user.click(screen.getByRole("button", {name: /trigger backup/i}));
        await waitFor(() => expect(screen.getByText("Failed to trigger backup")).toBeInTheDocument());
    });

    it("shows load error banner", async () => {
        vi.mocked(listBackups).mockResolvedValue({data: undefined} as never);
        vi.mocked(getBackupSchedule).mockResolvedValue({data: sched()} as never);
        render(<BackupsTab serverId="s1"/>);
        await waitFor(() => expect(screen.getByText("Failed to load backups")).toBeInTheDocument());
    });

    it("shows schedule info", async () => {
        vi.mocked(listBackups).mockResolvedValue({data: {backups: []}} as never);
        vi.mocked(getBackupSchedule).mockResolvedValue({
            data: sched({backup_schedule: "0 3 * * *", backup_max_count: 5}),
        } as never);
        render(<BackupsTab serverId="s1"/>);
        await waitFor(() => expect(screen.queryByText("Loading backups…")).not.toBeInTheDocument());
        expect(screen.getByText("0 3 * * *")).toBeInTheDocument();
        expect(screen.getByText("5")).toBeInTheDocument();
    });

    it("shows Disabled when schedule is null", async () => {
        vi.mocked(listBackups).mockResolvedValue({data: {backups: []}} as never);
        vi.mocked(getBackupSchedule).mockResolvedValue({
            data: sched({backup_schedule: null}),
        } as never);
        render(<BackupsTab serverId="s1"/>);
        await waitFor(() => expect(screen.queryByText("Loading backups…")).not.toBeInTheDocument());
        expect(screen.getByText("Disabled")).toBeInTheDocument();
    });

    it("edit schedule reveals editor, Save calls API", async () => {
        const user = userEvent.setup();
        await renderWith([]);
        await user.click(screen.getByRole("button", {name: /edit/i}));

        const cronInput = screen.getByPlaceholderText("0 2 * * *");
        const maxInput = screen.getByDisplayValue("10");
        await user.clear(cronInput);
        await user.type(cronInput, "0 4 * * *");
        await user.clear(maxInput);
        await user.type(maxInput, "3");

        await user.click(screen.getByRole("button", {name: /^save$/i}));
        await waitFor(() =>
            expect(updateBackupSchedule).toHaveBeenCalledWith({
                path: {id: "s1"},
                body: {backup_schedule: "0 4 * * *", backup_max_count: 3},
            }),
        );
    });

    it("cancel schedule edit reverts inputs", async () => {
        const user = userEvent.setup();
        await renderWith([]);
        await user.click(screen.getByRole("button", {name: /edit/i}));

        const cronInput = screen.getByPlaceholderText("0 2 * * *");
        await user.clear(cronInput);
        await user.type(cronInput, "0 4 * * *");

        await user.click(screen.getByRole("button", {name: /cancel/i}));
        expect(screen.queryByPlaceholderText("0 2 * * *")).not.toBeInTheDocument();
    });

    it("schedule error shown on save failure", async () => {
        const user = userEvent.setup();
        await renderWith([]);
        vi.mocked(updateBackupSchedule).mockResolvedValue({error: {message: "Invalid cron"}} as never);
        await user.click(screen.getByRole("button", {name: /edit/i}));
        await user.click(screen.getByRole("button", {name: /^save$/i}));
        await waitFor(() => expect(screen.getByText("Invalid cron")).toBeInTheDocument());
    });

    it("shows progress bar for IN_PROGRESS backup", async () => {
        await renderWith([b({id: "b1", status: "IN_PROGRESS"})]);
        expect(screen.getByText("Backing up…")).toBeInTheDocument();
    });

    it("refresh button re-fetches data", async () => {
        const user = userEvent.setup();
        await renderWith([b()]);
        expect(listBackups).toHaveBeenCalledTimes(1);

        await user.click(screen.getByRole("button", {name: /refresh/i}));
        await waitFor(() => expect(listBackups).toHaveBeenCalledTimes(2));
        expect(getBackupSchedule).toHaveBeenCalledTimes(2);
    });

    it("delete row not rendered for IN_PROGRESS backups", async () => {
        await renderWith([b({status: "IN_PROGRESS"})]);
        expect(screen.queryByTitle("Delete backup")).not.toBeInTheDocument();
    });
});
