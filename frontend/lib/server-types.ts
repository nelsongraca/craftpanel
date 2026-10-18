/**
 * The one owner of the frontend server-type taxonomy. The full list of types comes from the
 * backend `ServerType` enum (generated from the OpenAPI spec) — never hand-maintained. The
 * predicates are case-insensitive and trim, so callers never need to normalise. Grouping
 * (proxy / mod-loader) is semantic and not carried by the enum, so those sets are the only
 * hand-written part; they reference the generated enum constants so a rename/removal is a
 * compile error. Screen-specific policy (which tabs to show, what a blank version means) stays
 * with the screen — it uses these predicates.
 */

import {ServerType} from "@/lib/generated/types.gen";

/** Every server type the backend accepts, in backend declaration order. */
export const ALL_SERVER_TYPES: readonly ServerType[] = Object.values(ServerType);

/** Proxy / load-balancer types (mirrors master `ServerType.isProxy`). */
export const PROXY_TYPES: readonly ServerType[] = [ServerType.VELOCITY, ServerType.BUNGEECORD, ServerType.WATERFALL];

/** Mod-loader types — Modrinth publishes these as "mods", everything else as "plugins". */
export const MOD_LOADER_TYPES: readonly ServerType[] = [
    ServerType.FABRIC,
    ServerType.FORGE,
    ServerType.NEOFORGE,
    ServerType.QUILT,
];

function matches(types: readonly string[], serverType: string): boolean {
    return types.includes(serverType.trim().toUpperCase());
}

export function isProxyType(serverType: string): boolean {
    return matches(PROXY_TYPES, serverType);
}

export function isVelocityType(serverType: string): boolean {
    return serverType.trim().toUpperCase() === "VELOCITY";
}

export function isCustomType(serverType: string): boolean {
    return serverType.trim().toUpperCase() === "CUSTOM";
}

export function isPicolimboType(serverType: string): boolean {
    return serverType.trim().toUpperCase() === "PICOLIMBO";
}

export function isModLoaderType(serverType: string): boolean {
    return matches(MOD_LOADER_TYPES, serverType);
}

/** Game (non-proxy) server types, in backend declaration order. */
export const GAME_SERVER_TYPES: readonly ServerType[] = ALL_SERVER_TYPES.filter((t) => !isProxyType(t));

/** Modrinth project kind for a server type. */
export function modrinthKind(serverType: string): "mod" | "plugin" {
    return isModLoaderType(serverType) ? "mod" : "plugin";
}
