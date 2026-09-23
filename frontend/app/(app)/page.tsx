"use client";

import {useCallback, useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import Link from "next/link";
import {AlertTriangle, Clock} from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import {listNodes, listServers} from "@/lib/generated/sdk.gen";
import type {Node, Server} from "@/lib/types";
import {timeAgo, allocatable} from "@/lib/utils/format";
import {Empty, EmptyDescription} from "@/components/ui/empty";
import {nodeStatusLabel, nodeStatusVariant, serverStatusLabel, serverStatusVariant} from "@/lib/status";
import {Badge} from "@/components/ui/badge";

function NodeStatusBadge({status, health}: {status: string; health?: string}) {
    return <Badge variant={nodeStatusVariant(status, health)}>{nodeStatusLabel(status, health)}</Badge>;
}

function ServerStatusBadge({status}: {status: string}) {
    return <Badge variant={serverStatusVariant(status)}>{serverStatusLabel(status)}</Badge>;
}

function RamBar({used, total}: {used: number; total: number}) {
    const pct = total > 0 ? Math.min(100, (used / total) * 100) : 0;
    const color = pct >= 86 ? "var(--error)" : pct >= 66 ? "var(--warning)" : "var(--healthy)";
    return (
        <div className="flex items-center gap-2">
            <div className="h-1 w-16 rounded-full bg-border">
                <div className="h-full rounded-full" style={{width: `${pct}%`, background: color}} />
            </div>
            <span className="font-mono text-xs text-text-muted">{Math.round(pct)}%</span>
        </div>
    );
}

// ── Stat card ─────────────────────────────────────────────────────────────────

function StatCard({
    label,
    value,
    sub,
    href,
    accent,
}: {
    label: string;
    value: number;
    sub: string;
    href: string;
    accent?: boolean;
}) {
    return (
        <Link
            href={href}
            className="block rounded-md border border-border bg-surface p-5 transition-colors hover:border-accent/40"
        >
            <p className="mb-2 font-heading text-xs font-bold tracking-widest text-text-muted uppercase">{label}</p>
            <p
                className={`font-heading text-3xl font-bold tabular-nums ${accent ? "text-error" : "text-text-primary"}`}
            >
                {value}
            </p>
            <p className="mt-1 text-xs text-text-muted">{sub}</p>
        </Link>
    );
}

// ── Main ──────────────────────────────────────────────────────────────────────

export default function Dashboard() {
    const router = useRouter();
    const [servers, setServers] = useState<Server[]>([]);
    const [nodes, setNodes] = useState<Node[]>([]);
    const [loading, setLoading] = useState(true);

    const load = useCallback(async () => {
        const [sRes, nRes] = await Promise.all([listServers(), listNodes()]);
        if (sRes.data) setServers(sRes.data);
        if (nRes.data) setNodes(nRes.data);
        setLoading(false);
    }, []);

    useEffect(() => {
        load();
        const interval = setInterval(load, 30_000);
        return () => clearInterval(interval);
    }, [load]);

    const totalServers = servers.length;
    const healthyServers = servers.filter((s) => s.status === "HEALTHY").length;
    const unhealthy = servers.filter((s) => s.status === "UNHEALTHY").length;
    const totalNodes = nodes.length;
    const activeNodes = nodes.filter((n) => n.status === "ACTIVE").length;
    const pendingNodes = nodes.filter((n) => n.status === "PENDING").length;

    const recentServers = [...servers]
        .sort((a, b) => new Date(b.updated_at).getTime() - new Date(a.updated_at).getTime())
        .slice(0, 20);

    return (
        <div>
            <PageHeader title="Dashboard" subtitle="Platform overview" />
            <div className="max-w-[1600px] space-y-6 p-6">
                {/* Stat cards */}
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
                    <StatCard label="Servers" value={totalServers} sub={`${healthyServers} healthy`} href="/servers" />
                    <StatCard label="Nodes" value={totalNodes} sub={`${activeNodes} active`} href="/nodes" />
                    <StatCard
                        label="Unhealthy"
                        value={unhealthy}
                        sub="servers need attention"
                        href="/servers"
                        accent={unhealthy > 0}
                    />
                    <StatCard
                        label="Pending Nodes"
                        value={pendingNodes}
                        sub="awaiting approval"
                        href="/nodes"
                        accent={pendingNodes > 0}
                    />
                </div>

                {/* Split panels */}
                <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
                    {/* Node health overview */}
                    <div className="overflow-hidden rounded-md border border-border bg-surface">
                        <div className="border-b border-border px-5 py-3">
                            <h2 className="font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                                Node Health
                            </h2>
                        </div>
                        {loading ? (
                            <div className="p-6 text-xs text-text-muted">Loading…</div>
                        ) : nodes.length === 0 ? (
                            <Empty>
                                <EmptyDescription>No nodes registered.</EmptyDescription>
                            </Empty>
                        ) : (
                            <div className="overflow-x-auto">
                                <table className="w-full text-xs">
                                    <thead>
                                        <tr className="border-b border-border">
                                            <th className="px-5 py-2 text-left font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                                                Node
                                            </th>
                                            <th className="px-3 py-2 text-left font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                                                Status
                                            </th>
                                            <th className="px-3 py-2 text-left font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                                                RAM
                                            </th>
                                            <th className="px-3 py-2 text-left font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                                                Last seen
                                            </th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {nodes.map((node) => (
                                            <tr
                                                key={node.id}
                                                onClick={() => router.push(`/nodes/${node.id}`)}
                                                className="group cursor-pointer border-b border-border/50 transition-colors hover:bg-surface-high/40"
                                            >
                                                <td className="max-w-[120px] truncate px-5 py-2.5 font-medium text-text-primary">
                                                    <span className="transition-colors group-hover:text-accent">
                                                        {node.display_name}
                                                    </span>
                                                </td>
                                                <td className="px-3 py-2.5">
                                                    <NodeStatusBadge status={node.status} health={node.health} />
                                                </td>
                                                <td className="px-3 py-2.5">
                                                    <RamBar
                                                        used={node.allocated_ram_mb}
                                                        total={allocatable(node.total_ram_mb, node.reserved_ram_mb)}
                                                    />
                                                </td>
                                                <td className="px-3 py-2.5 font-mono text-xs text-text-muted">
                                                    {node.last_seen_at ? timeAgo(node.last_seen_at) : "-"}
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </div>

                    {/* Recent server events */}
                    <div className="overflow-hidden rounded-md border border-border bg-surface">
                        <div className="border-b border-border px-5 py-3">
                            <h2 className="font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                                Recent Server Activity
                            </h2>
                        </div>
                        {loading ? (
                            <div className="p-6 text-xs text-text-muted">Loading…</div>
                        ) : recentServers.length === 0 ? (
                            <Empty>
                                <EmptyDescription>No servers found.</EmptyDescription>
                            </Empty>
                        ) : (
                            <ul className="divide-y divide-border/50">
                                {recentServers.map((s) => (
                                    <li
                                        key={s.id}
                                        onClick={() => router.push(`/servers/${s.id}`)}
                                        className="group flex cursor-pointer items-center justify-between px-5 py-2.5 transition-colors hover:bg-surface-high/40"
                                    >
                                        <div className="flex min-w-0 items-center gap-2">
                                            {s.status === "UNHEALTHY" && (
                                                <AlertTriangle size={12} className="shrink-0 text-error" />
                                            )}
                                            <span className="truncate text-xs text-text-primary transition-colors group-hover:text-accent">
                                                {s.display_name}
                                            </span>
                                        </div>
                                        <div className="ml-3 flex shrink-0 items-center gap-3">
                                            <ServerStatusBadge status={s.status} />
                                            <span className="flex items-center gap-1 font-mono text-xs text-text-muted">
                                                <Clock size={10} />
                                                {timeAgo(s.updated_at)}
                                            </span>
                                        </div>
                                    </li>
                                ))}
                            </ul>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
}
