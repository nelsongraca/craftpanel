import {describe, it, expect, beforeEach} from "vitest";
import {
    LANGUAGE_OVERRIDES_STORAGE_KEY,
    loadLanguageOverrides,
    saveLanguageOverrides,
    setLanguageOverride,
} from "../file-language-overrides";

const KEY = LANGUAGE_OVERRIDES_STORAGE_KEY;

describe("file language overrides persistence", () => {
    beforeEach(() => {
        window.localStorage.clear();
    });

    it("returns no overrides when nothing is stored", () => {
        expect(loadLanguageOverrides()).toEqual({});
    });

    it("round-trips saved overrides", () => {
        saveLanguageOverrides({"/config/config": "toml"});
        expect(loadLanguageOverrides()).toEqual({"/config/config": "toml"});
    });

    it("keeps overrides isolated per path", () => {
        saveLanguageOverrides({"/a": "toml", "/b": "yaml"});
        expect(loadLanguageOverrides()).toEqual({"/a": "toml", "/b": "yaml"});
    });

    it("ignores malformed JSON", () => {
        window.localStorage.setItem(KEY, "{not json");
        expect(loadLanguageOverrides()).toEqual({});
    });

    it("ignores array payloads", () => {
        window.localStorage.setItem(KEY, JSON.stringify(["toml"]));
        expect(loadLanguageOverrides()).toEqual({});
    });

    it("ignores primitive payloads", () => {
        window.localStorage.setItem(KEY, JSON.stringify("toml"));
        expect(loadLanguageOverrides()).toEqual({});
    });

    it("drops unknown language values", () => {
        window.localStorage.setItem(KEY, JSON.stringify({"/a": "toml", "/b": "cobol", "/c": 7, "/d": null}));
        expect(loadLanguageOverrides()).toEqual({"/a": "toml"});
    });

    it("sets an override without mutating the input", () => {
        const before = {"/a": "toml"} as const;
        const after = setLanguageOverride({...before}, "/b", "yaml");
        expect(after).toEqual({"/a": "toml", "/b": "yaml"});
        expect(before).toEqual({"/a": "toml"});
    });

    it("removes an override when set to null", () => {
        const after = setLanguageOverride({"/a": "toml", "/b": "yaml"}, "/a", null);
        expect(after).toEqual({"/b": "yaml"});
    });

    it("is a no-op removing an absent path", () => {
        const before = {"/a": "toml"};
        expect(setLanguageOverride(before, "/missing", null)).toBe(before);
    });
});
