import {describe, it, expect, vi, beforeEach, afterEach} from "vitest";
import {render, screen} from "@testing-library/react";

vi.mock("@/lib/auth-context", () => ({
    useAuth: vi.fn(() => ({
        user: {username: "admin", email: "admin@test", permissions: []},
        logout: vi.fn(),
    })),
}));

import {useAuth} from "@/lib/auth-context";
import Shell from "../Shell";

function useAuthAs(permissions: string[]) {
    (vi.mocked(useAuth) as ReturnType<typeof vi.fn>).mockReturnValue({
        user: {username: "admin", email: "admin@test", permissions},
        logout: vi.fn(),
    });
}

describe("Shell sidebar", () => {
    beforeEach(() => {
        vi.clearAllMocks();
        useAuthAs(["*"]);
        vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
            json: async () => ({frontendVersion: "abc1234", masterVersion: "abc1234", versionMismatch: false}),
        }));
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("shows Networks menu item with network.view", () => {
        useAuthAs(["network.view"]);
        render(<Shell>content</Shell>);
        expect(screen.getByText("Networks")).toBeTruthy();
    });

    it("hides Networks menu item without network.view", () => {
        useAuthAs(["server.view"]);
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

    it("shows Groups menu item with system.groups", () => {
        useAuthAs(["system.groups"]);
        render(<Shell>content</Shell>);
        expect(screen.getByText("Groups")).toBeTruthy();
    });

    it("hides Groups menu item without system.groups", () => {
        useAuthAs(["system.users"]);
        render(<Shell>content</Shell>);
        expect(screen.queryByText("Groups")).toBeNull();
    });

    it("shows Alerts menu item with system.alerts", () => {
        useAuthAs(["system.alerts"]);
        render(<Shell>content</Shell>);
        expect(screen.getByText("Alerts")).toBeTruthy();
    });

    it("hides Alerts menu item without system.alerts", () => {
        useAuthAs(["system.nodes"]);
        render(<Shell>content</Shell>);
        expect(screen.queryByText("Alerts")).toBeNull();
    });
});

describe("Shell footer versions", () => {
    beforeEach(() => {
        vi.clearAllMocks();
        useAuthAs([]);
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("shows a single version when frontend and master match", async () => {
        vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
            json: async () => ({frontendVersion: "abc1234", masterVersion: "abc1234", versionMismatch: false}),
        }));
        render(<Shell>content</Shell>);
        expect(await screen.findByText("version abc1234")).toBeTruthy();
    });

    it("shows both versions and a warning when they differ", async () => {
        vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
            json: async () => ({frontendVersion: "abc1234", masterVersion: "def5678", versionMismatch: true}),
        }));
        render(<Shell>content</Shell>);
        expect(await screen.findByText("frontend abc1234 · master def5678")).toBeTruthy();
        expect(screen.getByText("version mismatch")).toBeTruthy();
    });
});