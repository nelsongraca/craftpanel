"use client";

import {StatCard, RamBarInline, HeapBarInline} from "./stat-cards";
import {fmtBytes, fmtMb, timeAgo, fmtCpuLimit} from "@/lib/utils/format";
import {serverStatusClass, serverStatusLabel} from "@/lib/status";
import type {Node, Server} from "@/lib/types";

type LiveMetrics = {
    cpuPercent: number;
    ramUsedMb: number;
    netInBytes: number;
    netOutBytes: number;
    // JVM heap sample. Absent/undefined when the tick carried none (metrics disabled or non-JVM).
    heapUsedBytes?: number | null;
    heapMaxBytes?: number | null;
    nonHeapUsedBytes?: number | null;
};
type LivePlayers = {count: number; list: string[]};

function cpuColorOf(liveMetrics: LiveMetrics | null): string {
    if (liveMetrics && liveMetrics.cpuPercent > 85) return "text-error";
    if (liveMetrics && liveMetrics.cpuPercent > 65) return "text-warning";
    return "text-text-primary";
}

/** Full-width summary strip: the four at-a-glance stat cards. */
export function LiveMetricsStatCards({
    liveMetrics,
    livePlayers,
    server,
    node,
}: {
    liveMetrics: LiveMetrics | null;
    livePlayers: LivePlayers | null;
    server: Server;
    node: Node | null;
}) {
    const sStatus = server.status;
    const cpuColor = cpuColorOf(liveMetrics);

    return (
        <div className="grid grid-cols-2 gap-4 md:grid-cols-3 lg:grid-cols-5">
            <StatCard label="Players Online">
                {livePlayers ? (
                    <>
                        <p className="font-mono text-[20px] leading-none text-text-primary">{livePlayers.count}</p>
                        <p className="font-mono text-xs text-text-muted">online now</p>
                    </>
                ) : (
                    <>
                        <p className="font-mono text-[20px] leading-none text-text-muted">{"-"}</p>
                        <p className="text-xs text-text-muted">awaiting data</p>
                    </>
                )}
            </StatCard>

            <StatCard label="RAM Usage">
                <RamBarInline usedMb={liveMetrics?.ramUsedMb ?? null} totalMb={server.memory_mb} />
            </StatCard>

            <StatCard label="JVM Heap">
                <HeapBarInline
                    usedBytes={liveMetrics?.heapUsedBytes ?? null}
                    maxBytes={liveMetrics?.heapMaxBytes ?? null}
                />
            </StatCard>

            <StatCard label="CPU Usage">
                {liveMetrics ? (
                    <>
                        <p className={`font-mono text-[20px] leading-none ${cpuColor}`}>
                            {liveMetrics.cpuPercent.toFixed(1)}%
                        </p>
                        <p className="font-mono text-xs text-text-muted">{fmtCpuLimit(server.cpu_limit_millicores)}</p>
                    </>
                ) : (
                    <>
                        <p className="font-mono text-[20px] leading-none text-text-muted">{"-"}%</p>
                        <p className="font-mono text-xs text-text-muted">{fmtCpuLimit(server.cpu_limit_millicores)}</p>
                    </>
                )}
            </StatCard>

            <StatCard label="Status">
                <span
                    className={`self-start rounded px-2 py-0.5 font-heading text-xs font-bold tracking-wider uppercase ${serverStatusClass(sStatus)}`}
                >
                    {serverStatusLabel(sStatus)}
                </span>
                {node?.last_seen_at && (
                    <p className="text-xs text-text-muted">last seen {timeAgo(node.last_seen_at)}</p>
                )}
            </StatCard>
        </div>
    );
}

/** The detailed live-metrics card (CPU/RAM/network), placed in the info column. */
export function LiveMetricsCard({liveMetrics, server}: {liveMetrics: LiveMetrics | null; server: Server}) {
    const cpuColor = cpuColorOf(liveMetrics);

    return (
        <div className="rounded border border-border bg-surface p-4">
            <div className="mb-4 flex items-center justify-between">
                <p className="font-heading text-xs font-bold tracking-widest text-text-muted uppercase">Live Metrics</p>
                {!liveMetrics && (
                    <span className="font-heading text-xs text-text-muted italic">awaiting data{"\u2026"}</span>
                )}
            </div>
            <div className="space-y-3">
                {[
                    {
                        label: "CPU",
                        value: liveMetrics ? `${liveMetrics.cpuPercent.toFixed(1)}%` : "-%",
                        color: liveMetrics ? cpuColor : "text-text-muted",
                    },
                    {
                        label: "RAM",
                        value: liveMetrics ? `${fmtMb(liveMetrics.ramUsedMb)} / ${fmtMb(server.memory_mb)}` : "-",
                        color: "text-text-primary",
                    },
                    {
                        label: "JVM Heap",
                        value:
                            liveMetrics && liveMetrics.heapUsedBytes != null && liveMetrics.heapMaxBytes != null
                                ? `${fmtBytes(liveMetrics.heapUsedBytes)} / ${fmtBytes(liveMetrics.heapMaxBytes)}`
                                : "-",
                        color: "text-text-primary",
                    },
                    {
                        label: "JVM Non-Heap",
                        value:
                            liveMetrics && liveMetrics.nonHeapUsedBytes != null
                                ? fmtBytes(liveMetrics.nonHeapUsedBytes)
                                : "-",
                        color: "text-text-primary",
                    },
                    {
                        label: "Net \u2193",
                        value: liveMetrics ? fmtBytes(liveMetrics.netInBytes) : "-",
                        color: "text-text-primary",
                    },
                    {
                        label: "Net \u2191",
                        value: liveMetrics ? fmtBytes(liveMetrics.netOutBytes) : "-",
                        color: "text-text-primary",
                    },
                ].map(({label, value, color}) => (
                    <div key={label} className="flex items-center justify-between">
                        <span className="font-heading text-xs font-bold tracking-wider text-text-muted uppercase">
                            {label}
                        </span>
                        <span className={`font-mono text-xs ${liveMetrics ? color : "text-text-muted"}`}>{value}</span>
                    </div>
                ))}
            </div>
        </div>
    );
}
