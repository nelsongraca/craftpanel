"use client";

import {useCallback, useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import Link from "next/link";
import {AlertTriangle, Clock} from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import {listNodes, listServers} from "@/lib/generated/sdk.gen";
import type {Node, Server} from "@/lib/types";
import {timeAgo} from "@/lib/utils/format";
import {nodeDisplayStatus, nodeStatusClass, serverStatusClass} from "@/lib/status";

function NodeStatusBadge({status, health}: { status: string; health?: string }) {
    return (
        <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-heading font-bold uppercase tracking-wider ${nodeStatusClass(status, health)}`}>
      {nodeDisplayStatus(status, health)}
    </span>
    );
}

function ServerStatusBadge({status}: { status: string }) {
    return (
        <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-heading font-bold uppercase tracking-wider ${serverStatusClass(status)}`}>
      {status}
    </span>
    );
}

function RamBar({used, total}: { used: number; total: number }) {
    const pct = total > 0 ? Math.min(100, (used / total) * 100) : 0;
    const color = pct >= 86 ? "var(--error)" : pct >= 66 ? "var(--warning)" : "var(--accent)";
    return (
        <div className="flex items-center gap-2">
            <div className="w-16 h-1 rounded-full bg-border">
                <div className="h-full rounded-full" style={{width: `${pct}%`, background: color}}/>
            </div>
            <span className="font-mono text-xs text-text-muted">{Math.round(pct)}%</span>
        </div>
    );
}

// ── Stat card ─────────────────────────────────────────────────────────────────

function StatCard({
                      label, value, sub, href, accent,
                  }: {
    label: string; value: number; sub: string; href: string; accent?: boolean;
}) {
    return (
        <Link href={href} className="block bg-surface border border-border rounded-md p-5 hover:border-accent/40 transition-colors">
            <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-2">{label}</p>
            <p className={`text-3xl font-heading font-bold tabular-nums ${accent ? "text-error" : "text-text-primary"}`}>{value}</p>
            <p className="text-xs text-text-muted mt-1">{sub}</p>
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
            <PageHeader title="Dashboard" subtitle="Platform overview"/>
            <div className="p-6 space-y-6 max-w-[1600px]">

                {/* Stat cards */}
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
                    <StatCard label="Servers" value={totalServers} sub={`${healthyServers} healthy`} href="/servers"/>
                    <StatCard label="Nodes" value={totalNodes} sub={`${activeNodes} active`} href="/nodes"/>
                    <StatCard label="Unhealthy" value={unhealthy} sub="servers need attention" href="/servers" accent={unhealthy > 0}/>
                    <StatCard label="Pending Nodes" value={pendingNodes} sub="awaiting approval" href="/nodes" accent={pendingNodes > 0}/>
                </div>

                {/* Split panels */}
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">

                    {/* Node health overview */}
                    <div className="bg-surface border border-border rounded-md overflow-hidden">
                        <div className="px-5 py-3 border-b border-border">
                            <h2 className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">Node Health</h2>
                        </div>
                        {loading ? (
                            <div className="p-6 text-xs text-text-muted">Loading…</div>
                        ) : nodes.length === 0 ? (
                            <div className="p-6 text-xs text-text-muted">No nodes registered.</div>
                        ) : (
                            <div className="overflow-x-auto">
                            <table className="w-full text-xs">
                                <thead>
                                <tr className="border-b border-border">
                                    <th className="text-left px-5 py-2 text-xs font-heading font-bold uppercase tracking-widest text-text-muted">Node</th>
                                    <th className="text-left px-3 py-2 text-xs font-heading font-bold uppercase tracking-widest text-text-muted">Status</th>
                                    <th className="text-left px-3 py-2 text-xs font-heading font-bold uppercase tracking-widest text-text-muted">RAM</th>
                                    <th className="text-left px-3 py-2 text-xs font-heading font-bold uppercase tracking-widest text-text-muted">Last seen</th>
                                </tr>
                                </thead>
                                <tbody>
                                {nodes.map((node) => (
                                    <tr
                                        key={node.id}
                                        onClick={() => router.push(`/nodes/${node.id}`)}
                                        className="border-b border-border/50 hover:bg-surface-high/40 cursor-pointer group transition-colors"
                                    >
                                        <td className="px-5 py-2.5 text-text-primary font-medium truncate max-w-[120px]">
                                            <span className="group-hover:text-accent transition-colors">
                                                {node.display_name}
                                            </span>
                                        </td>
                                        <td className="px-3 py-2.5">
                                            <NodeStatusBadge status={node.status} health={node.health}/>
                                        </td>
                                        <td className="px-3 py-2.5">
                                            <RamBar used={node.allocated_ram_mb} total={node.total_ram_mb}/>
                                        </td>
                                        <td className="px-3 py-2.5 text-text-muted font-mono text-xs">
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
                    <div className="bg-surface border border-border rounded-md overflow-hidden">
                        <div className="px-5 py-3 border-b border-border">
                            <h2 className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">Recent Server Activity</h2>
                        </div>
                        {loading ? (
                            <div className="p-6 text-xs text-text-muted">Loading…</div>
                        ) : recentServers.length === 0 ? (
                            <div className="p-6 text-xs text-text-muted">No servers found.</div>
                        ) : (
                            <ul className="divide-y divide-border/50">
                                {recentServers.map((s) => (
                                    <li
                                        key={s.id}
                                        onClick={() => router.push(`/servers/${s.id}`)}
                                        className="flex items-center justify-between px-5 py-2.5 hover:bg-surface-high/40 cursor-pointer group transition-colors"
                                    >
                                        <div className="flex items-center gap-2 min-w-0">
                                            {s.status === "UNHEALTHY" && (
                                                <AlertTriangle size={12} className="text-error shrink-0"/>
                                            )}
                                            <span className="text-xs text-text-primary truncate group-hover:text-accent transition-colors">
                                                {s.display_name}
                                            </span>
                                        </div>
                                        <div className="flex items-center gap-3 shrink-0 ml-3">
                                            <ServerStatusBadge status={s.status}/>
                                            <span className="flex items-center gap-1 text-xs text-text-muted font-mono">
                        <Clock size={10}/>
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
