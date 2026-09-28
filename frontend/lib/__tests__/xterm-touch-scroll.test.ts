import {describe, it, expect, vi, beforeEach} from "vitest";
import type {Terminal} from "@xterm/xterm";
import {accumulateScroll, attachXtermTouchScroll, measureRowHeight} from "../xterm-touch-scroll";

interface FakeTerminal {
    rows: number;
    options: {fontSize: number; lineHeight: number};
    scrollLines: ReturnType<typeof vi.fn>;
}

function fakeTerminal(overrides: Partial<{rows: number; fontSize: number; lineHeight: number}> = {}): FakeTerminal {
    return {
        rows: overrides.rows ?? 24,
        options: {
            fontSize: overrides.fontSize ?? 13,
            lineHeight: overrides.lineHeight ?? 1.4,
        },
        scrollLines: vi.fn(),
    };
}

function asTerminal(term: FakeTerminal): Terminal {
    return term as unknown as Terminal;
}

function touchEvent(type: string, ys: number[]): Event {
    const event = new Event(type, {cancelable: true});
    Object.defineProperty(event, "touches", {
        value: ys.map((clientY) => ({clientY})),
    });
    return event;
}

describe("accumulateScroll", () => {
    it("returns zero lines for no travel", () => {
        expect(accumulateScroll(0, 0, 20)).toEqual({lines: 0, remainderPx: 0});
    });

    it("converts whole-row travel into lines", () => {
        expect(accumulateScroll(0, 60, 20)).toEqual({lines: 3, remainderPx: 0});
    });

    it("carries the sub-row remainder into the next step", () => {
        const first = accumulateScroll(0, 30, 20);
        expect(first).toEqual({lines: 1, remainderPx: 10});
        const second = accumulateScroll(first.remainderPx, 25, 20);
        expect(second).toEqual({lines: 1, remainderPx: 15});
    });

    it("carries negative remainders for reverse travel", () => {
        expect(accumulateScroll(0, -30, 20)).toEqual({lines: -1, remainderPx: -10});
    });

    it("guards against a zero row height", () => {
        expect(accumulateScroll(5, 10, 0)).toEqual({lines: 0, remainderPx: 0});
    });
});

describe("measureRowHeight", () => {
    it("divides the rendered screen height by the row count", () => {
        const host = document.createElement("div");
        const screen = document.createElement("div");
        screen.className = "xterm-screen";
        Object.defineProperty(screen, "getBoundingClientRect", {
            value: () => ({height: 240}) as DOMRect,
        });
        host.appendChild(screen);
        expect(measureRowHeight(host, asTerminal(fakeTerminal({rows: 24})))).toBe(10);
    });

    it("falls back to font metrics when the screen is absent", () => {
        const host = document.createElement("div");
        expect(measureRowHeight(host, asTerminal(fakeTerminal({fontSize: 13, lineHeight: 1.5})))).toBeCloseTo(19.5);
    });
});

describe("attachXtermTouchScroll", () => {
    let host: HTMLElement;
    let term: FakeTerminal;

    beforeEach(() => {
        document.body.innerHTML = "";
        host = document.createElement("div");
        const screen = document.createElement("div");
        screen.className = "xterm-screen";
        Object.defineProperty(screen, "getBoundingClientRect", {
            value: () => ({height: 200}) as DOMRect,
        });
        host.appendChild(screen);
        document.body.appendChild(host);
        term = fakeTerminal({rows: 20});
    });

    it("removes every listener on cleanup", () => {
        const remove = vi.spyOn(host, "removeEventListener");
        const detach = attachXtermTouchScroll(host, asTerminal(term));
        detach();
        expect(remove).toHaveBeenCalledWith("touchstart", expect.any(Function));
        expect(remove).toHaveBeenCalledWith("touchmove", expect.any(Function));
        expect(remove).toHaveBeenCalledWith("touchend", expect.any(Function));
        expect(remove).toHaveBeenCalledWith("touchcancel", expect.any(Function));
    });

    it("does not scroll within the dead zone", () => {
        attachXtermTouchScroll(host, asTerminal(term));
        host.dispatchEvent(touchEvent("touchstart", [100]));
        host.dispatchEvent(touchEvent("touchmove", [105]));
        expect(term.scrollLines).not.toHaveBeenCalled();
    });

    it("scrolls backwards while dragging down past the dead zone", () => {
        attachXtermTouchScroll(host, asTerminal(term));
        host.dispatchEvent(touchEvent("touchstart", [100]));
        host.dispatchEvent(touchEvent("touchmove", [130]));
        expect(term.scrollLines).toHaveBeenCalledWith(-3);
    });

    it("scrolls forwards while dragging up", () => {
        attachXtermTouchScroll(host, asTerminal(term));
        host.dispatchEvent(touchEvent("touchstart", [100]));
        host.dispatchEvent(touchEvent("touchmove", [70]));
        expect(term.scrollLines).toHaveBeenCalledWith(3);
    });

    it("prevents default once the drag is claimed", () => {
        attachXtermTouchScroll(host, asTerminal(term));
        host.dispatchEvent(touchEvent("touchstart", [100]));
        const move = touchEvent("touchmove", [130]);
        host.dispatchEvent(move);
        expect(move.defaultPrevented).toBe(true);
    });

    it("ignores multi-touch gestures", () => {
        attachXtermTouchScroll(host, asTerminal(term));
        host.dispatchEvent(touchEvent("touchstart", [100, 120]));
        host.dispatchEvent(touchEvent("touchmove", [140, 160]));
        expect(term.scrollLines).not.toHaveBeenCalled();
    });
});
