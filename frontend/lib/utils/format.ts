export function timeAgo(iso: string): string {
    const rtf = new Intl.RelativeTimeFormat("en", {numeric: "auto"});
    const secs = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
    if (secs < 60) return rtf.format(-secs, "second");
    if (secs < 3600) return rtf.format(-Math.floor(secs / 60), "minute");
    if (secs < 86400) return rtf.format(-Math.floor(secs / 3600), "hour");
    return rtf.format(-Math.floor(secs / 86400), "day");
}

export function fmtBytes(bytes: number): string {
    if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(1)} GB`;
    if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`;
    if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${bytes} B`;
}

export function fmtMb(mb: number): string {
    if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`;
    return `${mb} MB`;
}

export function fmtBytesNetworkIo(b: number): string {
    if (b >= 1e9) return `${(b / 1e9).toFixed(1)} GB`;
    if (b >= 1e6) return `${(b / 1e6).toFixed(1)} MB`;
    if (b >= 1e3) return `${(b / 1e3).toFixed(1)} KB`;
    return `${b} B`;
}

export function fillColor(pct: number): string {
    if (pct >= 86) return "var(--error)";
    if (pct >= 66) return "var(--warning)";
    return "var(--accent)";
}

export function fillColorBg(pct: number): string {
    if (pct >= 86) return "bg-error";
    if (pct >= 66) return "bg-warning";
    return "bg-accent";
}

type MojangVersion = { id: string; type: string };

export async function fetchReleaseVersions(): Promise<string[]> {
    try {
        const res = await fetch("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json");
        const json = await res.json() as { versions: MojangVersion[] };
        return json.versions.filter((v) => v.type === "release").map((v) => v.id);
    } catch {
        return [];
    }
}
