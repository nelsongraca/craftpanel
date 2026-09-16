/**
 * The one owner of the frontend server-type taxonomy. Server types are the master `ServerType`
 * enum names (exact, uppercase strings); the predicates are case-insensitive and trim, so callers
 * never need to normalise. Screen-specific policy (which tabs to show, what a blank version means)
 * stays with the screen — it uses these predicates.
 */

/** Proxy / load-balancer types (mirrors master `ServerType.isProxy`). */
export const PROXY_TYPES = ["VELOCITY", "BUNGEECORD", "WATERFALL"] as const;

/** Mod-loader types — Modrinth publishes these as "mods", everything else as "plugins". */
export const MOD_LOADER_TYPES = ["FABRIC", "FORGE", "NEOFORGE", "QUILT"] as const;

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

/** Modrinth project kind for a server type. */
export function modrinthKind(serverType: string): "mod" | "plugin" {
    return isModLoaderType(serverType) ? "mod" : "plugin";
}
