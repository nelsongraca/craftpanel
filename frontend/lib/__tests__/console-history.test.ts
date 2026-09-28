import {describe, it, expect, beforeEach} from "vitest";
import {loadConsoleHistory, saveConsoleHistory} from "../console-history";

const KEY = "craftpanel:console-history:srv-1";

describe("console history persistence", () => {
    beforeEach(() => {
        window.localStorage.clear();
    });

    it("returns an empty history when nothing is stored", () => {
        expect(loadConsoleHistory("srv-1")).toEqual([]);
    });

    it("round-trips saved commands", () => {
        saveConsoleHistory("srv-1", ["list", "say hi"]);
        expect(loadConsoleHistory("srv-1")).toEqual(["list", "say hi"]);
    });

    it("keeps history isolated per server", () => {
        saveConsoleHistory("srv-1", ["list"]);
        saveConsoleHistory("srv-2", ["stop"]);
        expect(loadConsoleHistory("srv-1")).toEqual(["list"]);
        expect(loadConsoleHistory("srv-2")).toEqual(["stop"]);
    });

    it("caps stored history at the most recent 100 commands", () => {
        const commands = Array.from({length: 150}, (_, i) => `cmd-${i}`);
        saveConsoleHistory("srv-1", commands);
        const stored = loadConsoleHistory("srv-1");
        expect(stored).toHaveLength(100);
        expect(stored[0]).toBe("cmd-50");
        expect(stored.at(-1)).toBe("cmd-149");
    });

    it("ignores malformed JSON", () => {
        window.localStorage.setItem(KEY, "{not json");
        expect(loadConsoleHistory("srv-1")).toEqual([]);
    });

    it("ignores non-array payloads", () => {
        window.localStorage.setItem(KEY, JSON.stringify({nope: true}));
        expect(loadConsoleHistory("srv-1")).toEqual([]);
    });

    it("drops non-string entries", () => {
        window.localStorage.setItem(KEY, JSON.stringify(["ok", 1, null, "fine"]));
        expect(loadConsoleHistory("srv-1")).toEqual(["ok", "fine"]);
    });
});
