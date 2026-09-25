"use client";

import {fmtBytes, fmtMb} from "@/lib/utils/format";

export function StatCard({label, children}: {label: string; children: React.ReactNode}) {
    return (
        <div className="flex flex-col gap-2 rounded border border-border bg-surface p-4">
            <p className="font-heading text-xs font-bold tracking-widest text-text-muted uppercase">{label}</p>
            {children}
        </div>
    );
}

export function RamBarInline({usedMb, totalMb}: {usedMb: number | null; totalMb: number}) {
    if (usedMb === null) {
        return (
            <div className="flex flex-col gap-1.5">
                <p className="font-mono text-[20px] leading-none text-text-muted">{"-"}</p>
                <p className="font-mono text-xs text-text-muted">
                    {"-"} / {fmtMb(totalMb)} alloc
                </p>
                <div className="h-1.5 w-full rounded-full bg-surface-higher" />
            </div>
        );
    }
    const pct = Math.min(100, (usedMb / totalMb) * 100);
    const barColor = pct > 85 ? "bg-error" : pct > 65 ? "bg-warning" : "bg-healthy";
    return (
        <div className="flex flex-col gap-1.5">
            <p className="font-mono text-[20px] leading-none text-text-primary">{fmtMb(usedMb)}</p>
            <p className="font-mono text-xs text-text-muted">
                {fmtMb(usedMb)} / {fmtMb(totalMb)} alloc
            </p>
            <div className="h-1.5 w-full overflow-hidden rounded-full bg-surface-higher">
                <div className={`h-full rounded-full ${barColor}`} style={{width: `${pct}%`}} />
            </div>
        </div>
    );
}

/**
 * JVM heap usage bar: `used / max` (the heap ceiling, i.e. `-Xmx`). This is the "actual JVM RAM"
 * signal — distinct from the container's cgroup figure. Renders a muted placeholder when no sample
 * is available (JVM metrics disabled, non-JVM server, or not yet attached).
 */
export function HeapBarInline({usedBytes, maxBytes}: {usedBytes: number | null; maxBytes: number | null}) {
    if (usedBytes === null || maxBytes === null || maxBytes <= 0) {
        return (
            <div className="flex flex-col gap-1.5">
                <p className="font-mono text-[20px] leading-none text-text-muted">{"-"}</p>
                <p className="font-mono text-xs text-text-muted">JVM heap unavailable</p>
                <div className="h-1.5 w-full rounded-full bg-surface-higher" />
            </div>
        );
    }
    const pct = Math.min(100, (usedBytes / maxBytes) * 100);
    const barColor = pct > 85 ? "bg-error" : pct > 65 ? "bg-warning" : "bg-healthy";
    return (
        <div className="flex flex-col gap-1.5">
            <p className="font-mono text-[20px] leading-none text-text-primary">{fmtBytes(usedBytes)}</p>
            <p className="font-mono text-xs text-text-muted">
                {fmtBytes(usedBytes)} / {fmtBytes(maxBytes)} heap
            </p>
            <div className="h-1.5 w-full overflow-hidden rounded-full bg-surface-higher">
                <div className={`h-full rounded-full ${barColor}`} style={{width: `${pct}%`}} />
            </div>
        </div>
    );
}
