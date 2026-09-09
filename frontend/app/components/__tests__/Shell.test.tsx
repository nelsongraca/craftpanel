import {describe, it, expect, vi, beforeEach, afterEach} from "vitest";
import {render, screen} from "@testing-library/react";

vi.mock("@/lib/auth-context", () => ({
    useAuth: vi.fn(() => ({
        user: {username: "admin", email: "admin@test", permissions: []},
        logout: vi.fn(),
        logoutAll: vi.fn(),
    })),
}));

import {useAuth} from "@/lib/auth-context";
import Shell from "../Shell";

function useAuthAs(permissions: string[]) {
    (vi.mocked(useAuth) as ReturnType<typeof vi.fn>).mockReturnValue({
        user: {username: "admin", email: "admin@test", permissions},
        logout: vi.fn(),
        logoutAll: vi.fn(),
    });
}

describe("Shell sidebar", () => {
    beforeEach(() => {
        vi.clearAllMocks();
        useAuthAs(["*"]);
        vi.stubGlobal("fetch", vi.fn().mockResolvedValue({json: async () => ({})}));
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("shows Networks menu item with server.view", () => {
        useAuthAs(["server.view"]);
        render(<Shell>content</Shell>);
        expect(screen.getByText("Networks")).toBeTruthy();
    });

    it("hides Networks menu item without server.view", () => {
        useAuthAs(["server.view.other"]);
        render(<Shell>content</Shell>);
        expect(screen.queryByText("Networks")).toBeNull();
    });

    it("always shows All Servers menu item", () => {
        useAuthAs([]);
        render(<Shell>content</Shell>);
        expect(screen.getByText("All Servers")).toBeTruthy();
    });

    it("hides Nodes menu item without system.nodes", () => {
        useAuthAs([]);
        render(<Shell>content</Shell>);
        expect(screen.queryByText("Nodes")).toBeNull();
        expect(screen.queryByText("Infrastructure")).toBeNull();
    });

    it("shows Nodes menu item with system.nodes", () => {
        useAuthAs(["system.nodes"]);
        render(<Shell>content</Shell>);
        expect(screen.getByText("Nodes")).toBeTruthy();
    });
});