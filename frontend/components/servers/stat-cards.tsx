"use client";

import {fmtMb} from "@/lib/utils/format";

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
                <p className="font-mono text-[20px] text-text-muted leading-none">\u2014</p>
                <p className="font-mono text-xs text-text-muted">\u2014 / {fmtMb(totalMb)} alloc</p>
                <div className="h-1.5 rounded-full bg-surface-higher w-full"/>
            </div>
        );
    }
    const pct = Math.min(100, (usedMb / totalMb) * 100);
    const barColor = pct > 85 ? "bg-error" : pct > 65 ? "bg-warning" : "bg-accent";
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
