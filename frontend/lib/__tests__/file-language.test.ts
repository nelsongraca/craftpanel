import {describe, it, expect} from "vitest";
import {LANGUAGE_IDS, LANGUAGE_LABELS, isLanguageId, languageIdFromPath} from "../file-language";

describe("languageIdFromPath", () => {
    it("maps known extensions", () => {
        expect(languageIdFromPath("/config/config.yml")).toBe("yaml");
        expect(languageIdFromPath("/ops.json")).toBe("json");
        expect(languageIdFromPath("/velocity.toml")).toBe("toml");
        expect(languageIdFromPath("/server.properties")).toBe("properties");
        expect(languageIdFromPath("/pom.xml")).toBe("xml");
        expect(languageIdFromPath("/README.md")).toBe("markdown");
    });

    it("is case-insensitive on the extension", () => {
        expect(languageIdFromPath("/CONFIG.YML")).toBe("yaml");
    });

    it("falls back to plain for an extension-less path", () => {
        expect(languageIdFromPath("/mods/example/config")).toBe("plain");
    });

    it("falls back to plain for an unknown extension", () => {
        expect(languageIdFromPath("/mods/example/config.conf")).toBe("plain");
    });
});

describe("language id metadata", () => {
    it("labels every language id", () => {
        for (const id of LANGUAGE_IDS) {
            expect(LANGUAGE_LABELS[id]).toBeTruthy();
        }
    });

    it("guards unknown language ids", () => {
        expect(isLanguageId("toml")).toBe(true);
        expect(isLanguageId("cobol")).toBe(false);
        expect(isLanguageId(7)).toBe(false);
        expect(isLanguageId(null)).toBe(false);
    });
});
