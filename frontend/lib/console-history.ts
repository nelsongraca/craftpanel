const MAX_ENTRIES = 100;

function storageKey(serverId: string): string {
    return `craftpanel:console-history:${serverId}`;
}

/**
 * Load the persisted console command history for a server. Best-effort: any
 * storage or parse failure yields an empty history rather than throwing.
 */
export function loadConsoleHistory(serverId: string): string[] {
    if (typeof window === "undefined") return [];
    try {
        const raw = window.localStorage.getItem(storageKey(serverId));
        if (!raw) return [];
        const parsed: unknown = JSON.parse(raw);
        if (!Array.isArray(parsed)) return [];
        return parsed.filter((entry): entry is string => typeof entry === "string");
    } catch {
        return [];
    }
}

/**
 * Persist the console command history for a server, keeping only the most
 * recent MAX_ENTRIES commands. Best-effort: failures (private mode, quota)
 * are swallowed so typing is never interrupted.
 */
export function saveConsoleHistory(serverId: string, commands: string[]): void {
    if (typeof window === "undefined") return;
    try {
        window.localStorage.setItem(storageKey(serverId), JSON.stringify(commands.slice(-MAX_ENTRIES)));
    } catch {
        // storage unavailable — history persistence is best-effort
    }
}
