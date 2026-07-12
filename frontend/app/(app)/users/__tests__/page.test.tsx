import type {ReactNode} from "react";
import {describe, it, expect, vi, beforeEach} from "vitest";
import {render, screen, waitFor, within} from "@testing-library/react";
import userEvent from "@testing-library/user-event";

vi.mock("@/lib/generated/sdk.gen", () => ({
    listUsers: vi.fn(),
    listGroups: vi.fn(),
    createUser: vi.fn(),
    updateUser: vi.fn(),
    deleteUser: vi.fn(),
    listUserAssignments: vi.fn(),
    createAssignment: vi.fn(),
    deleteAssignment: vi.fn(),
    listServers: vi.fn(),
    listNetworks: vi.fn(),
}));

vi.mock("@/app/components/PageHeader", () => ({
    default: vi.fn(
        ({title, subtitle, action}: { title?: string; subtitle?: string; action?: ReactNode }) => (
            <div>
                {title && <h1>{title}</h1>}
                {subtitle && <p>{subtitle}</p>}
                {action}
            </div>
        ),
    ),
}));

import {
    listUsers, listGroups, createUser, updateUser, deleteUser,
    listUserAssignments, createAssignment, deleteAssignment,
    listServers, listNetworks,
} from "@/lib/generated/sdk.gen";
import UsersPage from "../page";

// ── Helpers ──────────────────────────────────────────────────────────────────────

function user(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        id: "u1",
        username: "alice",
        email: "alice@example.com",
        is_active: true,
        created_at: "2026-01-15T10:00:00Z",
        ...overrides,
    };
}

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

function assignment(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        id: "a1",
        group_id: "g1",
        scope_type: "GLOBAL",
        scope_id: null,
        ...overrides,
    };
}

function deferred<T>(): {promise: Promise<T>; resolve: (v: T) => void} {
    let resolve!: (v: T) => void;
    const promise = new Promise<T>((r) => {
        resolve = r;
    });
    return {promise, resolve};
}

async function renderWith(
    mocks: {
        users?: Record<string, unknown>[];
        groups?: Record<string, unknown>[];
        assignments?: Record<string, unknown>[];
        servers?: Record<string, unknown>[];
        networks?: Record<string, unknown>[];
    } = {},
) {
    const {
        users: us = [user()],
        groups: gs = [group()],
        assignments: asg = [],
        servers: ss = [],
        networks: ns = [],
    } = mocks;

    vi.mocked(listUsers).mockResolvedValue({data: {users: us}} as never);
    vi.mocked(listGroups).mockResolvedValue({data: gs} as never);
    vi.mocked(listUserAssignments).mockResolvedValue({data: {assignments: asg}} as never);
    vi.mocked(listServers).mockResolvedValue({data: ss} as never);
    vi.mocked(listNetworks).mockResolvedValue({data: ns} as never);

    const result = render(<UsersPage/>);

    if (us.length > 0) {
        await waitFor(() => {
            expect(screen.queryAllByText(us[0].username as string).length).toBeGreaterThan(0);
        });
    } else {
        await waitFor(() => {
            expect(screen.getByText(/No users yet/i)).toBeInTheDocument();
        });
    }

    return result;
}

// ── Tests ────────────────────────────────────────────────────────────────────────

describe("UsersPage", () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    describe("Loading state", () => {
        it("shows Loading… while loading", async () => {
            const def = deferred<{ data: { users: Record<string, unknown>[] } }>();
            vi.mocked(listUsers).mockReturnValue(def.promise as never);
            vi.mocked(listGroups).mockResolvedValue({data: []} as never);

            render(<UsersPage/>);

            expect(screen.getByText("Loading…")).toBeInTheDocument();

            def.resolve({data: {users: []}});
            await waitFor(() => {
                expect(screen.queryByText("Loading…")).not.toBeInTheDocument();
            });
        });
    });

    describe("Empty state", () => {
        it('shows "No users yet" when list is empty', async () => {
            await renderWith({users: []});
            expect(screen.getByText(/No users yet/i)).toBeInTheDocument();
        });
    });

    describe("User list", () => {
        it("renders username, email, status, created date in table", async () => {
            const u = user({username: "bob", email: "bob@test.com", created_at: "2026-03-01T00:00:00Z"});
            await renderWith({users: [u]});

            expect(screen.getAllByText("bob").length).toBeGreaterThan(0);
            expect(screen.getAllByText("bob@test.com").length).toBeGreaterThan(0);
            expect(screen.getAllByText("Active").length).toBeGreaterThan(0);
            const dateStr = new Date("2026-03-01T00:00:00Z").toLocaleDateString();
            expect(screen.getAllByText(dateStr).length).toBeGreaterThan(0);
        });
    });

    describe("Inactive badge", () => {
        it("user with is_active: false shows Inactive text", async () => {
            const u = user({is_active: false});
            await renderWith({users: [u]});

            expect(screen.getAllByText("Inactive").length).toBeGreaterThan(0);
        });
    });

    describe("Create User modal", () => {
        it('clicking "New User" opens form, submitting calls createUser, on success refreshes list', async () => {
            const u = user();
            vi.mocked(createUser).mockResolvedValue({data: {}} as never);
            await renderWith({users: [u]});

            const userEv = userEvent.setup();
            await userEv.click(screen.getByRole("button", {name: /New User/i}));

            await waitFor(() => {
                expect(screen.getByText("Create User")).toBeInTheDocument();
            });

            const dialog = screen.getByRole("dialog");
            const [usernameInput, emailInput] = within(dialog).getAllByRole("textbox");
            const passwordInput = dialog.querySelector<HTMLInputElement>('input[type="password"]')!;
            await userEv.type(usernameInput, "newuser");
            await userEv.type(emailInput, "new@test.com");
            await userEv.type(passwordInput, "secret123");

            await userEv.click(screen.getByRole("button", {name: "Create"}));

            await waitFor(() => {
                expect(createUser).toHaveBeenCalledWith({
                    body: {username: "newuser", email: "new@test.com", password: "secret123"},
                });
            });
            expect(listUsers).toHaveBeenCalledTimes(2);
        });

        it("API error displays error message", async () => {
            const u = user();
            vi.mocked(createUser).mockResolvedValue({
                error: {message: "Email already taken"},
            } as never);
            await renderWith({users: [u]});

            const userEv = userEvent.setup();
            await userEv.click(screen.getByRole("button", {name: /New User/i}));

            await waitFor(() => {
                expect(screen.getByText("Create User")).toBeInTheDocument();
            });

            const dialog = screen.getByRole("dialog");
            const [usernameInput, emailInput] = within(dialog).getAllByRole("textbox");
            const passwordInput = dialog.querySelector<HTMLInputElement>('input[type="password"]')!;
            await userEv.type(usernameInput, "dup");
            await userEv.type(emailInput, "dup@test.com");
            await userEv.type(passwordInput, "secret");

            await userEv.click(screen.getByRole("button", {name: "Create"}));

            await waitFor(() => {
                expect(screen.getByText("Email already taken")).toBeInTheDocument();
            });
        });
    });

    describe("Edit User modal", () => {
        it("clicking pencil opens Edit modal with pre-filled values, Save calls updateUser", async () => {
            const u = user({username: "alice", email: "alice@example.com"});
            vi.mocked(updateUser).mockResolvedValue({data: {}} as never);
            await renderWith({users: [u]});

            const userEv = userEvent.setup();
            await userEv.click(screen.getAllByTitle("Edit")[0]);

            await waitFor(() => {
                expect(screen.getByText("Edit User")).toBeInTheDocument();
            });

            const usernameInput = screen.getByDisplayValue("alice");
            await userEv.clear(usernameInput);
            await userEv.type(usernameInput, "alice_updated");

            await userEv.click(screen.getByRole("button", {name: "Save"}));

            await waitFor(() => {
                expect(updateUser).toHaveBeenCalledWith(expect.objectContaining({
                    path: {id: "u1"},
                    body: expect.objectContaining({username: "alice_updated"}),
                }));
            });
        });

        it("toggling active checkbox sends is_active: false", async () => {
            const u = user({username: "alice", is_active: true});
            vi.mocked(updateUser).mockResolvedValue({data: {}} as never);
            await renderWith({users: [u]});

            const userEv = userEvent.setup();
            await userEv.click(screen.getAllByTitle("Edit")[0]);

            await waitFor(() => {
                expect(screen.getByText("Edit User")).toBeInTheDocument();
            });

            const cb = screen.getByRole("checkbox");
            await userEv.click(cb);

            await userEv.click(screen.getByRole("button", {name: "Save"}));

            await waitFor(() => {
                expect(updateUser).toHaveBeenCalledWith(expect.objectContaining({
                    path: {id: "u1"},
                    body: expect.objectContaining({is_active: false}),
                }));
            });
        });
    });

    describe("Delete User modal", () => {
        it("clicking trash opens confirm, Delete calls deleteUser, reloads", async () => {
            const u = user({username: "alice"});
            vi.mocked(deleteUser).mockResolvedValue({data: {}} as never);
            await renderWith({users: [u]});

            const userEv = userEvent.setup();
            await userEv.click(screen.getAllByTitle("Delete")[0]);

            await waitFor(() => {
                expect(screen.getByText("Delete User")).toBeInTheDocument();
            });
            expect(screen.getAllByText(/alice/).length).toBeGreaterThan(0);

            await userEv.click(within(screen.getByRole("dialog")).getByRole("button", {name: "Delete"}));

            await waitFor(() => {
                expect(deleteUser).toHaveBeenCalledWith({path: {id: "u1"}});
            });
            expect(listUsers).toHaveBeenCalledTimes(2);
        });

        it("error shown on failure", async () => {
            const u = user();
            vi.mocked(deleteUser).mockResolvedValue({
                error: {message: "Cannot delete last admin"},
            } as never);
            await renderWith({users: [u]});

            const userEv = userEvent.setup();
            await userEv.click(screen.getAllByTitle("Delete")[0]);

            await waitFor(() => {
                expect(screen.getByText("Delete User")).toBeInTheDocument();
            });

            await userEv.click(within(screen.getByRole("dialog")).getByRole("button", {name: "Delete"}));

            await waitFor(() => {
                expect(screen.getByText("Cannot delete last admin")).toBeInTheDocument();
            });
        });
    });

    describe("Assignments modal", () => {
        it("clicking group icon opens modal, loads assignments, displays current assignments", async () => {
            const u = user({username: "alice"});
            const g = group({id: "g1", name: "Server Admin"});
            const a = assignment({id: "a1", group_id: "g1", scope_type: "GLOBAL", scope_id: null});
            await renderWith({users: [u], groups: [g], assignments: [a]});

            const userEv = userEvent.setup();
            await userEv.click(screen.getAllByTitle("Manage groups")[0]);

            await waitFor(() => {
                expect(screen.getByText(/Groups — alice/)).toBeInTheDocument();
            });

            expect(screen.getAllByText("Server Admin").length).toBeGreaterThan(0);
            expect(screen.getAllByText("Global").length).toBeGreaterThan(0);
        });

        it("selecting group and scope, clicking Add calls createAssignment", async () => {
            const u = user();
            const g = group({id: "g1", name: "Operator"});
            vi.mocked(createAssignment).mockResolvedValue({data: {}} as never);
            await renderWith({users: [u], groups: [g]});

            const userEv = userEvent.setup();
            await userEv.click(screen.getAllByTitle("Manage groups")[0]);

            await waitFor(() => {
                expect(screen.getByText(/Groups —/)).toBeInTheDocument();
            });

            await userEv.selectOptions(screen.getAllByRole("combobox")[0], "g1");

            await userEv.click(screen.getByRole("button", {name: "Add"}));

            await waitFor(() => {
                expect(createAssignment).toHaveBeenCalledWith(expect.objectContaining({
                    path: {userId: "u1"},
                    body: expect.objectContaining({group_id: "g1", scope_type: "GLOBAL"}),
                }));
            });
        });

        it("not selecting group shows error", async () => {
            const u = user();
            await renderWith({users: [u]});

            const userEv = userEvent.setup();
            await userEv.click(screen.getAllByTitle("Manage groups")[0]);

            await waitFor(() => {
                expect(screen.getByText(/Groups —/)).toBeInTheDocument();
            });

            await userEv.click(screen.getByRole("button", {name: "Add"}));

            expect(screen.getByText("Select a group")).toBeInTheDocument();
        });

        it("no scope_id when SERVER/NETWORK shows error", async () => {
            const u = user();
            const g = group({id: "g1"});
            await renderWith({users: [u], groups: [g]});

            const userEv = userEvent.setup();
            await userEv.click(screen.getAllByTitle("Manage groups")[0]);

            await waitFor(() => {
                expect(screen.getByText(/Groups —/)).toBeInTheDocument();
            });

            await userEv.selectOptions(screen.getAllByRole("combobox")[0], "g1");
            await userEv.selectOptions(screen.getAllByRole("combobox")[1], "SERVER");

            await userEv.click(screen.getByRole("button", {name: "Add"}));

            expect(screen.getByText("Select a scope target")).toBeInTheDocument();
        });

        it("clicking trash on assignment calls deleteAssignment", async () => {
            const u = user();
            const g = group({id: "g1", name: "Viewer"});
            const a = assignment({id: "a1", group_id: "g1", scope_type: "GLOBAL"});
            vi.mocked(deleteAssignment).mockResolvedValue({data: {}} as never);
            await renderWith({users: [u], groups: [g], assignments: [a]});

            const userEv = userEvent.setup();
            await userEv.click(screen.getAllByTitle("Manage groups")[0]);

            await waitFor(() => {
                expect(screen.getAllByText("Viewer").length).toBeGreaterThan(0);
            });

            const removeBtn = within(screen.getByRole("list")).getByRole("button");
            expect(removeBtn).toBeInTheDocument();
            await userEv.click(removeBtn);

            await waitFor(() => {
                expect(deleteAssignment).toHaveBeenCalledWith({
                    path: {userId: "u1", assignmentId: "a1"},
                });
            });
        });
    });

    describe("Mobile cards", () => {
        it("users also rendered in mobile card layout", async () => {
            const u = user({username: "mobile-user", email: "m@test.com"});
            await renderWith({users: [u]});

            const mobileCards = document.querySelectorAll(".md\\:hidden");
            expect(mobileCards.length).toBeGreaterThan(0);
            expect(mobileCards[0].textContent).toContain("mobile-user");
            expect(mobileCards[0].textContent).toContain("m@test.com");
        });
    });
});
