import {describe, expect, it, vi} from "vitest";
import {act, renderHook, waitFor} from "@testing-library/react";
import {useConfigSection} from "../useConfigSection";

describe("useConfigSection", () => {
    it("is prop-driven when there is no load: save snapshots the draft", async () => {
        const persist = vi.fn().mockResolvedValue({data: undefined});
        const {result} = renderHook(() => useConfigSection<string>({initial: "stop", persist}));

        expect(result.current.draft).toBe("stop");
        expect(result.current.isDirty).toBe(false);
        expect(result.current.loading).toBe(false);

        act(() => result.current.setDraft("quit"));
        expect(result.current.isDirty).toBe(true);

        await act(async () => {
            await result.current.save();
        });

        expect(persist).toHaveBeenCalledWith("quit");
        expect(result.current.isDirty).toBe(false);
    });

    it("keeps the draft dirty and surfaces the error when persist fails", async () => {
        const persist = vi.fn().mockResolvedValue({error: {message: "nope"}});
        const {result} = renderHook(() => useConfigSection<string>({initial: "stop", persist}));

        act(() => result.current.setDraft("quit"));
        await act(async () => {
            await result.current.save();
        });

        expect(result.current.error).toBe("nope");
        expect(result.current.isDirty).toBe(true);
    });

    it("discard restores the last saved value", async () => {
        const persist = vi.fn().mockResolvedValue({data: undefined});
        const {result} = renderHook(() => useConfigSection<string>({initial: "stop", persist}));

        act(() => result.current.setDraft("quit"));
        act(() => result.current.discard());

        expect(result.current.draft).toBe("stop");
        expect(result.current.isDirty).toBe(false);
    });

    it("loads on mount and reloads after a successful save, returning the persist data", async () => {
        const load = vi.fn().mockResolvedValue({data: {motd: "hi"}});
        const persist = vi.fn().mockResolvedValue({data: {forwarding_warnings: ["w"]}});
        const {result} = renderHook(() => useConfigSection<{ motd: string }>({
            initial: {motd: ""},
            load,
            persist,
        }));

        await waitFor(() => expect(result.current.loading).toBe(false));
        expect(result.current.draft).toEqual({motd: "hi"});

        act(() => result.current.setDraft({motd: "bye"}));

        let saved: { data?: unknown } | undefined;
        await act(async () => {
            saved = await result.current.save();
        });

        expect(saved?.data).toEqual({forwarding_warnings: ["w"]});
        expect(load).toHaveBeenCalledTimes(2);
        expect(result.current.draft).toEqual({motd: "hi"});
    });

    it("surfaces a load error and stops loading", async () => {
        const load = vi.fn().mockResolvedValue({error: {message: "boom"}});
        const persist = vi.fn();
        const {result} = renderHook(() => useConfigSection<string>({initial: "", load, persist}));

        await waitFor(() => expect(result.current.loading).toBe(false));
        expect(result.current.error).toBe("boom");
    });

    it("supports an updater passed to setDraft", async () => {
        const persist = vi.fn();
        const {result} = renderHook(() => useConfigSection<{ a: number }>({initial: {a: 1}, persist}));

        act(() => result.current.setDraft((p) => ({a: p.a + 1})));

        expect(result.current.draft).toEqual({a: 2});
        expect(result.current.isDirty).toBe(true);
    });
});
