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
    return "var(--healthy)";
}

export function fillColorBg(pct: number): string {
    if (pct >= 86) return "bg-error";
    if (pct >= 66) return "bg-warning";
    return "bg-healthy";
}

export function fmtPct(v: number): string {
    return `${Math.round(v)}%`;
}

/** CPU millicores → short core string, e.g. 4000 → "4c", 1500 → "1.5c", 500 → "500m". */
export function fmtCpuCores(millicores: number): string {
    const cores = millicores / 1000;
    if (cores >= 1) return `${cores % 1 === 0 ? cores.toFixed(0) : cores.toFixed(1)}c`;
    return `${millicores}m`;
}

/** CPU millicores → user-facing limit label. 0 = unlimited. */
export function fmtCpuLimit(millicores: number): string {
    return millicores === 0 ? "Unlimited" : `${millicores / 1000} cores`;
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
