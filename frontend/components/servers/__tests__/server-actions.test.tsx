import {beforeEach, describe, expect, it, vi} from "vitest";
import {act, renderHook, waitFor} from "@testing-library/react";
import {allowedServerActions, useServerActions} from "../server-actions";
import type {Server} from "@/lib/types";

const {pushMock} = vi.hoisted(() => ({pushMock: vi.fn()}));

vi.mock("next/navigation", () => ({useRouter: () => ({push: pushMock})}));
vi.mock("@/lib/generated/sdk.gen", () => ({
    startServer: vi.fn(),
    stopServer: vi.fn(),
    restartServer: vi.fn(),
    forceStopServer: vi.fn(),
    deleteServer: vi.fn(),
}));
vi.mock("@/lib/hooks/useConfirmDialog", () => ({
    useConfirmDialog: () => ({confirm: (opts: {onConfirm: () => void}) => opts.onConfirm(), dialog: null}),
}));

import * as sdk from "@/lib/generated/sdk.gen";

const server = (status: string, extra: Partial<Server> = {}): Server =>
    ({id: "s1", display_name: "Survival", status, disabled: false, expires_at: null, ...extra}) as Server;

describe("allowedServerActions", () => {
    it("allows start and delete only on STOPPED, plus duplicate with server.create", () => {
        expect(allowedServerActions(server("STOPPED"), ["*"])).toEqual(["start", "duplicate", "delete"]);
    });

    it("allows stop while HEALTHY, STARTING or UNHEALTHY", () => {
        const perms = ["server.stop"];
        expect(allowedServerActions(server("HEALTHY"), perms)).toEqual(["stop"]);
        expect(allowedServerActions(server("STARTING"), perms)).toEqual(["stop"]);
        expect(allowedServerActions(server("UNHEALTHY"), perms)).toEqual(["stop"]);
    });

    it("allows force stop only while STOPPING", () => {
        expect(allowedServerActions(server("STOPPING"), ["server.force_stop"])).toEqual(["forceStop"]);
    });

    it("allows restart only while HEALTHY", () => {
        expect(allowedServerActions(server("HEALTHY"), ["server.restart"])).toEqual(["restart"]);
        expect(allowedServerActions(server("STOPPED"), ["server.restart"])).toEqual([]);
    });

    it("hides start and restart for a disabled or expired server", () => {
        expect(allowedServerActions(server("STOPPED", {disabled: true}), ["server.start"])).toEqual([]);
        expect(allowedServerActions(server("HEALTHY", {expires_at: "2000-01-01T00:00:00Z"}), ["server.restart"])).toEqual([]);
    });

    it("returns nothing without the matching permission", () => {
        expect(allowedServerActions(server("STOPPED"), [])).toEqual([]);
    });
});

describe("useServerActions", () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.mocked(sdk.startServer).mockResolvedValue({data: undefined, error: undefined} as never);
        vi.mocked(sdk.deleteServer).mockResolvedValue({data: undefined, error: undefined} as never);
    });

    function render(onChanged = vi.fn(), onDeleted = vi.fn()) {
        return renderHook(() =>
            useServerActions({permissions: ["*"], serverPermissionsMap: {}, onChanged, onDeleted}),
        );
    }

    it("runs a lifecycle action against the server and refreshes", async () => {
        const onChanged = vi.fn();
        const {result} = render(onChanged);

        await act(async () => {
            await result.current.run("s1", "start");
        });

        expect(sdk.startServer).toHaveBeenCalledWith({path: {id: "s1"}});
        expect(onChanged).toHaveBeenCalled();
        expect(result.current.pendingFor("s1")).toBeUndefined();
    });

    it("surfaces the API error and does not refresh", async () => {
        vi.mocked(sdk.startServer).mockResolvedValue({data: undefined, error: {message: "nope"}} as never);
        const onChanged = vi.fn();
        const {result} = render(onChanged);

        await act(async () => {
            await result.current.run("s1", "start");
        });

        expect(result.current.actionError).toBe("nope");
        expect(onChanged).not.toHaveBeenCalled();
    });

    it("deletes through the confirm dialog and calls onDeleted", async () => {
        const onDeleted = vi.fn();
        const {result} = render(vi.fn(), onDeleted);

        await act(async () => {
            result.current.remove(server("STOPPED"));
        });

        await waitFor(() => expect(sdk.deleteServer).toHaveBeenCalledWith({path: {id: "s1"}}));
        expect(onDeleted).toHaveBeenCalled();
    });

    it("routes duplicate to the clone form", () => {
        const {result} = render();

        act(() => result.current.duplicate(server("STOPPED")));

        expect(pushMock).toHaveBeenCalledWith("/servers/new?clone=s1");
    });
});
