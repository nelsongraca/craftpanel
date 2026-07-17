"use client";

import {StatCard, RamBarInline} from "./stat-cards";
import {fmtBytes, fmtMb, timeAgo} from "@/lib/utils/format";
import {serverStatusClass, serverStatusLabel} from "@/lib/status";
import type {Node, Server} from "@/lib/types";
import type React from "react";

type LiveMetrics = { cpuPercent: number; ramUsedMb: number; netInBytes: number; netOutBytes: number };
type LivePlayers = { count: number; list: string[] };

export function LiveMetricsPanel({
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

    const cpuColor = liveMetrics && liveMetrics.cpuPercent > 85 ? "text-error"
        : liveMetrics && liveMetrics.cpuPercent > 65 ? "text-warning"
            : "text-text-primary";

    return (
        <>
            {/* Stat cards */}
            <div className="grid grid-cols-4 gap-4">
                <StatCard label="Players Online">
                    {livePlayers ? (
                        <>
                            <p className="font-mono text-[20px] text-text-primary leading-none">{livePlayers.count}</p>
                            <p className="font-mono text-xs text-text-muted">online now</p>
                        </>
                    ) : (
                        <>
                            <p className="font-mono text-[20px] text-text-muted leading-none">\u2014</p>
                            <p className="text-xs text-text-muted">awaiting data</p>
                        </>
                    )}
                </StatCard>

                <StatCard label="RAM Usage">
                    <RamBarInline usedMb={liveMetrics?.ramUsedMb ?? null} totalMb={server.memory_mb}/>
                </StatCard>

                <StatCard label="CPU Usage">
                    {liveMetrics ? (
                        <>
                            <p className={`font-mono text-[20px] leading-none ${cpuColor}`}>
                                {liveMetrics.cpuPercent.toFixed(1)}%
                            </p>
                            <p className="font-mono text-xs text-text-muted">{server.cpu_shares} shares alloc</p>
                        </>
                    ) : (
                        <>
                            <p className="font-mono text-[20px] text-text-muted leading-none">\u2014%</p>
                            <p className="font-mono text-xs text-text-muted">{server.cpu_shares} shares alloc</p>
                        </>
                    )}
                </StatCard>

                <StatCard label="Status">
                    <span
                        className={`self-start text-xs font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${serverStatusClass(sStatus)}`}
                    >
                        {serverStatusLabel(sStatus)}
                    </span>
                    {node?.last_seen_at && (
                        <p className="text-xs text-text-muted">
                            last seen {timeAgo(node.last_seen_at)}
                        </p>
                    )}
                </StatCard>
            </div>

            {/* Live Metrics + Server Info row */}
            <div className="grid grid-cols-[1fr_1fr] gap-4">
                <div className="bg-surface border border-border rounded p-4">
                    <div className="flex items-center justify-between mb-4">
                        <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">
                            Live Metrics
                        </p>
                        {!liveMetrics && (
                            <span className="text-xs font-heading text-text-muted italic">awaiting data\u2026</span>
                        )}
                    </div>
                    <div className="space-y-3">
                        {[
                            {
                                label: "CPU",
                                value: liveMetrics ? `${liveMetrics.cpuPercent.toFixed(1)}%` : "\u2014%",
                                color: liveMetrics ? cpuColor : "text-text-muted",
                            },
                            {
                                label: "RAM",
                                value: liveMetrics
                                    ? `${fmtMb(liveMetrics.ramUsedMb)} / ${fmtMb(server.memory_mb)}`
                                    : "\u2014",
                                color: "text-text-primary",
                            },
                            {
                                label: "Net \u2193",
                                value: liveMetrics ? fmtBytes(liveMetrics.netInBytes) : "\u2014",
                                color: "text-text-primary",
                            },
                            {
                                label: "Net \u2191",
                                value: liveMetrics ? fmtBytes(liveMetrics.netOutBytes) : "\u2014",
                                color: "text-text-primary",
                            },
                        ].map(({label, value, color}) => (
                            <div key={label} className="flex items-center justify-between">
                            <span className="text-xs font-heading font-bold uppercase tracking-wider text-text-muted">
                                {label}
                            </span>
                                <span className={`font-mono text-xs ${liveMetrics ? color : "text-text-muted"}`}>{value}</span>
                            </div>
                        ))}
                    </div>
                    {livePlayers && livePlayers.list.length > 0 && (
                        <div className="mt-4 pt-4 border-t border-border">
                            <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-2">
                                Online Players
                            </p>
                            <div className="flex flex-wrap gap-1">
                                {livePlayers.list.map((name) => (
                                    <span key={name} className="font-mono text-xs text-text-dim border border-border bg-surface-high px-1.5 py-0.5 rounded">
                                        {name}
                                    </span>
                                ))}
                            </div>
                        </div>
                    )}
                </div>
            </div>
        </>
    );
}
