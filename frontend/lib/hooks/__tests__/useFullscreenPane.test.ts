import {describe, it, expect, beforeEach} from "vitest";
import {renderHook, act} from "@testing-library/react";
import {useFullscreenPane} from "../useFullscreenPane";

describe("useFullscreenPane", () => {
    beforeEach(() => {
        document.body.style.overflow = "";
    });

    it("starts out of fullscreen", () => {
        const {result} = renderHook(() => useFullscreenPane());
        expect(result.current.fullscreen).toBe(false);
    });

    it("toggles fullscreen on and off", () => {
        const {result} = renderHook(() => useFullscreenPane());
        act(() => result.current.toggle());
        expect(result.current.fullscreen).toBe(true);
        act(() => result.current.toggle());
        expect(result.current.fullscreen).toBe(false);
    });

    it("locks and restores body scroll while fullscreen", () => {
        document.body.style.overflow = "auto";
        const {result} = renderHook(() => useFullscreenPane());
        act(() => result.current.toggle());
        expect(document.body.style.overflow).toBe("hidden");
        act(() => result.current.toggle());
        expect(document.body.style.overflow).toBe("auto");
    });

    it("exits fullscreen on Escape", () => {
        const {result} = renderHook(() => useFullscreenPane());
        act(() => result.current.toggle());
        act(() => {
            window.dispatchEvent(new KeyboardEvent("keydown", {key: "Escape"}));
        });
        expect(result.current.fullscreen).toBe(false);
    });

    it("does not lock body scroll when active is false", () => {
        const {result} = renderHook(() => useFullscreenPane(false));
        act(() => result.current.toggle());
        act(() => result.current.toggle());
        expect(document.body.style.overflow).toBe("");
    });

    it("exits fullscreen when active becomes false", () => {
        const {result, rerender} = renderHook(({active}) => useFullscreenPane(active), {
            initialProps: {active: true},
        });
        act(() => result.current.toggle());
        expect(result.current.fullscreen).toBe(true);
        rerender({active: false});
        expect(result.current.fullscreen).toBe(false);
    });
});
