import {beforeEach, describe, expect, it, vi} from "vitest";
import {act, renderHook, waitFor} from "@testing-library/react";
import {allowedNodeActions, useNodeActions} from "../node-actions";
import type {Node} from "@/lib/types";

vi.mock("@/lib/generated/sdk.gen", () => ({
    trustNode: vi.fn(),
    rejectNode: vi.fn(),
    rotateNodeToken: vi.fn(),
    shutdownNode: vi.fn(),
    decommissionNode: vi.fn(),
}));
vi.mock("@/lib/hooks/useConfirmDialog", () => ({
    useConfirmDialog: () => ({confirm: (opts: {onConfirm: () => void}) => opts.onConfirm(), dialog: null}),
}));

import * as sdk from "@/lib/generated/sdk.gen";

const node = (status: string, extra: Partial<Node> = {}): Node =>
    ({id: "n1", display_name: "Node 1", status, ...extra}) as Node;

describe("allowedNodeActions", () => {
    it("offers trust and reject while PENDING", () => {
        expect(allowedNodeActions(node("PENDING"), 0)).toEqual(["trust", "reject"]);
    });

    it("offers rotate, shutdown and decommission for an empty ACTIVE node", () => {
        expect(allowedNodeActions(node("ACTIVE"), 0)).toEqual(["rotate", "shutdown", "decommission"]);
    });

    it("hides decommission when the node still has servers", () => {
        expect(allowedNodeActions(node("ACTIVE"), 2)).toEqual(["rotate", "shutdown"]);
    });

    it("hides decommission for an already-decommissioned node", () => {
        expect(allowedNodeActions(node("DECOMMISSIONED"), 0)).toEqual(["rotate"]);
    });
});

describe("useNodeActions", () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.mocked(sdk.trustNode).mockResolvedValue({data: undefined, error: undefined} as never);
        vi.mocked(sdk.rejectNode).mockResolvedValue({data: undefined, error: undefined} as never);
        vi.mocked(sdk.rotateNodeToken).mockResolvedValue({data: {node_key: "raw-key"}, error: undefined} as never);
        vi.mocked(sdk.shutdownNode).mockResolvedValue({data: undefined, error: undefined} as never);
        vi.mocked(sdk.decommissionNode).mockResolvedValue({data: undefined, error: undefined} as never);
    });

    function render(onChanged = vi.fn(), onTokenRotated = vi.fn(), onDecommissioned = vi.fn()) {
        return renderHook(() => useNodeActions({onChanged, onTokenRotated, onDecommissioned}));
    }

    it("trusts a node and refreshes", async () => {
        const onChanged = vi.fn();
        const {result} = render(onChanged);

        await act(async () => {
            await result.current.trust("n1");
        });

        expect(sdk.trustNode).toHaveBeenCalledWith({path: {id: "n1"}});
        expect(onChanged).toHaveBeenCalled();
        expect(result.current.pendingFor("n1")).toBeUndefined();
    });

    it("rejects through the confirm dialog", async () => {
        const {result} = render();

        await act(async () => {
            result.current.reject("n1");
        });

        await waitFor(() => expect(sdk.rejectNode).toHaveBeenCalledWith({path: {id: "n1"}}));
    });

    it("rotates the key and hands the new key back", async () => {
        const onTokenRotated = vi.fn();
        const {result} = render(vi.fn(), onTokenRotated);

        await act(async () => {
            result.current.rotate("n1");
        });

        await waitFor(() => expect(onTokenRotated).toHaveBeenCalledWith("raw-key"));
    });

    it("shuts a node down through the confirm dialog", async () => {
        const {result} = render();

        await act(async () => {
            result.current.shutdown(node("ACTIVE"));
        });

        await waitFor(() => expect(sdk.shutdownNode).toHaveBeenCalledWith({path: {id: "n1"}}));
    });

    it("decommissions and runs the decommissioned callback", async () => {
        const onDecommissioned = vi.fn();
        const {result} = render(vi.fn(), vi.fn(), onDecommissioned);

        await act(async () => {
            result.current.decommission(node("ACTIVE"));
        });

        await waitFor(() => expect(onDecommissioned).toHaveBeenCalled());
    });

    it("surfaces the API error", async () => {
        vi.mocked(sdk.trustNode).mockResolvedValue({data: undefined, error: {message: "denied"}} as never);
        const {result} = render();

        await act(async () => {
            await result.current.trust("n1");
        });

        expect(result.current.actionError).toBe("denied");
    });
});
