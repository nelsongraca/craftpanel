import {describe, it, expect, vi, beforeEach} from "vitest";
import {render, screen, waitFor, fireEvent} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

vi.mock("@/lib/generated/sdk.gen", () => ({
    listGroups: vi.fn(),
    createGroup: vi.fn(),
    updateGroup: vi.fn(),
    deleteGroup: vi.fn(),
    setGroupPermissions: vi.fn(),
}));

vi.mock("@/app/components/PageHeader", () => ({
    default: vi.fn(
        ({title, subtitle, action}: Record<string, any>) => (
            <div>
                {title && <h1>{title}</h1>}
                {subtitle && <p>{subtitle}</p>}
                {action}
            </div>
        ),
    ),
}));

import {
    listGroups, createGroup, updateGroup, deleteGroup, setGroupPermissions,
} from "@/lib/generated/sdk.gen";
import GroupsPage from "../page";

function group(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        id: "g1",
        name: "Server Admin",
        is_system: true,
        permissions: ["server.*"],
        created_at: "2026-01-01T00:00:00Z",
        ...overrides,
    };
}

function deferred<T>(): { promise: Promise<T>; resolve: (v: T) => void } {
    let resolve!: (v: T) => void;
    const promise = new Promise<T>((r) => { resolve = r; });
    return {promise, resolve};
}

async function renderWith(
    mocks: {
        groups?: Record<string, unknown>[];
    } = {},
) {
    const {groups: g = [group()]} = mocks;
    vi.mocked(listGroups).mockResolvedValue({data: g as any});
    const ui = render(<GroupsPage/>);
    await waitFor(() => expect(screen.queryByText("Loading…")).toBeNull());
    return ui;
}

describe("GroupsPage", () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it("renders loading state initially", () => {
        const deferred_ = deferred();
        vi.mocked(listGroups).mockReturnValue(deferred_.promise as any);
        render(<GroupsPage/>);
        expect(screen.getByText("Loading…")).toBeTruthy();
        deferred_.resolve({data: [group() as any]});
    });

    it("renders empty state when no groups", async () => {
        await renderWith({groups: []});
        expect(screen.getByText("No groups.")).toBeTruthy();
    });

    it("renders list with groups", async () => {
        await renderWith({
            groups: [
                group({id: "g1", name: "Admins", is_system: true, permissions: ["*"]}),
                group({id: "g2", name: "Mods", is_system: false, permissions: ["server.view", "server.console"]}),
            ],
        });
        expect(screen.getByText("Admins")).toBeTruthy();
        expect(screen.getByText("Mods")).toBeTruthy();
    });

    it("shows lock icon for system groups", async () => {
        await renderWith();
        expect(document.querySelector("span[title='System group']")).toBeTruthy();
    });

    it("shows permission chips", async () => {
        await renderWith({
            groups: [group({id: "g1", name: "Custom", is_system: false, permissions: ["server.view", "server.console"]})],
        });
        expect(screen.getByText("server.view")).toBeTruthy();
        expect(screen.getByText("server.console")).toBeTruthy();
    });

    it("shows -- when permissions are empty", async () => {
        await renderWith({
            groups: [group({id: "g1", name: "Empty", is_system: false, permissions: []})],
        });
        expect(screen.getByText("—")).toBeTruthy();
    });

    it("shows +N more when >5 permissions", async () => {
        const perms = Array.from({length: 7}, (_, i) => `server.perm${i}`);
        await renderWith({
            groups: [group({id: "g1", name: "Many", is_system: false, permissions: perms})],
        });
        expect(screen.getByText("+2 more")).toBeTruthy();
    });

    it("renders edit/delete buttons for non-system groups", async () => {
        await renderWith({
            groups: [group({id: "g1", name: "Custom", is_system: false, permissions: []})],
        });
        expect(screen.getByTitle("Edit")).toBeTruthy();
        expect(screen.getByTitle("Delete")).toBeTruthy();
    });

    it("hides edit/delete buttons for system groups", async () => {
        await renderWith();
        expect(screen.queryByTitle("Edit")).toBeNull();
        expect(screen.queryByTitle("Delete")).toBeNull();
    });

    it("'New Group' button opens create modal", async () => {
        await renderWith({groups: []});
        const user = userEvent.setup();
        await user.click(screen.getByText("New Group"));
        expect(screen.getByText("Create")).toBeTruthy();
    });

    it("create modal submits name and permissions", async () => {
        vi.mocked(createGroup).mockResolvedValue({data: {id: "new-g"} as any, error: undefined} as any);
        vi.mocked(setGroupPermissions).mockResolvedValue({error: undefined} as any);
        vi.mocked(listGroups).mockResolvedValue({data: []} as any);
        await renderWith({groups: []});

        const user = userEvent.setup();
        await user.click(screen.getByText("New Group"));
        const inputs = screen.getAllByRole("textbox");
        await user.type(inputs[0], "NewGroup");

        const checkboxes = screen.getAllByRole("checkbox");
        await user.click(checkboxes[0]);
        await user.click(screen.getByText("Create"));

        await waitFor(() => {
            expect(createGroup).toHaveBeenCalled();
            expect(setGroupPermissions).toHaveBeenCalled();
        });
    });

    it("create modal error shown on API failure", async () => {
        vi.mocked(createGroup).mockResolvedValue({error: {message: "Name taken"} as any} as any);
        await renderWith({groups: []});

        const user = userEvent.setup();
        await user.click(screen.getByText("New Group"));
        const inputs = screen.getAllByRole("textbox");
        await user.type(inputs[0], "Duplicate");
        await user.click(screen.getByText("Create"));

        await waitFor(() => {
            expect(screen.getByText("Name taken")).toBeTruthy();
        });
    });

    it("edit modal opens with pre-filled data", async () => {
        await renderWith({
            groups: [group({id: "g1", name: "Custom", is_system: false, permissions: ["server.view"]})],
        });
        const user = userEvent.setup();
        await user.click(screen.getAllByTitle("Edit")[0]);
        await waitFor(() => {
            expect(screen.getByDisplayValue("Custom")).toBeTruthy();
        });
    });

    it("edit modal calls updateGroup and setGroupPermissions on Save", async () => {
        vi.mocked(updateGroup).mockResolvedValue({error: undefined} as any);
        vi.mocked(setGroupPermissions).mockResolvedValue({error: undefined} as any);
        await renderWith({
            groups: [group({id: "g1", name: "Custom", is_system: false, permissions: ["server.view"]})],
        });
        const user = userEvent.setup();
        await user.click(screen.getAllByTitle("Edit")[0]);
        await user.clear(screen.getByDisplayValue("Custom"));
        await user.type(screen.getByDisplayValue(""), "Updated");
        await user.click(screen.getByText("Save"));
        await waitFor(() => {
            expect(updateGroup).toHaveBeenCalled();
            expect(setGroupPermissions).toHaveBeenCalled();
        });
    });

    it("delete modal opens on trash click", async () => {
        await renderWith({
            groups: [group({id: "g1", name: "Custom", is_system: false, permissions: []})],
        });
        expect(screen.getByText("Custom")).toBeTruthy();
        const deleteBtns = screen.getAllByTitle("Delete");
        expect(deleteBtns.length).toBeGreaterThan(0);
        fireEvent.click(deleteBtns[0]);
        await waitFor(() => {
            expect(screen.getByText("Delete Group")).toBeTruthy();
        });
    });

    it("delete calls deleteGroup and reloads", async () => {
        vi.mocked(deleteGroup).mockResolvedValue({error: undefined} as any);
        vi.mocked(listGroups).mockResolvedValue({data: []} as any);
        await renderWith({
            groups: [group({id: "g1", name: "Custom", is_system: false, permissions: []})],
        });
        const deleteBtns = screen.getAllByTitle("Delete");
        fireEvent.click(deleteBtns[0]);
        fireEvent.click(screen.getByText("Delete"));
        await waitFor(() => {
            expect(deleteGroup).toHaveBeenCalledWith({path: {id: "g1"}});
            expect(listGroups).toHaveBeenCalledTimes(2);
        });
    });

    it("delete error shown when API fails", async () => {
        vi.mocked(deleteGroup).mockResolvedValue({error: {message: "Cannot delete"} as any} as any);
        await renderWith({
            groups: [group({id: "g1", name: "Custom", is_system: false, permissions: []})],
        });
        const deleteBtns = screen.getAllByTitle("Delete");
        fireEvent.click(deleteBtns[0]);
        fireEvent.click(screen.getByText("Delete"));
        await waitFor(() => {
            expect(screen.getByText("Cannot delete")).toBeTruthy();
        });
    });
});
