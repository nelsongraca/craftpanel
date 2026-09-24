"use client";

import {fmtBytes, fmtMb} from "@/lib/utils/format";

export function StatCard({
                             label,
                             children,
                         }: {
    label: string;
    children: React.ReactNode;
}) {
    return (
        <div className="bg-surface border border-border rounded p-4 flex flex-col gap-2">
            <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">
                {label}
            </p>
            {children}
        </div>
    );
}

export function RamBarInline({usedMb, totalMb}: { usedMb: number | null; totalMb: number }) {
    if (usedMb === null) {
        return (
            <div className="flex flex-col gap-1.5">
                <p className="font-mono text-[20px] text-text-muted leading-none">{"-"}</p>
                <p className="font-mono text-xs text-text-muted">{"-"} / {fmtMb(totalMb)} alloc</p>
                <div className="h-1.5 rounded-full bg-surface-higher w-full"/>
            </div>
        );
    }
    const pct = Math.min(100, (usedMb / totalMb) * 100);
    const barColor = pct > 85 ? "bg-error" : pct > 65 ? "bg-warning" : "bg-healthy";
    return (
        <div className="flex flex-col gap-1.5">
            <p className="font-mono text-[20px] text-text-primary leading-none">
                {fmtMb(usedMb)}
            </p>
            <p className="font-mono text-xs text-text-muted">
                {fmtMb(usedMb)} / {fmtMb(totalMb)} alloc
            </p>
            <div className="h-1.5 rounded-full bg-surface-higher w-full overflow-hidden">
                <div className={`h-full rounded-full ${barColor}`} style={{width: `${pct}%`}}/>
            </div>
        </div>
    );
}

/**
 * JVM heap usage bar: `used / max` (the heap ceiling, i.e. `-Xmx`). This is the "actual JVM RAM"
 * signal — distinct from the container's cgroup figure. Renders a muted placeholder when no sample
 * is available (JVM metrics disabled, non-JVM server, or not yet attached).
 */
export function HeapBarInline({usedBytes, maxBytes}: { usedBytes: number | null; maxBytes: number | null }) {
    if (usedBytes === null || maxBytes === null || maxBytes <= 0) {
        return (
            <div className="flex flex-col gap-1.5">
                <p className="font-mono text-[20px] text-text-muted leading-none">{"-"}</p>
                <p className="font-mono text-xs text-text-muted">JVM heap unavailable</p>
                <div className="h-1.5 rounded-full bg-surface-higher w-full"/>
            </div>
        );
    }
    const pct = Math.min(100, (usedBytes / maxBytes) * 100);
    const barColor = pct > 85 ? "bg-error" : pct > 65 ? "bg-warning" : "bg-healthy";
    return (
        <div className="flex flex-col gap-1.5">
            <p className="font-mono text-[20px] text-text-primary leading-none">
                {fmtBytes(usedBytes)}
            </p>
            <p className="font-mono text-xs text-text-muted">
                {fmtBytes(usedBytes)} / {fmtBytes(maxBytes)} heap
            </p>
            <div className="h-1.5 rounded-full bg-surface-higher w-full overflow-hidden">
                <div className={`h-full rounded-full ${barColor}`} style={{width: `${pct}%`}}/>
            </div>
        </div>
    );
}
