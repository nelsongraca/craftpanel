import {describe, it, expect} from "vitest";
import {
    ALL_SERVER_TYPES,
    GAME_SERVER_TYPES,
    isCustomType,
    isModLoaderType,
    isPicolimboType,
    isProxyType,
    isVelocityType,
    modrinthKind,
    PROXY_TYPES,
} from "./server-types";

describe("server-types", () => {
    it("classifies every proxy type, case- and whitespace-insensitively", () => {
        for (const type of PROXY_TYPES) expect(isProxyType(type)).toBe(true);
        expect(isProxyType("velocity")).toBe(true);
        expect(isProxyType("  BungeeCord ")).toBe(true);
        expect(isProxyType("PAPER")).toBe(false);
    });

    it("classifies custom, picolimbo and velocity", () => {
        expect(isCustomType("CUSTOM")).toBe(true);
        expect(isCustomType("custom")).toBe(true);
        expect(isCustomType("PAPER")).toBe(false);

        expect(isPicolimboType("PICOLIMBO")).toBe(true);
        expect(isPicolimboType("CUSTOM")).toBe(false);

        expect(isVelocityType("VELOCITY")).toBe(true);
        expect(isVelocityType("BUNGEECORD")).toBe(false);
    });

    it("classifies mod loaders and derives the Modrinth project kind", () => {
        expect(isModLoaderType("FABRIC")).toBe(true);
        expect(isModLoaderType("quilt")).toBe(true);
        expect(isModLoaderType("PAPER")).toBe(false);

        expect(modrinthKind("FORGE")).toBe("mod");
        expect(modrinthKind("PAPER")).toBe("plugin");
        expect(modrinthKind("VELOCITY")).toBe("plugin");
    });

    it("derives the type lists from the generated backend enum", () => {
        // The list is generated from the OpenAPI ServerType schema — it must include the types the
        // backend provisions, so drift here means the enum and the UI are out of sync.
        expect(ALL_SERVER_TYPES).toEqual(expect.arrayContaining(["FOLIA", "LIMBO", "BUKKIT", "PURPUR", "PICOLIMBO"]));
        expect(new Set(ALL_SERVER_TYPES).size).toBe(ALL_SERVER_TYPES.length);

        // Game types are everything except the proxies, and stay in backend declaration order.
        expect(GAME_SERVER_TYPES).toEqual(ALL_SERVER_TYPES.filter((t) => !isProxyType(t)));
        expect(PROXY_TYPES.every((p) => !GAME_SERVER_TYPES.includes(p))).toBe(true);
        expect(GAME_SERVER_TYPES).toEqual(expect.arrayContaining(["FOLIA", "LIMBO"]));
    });
});
