"use client";

import {useEffect, useMemo, useState, type ReactNode} from "react";
import {Badge} from "@/components/ui/badge";
import {SmartList, type SmartListColumn} from "@/components/ui/smart-list";
import {fillColor, fmtCpuLimit} from "@/lib/utils/format";
import {serverExpired, serverStatusLabel, serverStatusVariant} from "@/lib/status";
import {useWs} from "@/lib/ws-context";
import type {Node, Server} from "@/lib/types";

export function RamBar({total, used}: { total: number; used?: number }) {
    const hasData = used != null;
    const pct = hasData && total > 0 ? Math.min(100, (used! / total) * 100) : 0;
    return (
        <div className="flex flex-col gap-1">
      <span className="font-mono text-xs text-text-muted whitespace-nowrap">
        {hasData ? `${used} / ${total} MB` : `- / ${total} MB`}
      </span>
            <div className="w-20 h-1 rounded-full" style={{background: "var(--border)"}}>
                {hasData && pct > 0 && (
                    <div
                        className="h-full rounded-full"
                        style={{width: `${pct}%`, background: fillColor(pct)}}
                    />
                )}
            </div>
        </div>
    );
}

export function CpuBar({percent, limitMillicores}: { percent?: number; limitMillicores: number }) {
    const hasData = percent != null;
    const pct = hasData ? Math.min(100, percent!) : 0;
    return (
        <div className="flex flex-col gap-1">
      <span className="font-mono text-xs text-text-muted whitespace-nowrap">
        {hasData ? `${percent!.toFixed(1)}% / ${fmtCpuLimit(limitMillicores)}` : `- / ${fmtCpuLimit(limitMillicores)}`}
      </span>
            <div className="w-20 h-1 rounded-full" style={{background: "var(--border)"}}>
                {hasData && pct > 0 && (
                    <div
                        className="h-full rounded-full"
                        style={{width: `${pct}%`, background: fillColor(pct)}}
                    />
                )}
            </div>
        </div>
    );
}

type SortKey = "name" | "type" | "status" | "ram" | "cpu" | "node";
type SortDir = "asc" | "desc";

function SortIndicator({active, dir}: { active: boolean; dir: SortDir }) {
    if (!active) return <span className="text-text-muted/50 ml-1">↕</span>;
    return <span className="text-accent ml-1">{dir === "asc" ? "↑" : "↓"}</span>;
}

function sortServers(servers: Server[], key: SortKey | null, dir: SortDir, nodeMap: Record<string, Node>, cpuUsage: Record<string, number>): Server[] {
    if (!key) return servers;
    const factor = dir === "asc" ? 1 : -1;
    const valueOf = (s: Server): string | number => {
        switch (key) {
            case "name":
                return s.display_name.toLowerCase();
            case "type":
                return s.server_type.toLowerCase();
            case "status":
                return s.status.toLowerCase();
            case "ram":
                return s.memory_mb;
            case "cpu":
                return cpuUsage[s.id] ?? -1;
            case "node":
                return (nodeMap[s.node_id]?.display_name ?? s.node_id).toLowerCase();
        }
    };
    return [...servers].sort((a, b) => {
        const va = valueOf(a);
        const vb = valueOf(b);
        if (va < vb) return -1 * factor;
        if (va > vb) return 1 * factor;
        return 0;
    });
}

export interface ServerListProps {
    servers: Server[];
    nodes: Node[];
    loading?: boolean;
    empty?: ReactNode;
    /** Hide the Node column when the list is already scoped to one node (node detail). */
    showNodeColumn?: boolean;
    onRowClick?: (server: Server) => void;
    renderActions?: (server: Server) => ReactNode;
    actionsHeader?: ReactNode;
}

/**
 * The canonical server list table: columns, live RAM/CPU bars, sorting and mobile cards.
 * Shared by the Servers page and the node detail Servers tab so rows render identically.
 */
export function ServerList({
                               servers,
                               nodes,
                               loading = false,
                               empty = "No servers.",
                               showNodeColumn = true,
                               onRowClick,
                               renderActions,
                               actionsHeader,
                           }: ServerListProps) {
    const {subscribe} = useWs();
    // Live container RAM/CPU usage by server id, from the WS snapshot + metrics stream.
    const [ramUsage, setRamUsage] = useState<Record<string, number>>({});
    const [cpuUsage, setCpuUsage] = useState<Record<string, number>>({});
    const [sortKey, setSortKey] = useState<SortKey | null>(null);
    const [sortDir, setSortDir] = useState<SortDir>("asc");

    useEffect(() => {
        const unsubSnapshot = subscribe("snapshot", (payload) => {
            const nextRam: Record<string, number> = {};
            const nextCpu: Record<string, number> = {};
            for (const s of payload.servers ?? []) {
                if (s.metrics) {
                    nextRam[s.id] = s.metrics.ram_used_mb;
                    nextCpu[s.id] = s.metrics.cpu_percent;
                }
            }
            setRamUsage(nextRam);
            setCpuUsage(nextCpu);
        });
        const unsubMetrics = subscribe("server.metrics", (payload) => {
            setRamUsage((prev) => ({...prev, [payload.server_id]: payload.ram_used_mb}));
            setCpuUsage((prev) => ({...prev, [payload.server_id]: payload.cpu_percent}));
        });
        const unsubStatus = subscribe("server.status", (payload) => {
            if (payload.status !== "STOPPED") return;
            const drop = (prev: Record<string, number>) => {
                if (!(payload.server_id in prev)) return prev;
                const next = {...prev};
                delete next[payload.server_id];
                return next;
            };
            setRamUsage(drop);
            setCpuUsage(drop);
        });
        return () => {
            unsubSnapshot();
            unsubMetrics();
            unsubStatus();
        };
    }, [subscribe]);

    function toggleSort(key: SortKey) {
        if (sortKey === key) {
            setSortDir((d) => (d === "asc" ? "desc" : "asc"));
        } else {
            setSortKey(key);
            setSortDir("asc");
        }
    }

    const nodeMap = useMemo(() => Object.fromEntries(nodes.map((n) => [n.id, n])), [nodes]);

    const sortedServers = useMemo(
        () => sortServers(servers, sortKey, sortDir, nodeMap, cpuUsage),
        [servers, sortKey, sortDir, nodeMap, cpuUsage]
    );

    const columns: SmartListColumn<Server>[] = [
        {
            key: "name",
            header: <>Server<SortIndicator active={sortKey === "name"} dir={sortDir}/></>,
            headerClassName: "cursor-pointer select-none hover:text-accent",
            onHeaderClick: () => toggleSort("name"),
            render: (server) => (
                <>
                    <p className="text-sm font-heading font-medium text-text-primary group-hover:text-accent transition-colors leading-none">
                        {server.display_name}
                    </p>
                    {server.is_migrating && (
                        <p className="mt-1 text-xs font-mono text-warning leading-none">
                            ⟳ Migrating
                        </p>
                    )}
                    {server.disabled ? (
                        <p className="mt-1 text-xs font-mono text-warning leading-none">
                            Disabled
                        </p>
                    ) : serverExpired(server.expires_at) && (
                        <p className="mt-1 text-xs font-mono text-error leading-none">
                            Expired
                        </p>
                    )}
                    {server.restart_pending && server.status !== "STOPPED" && (
                        <p className="mt-1 text-xs font-mono text-warning leading-none">
                            Restart pending
                        </p>
                    )}
                    {server.canonical_hostname && (
                        <p className="mt-0.5 text-xs font-mono text-text-muted leading-none">
                            {server.canonical_hostname}
                        </p>
                    )}
                </>
            ),
        },
        {
            key: "type",
            header: <>Type<SortIndicator active={sortKey === "type"} dir={sortDir}/></>,
            headerClassName: "cursor-pointer select-none hover:text-accent",
            onHeaderClick: () => toggleSort("type"),
            render: (server) => (
                <span
                    className="font-mono text-xs uppercase tracking-wider text-text-dim border border-border px-1.5 py-0.5 rounded"
                    style={{background: "var(--text-dim-bg)"}}
                >
                    {server.server_type}
                </span>
            ),
        },
        {
            key: "status",
            header: <>Status<SortIndicator active={sortKey === "status"} dir={sortDir}/></>,
            headerClassName: "cursor-pointer select-none hover:text-accent",
            onHeaderClick: () => toggleSort("status"),
            render: (server) => (
                <Badge variant={serverStatusVariant(server.status)}>{serverStatusLabel(server.status)}</Badge>
            ),
        },
        {
            key: "players",
            header: "Players",
            render: () => <span className="font-mono text-xs text-text-muted">-/-</span>,
        },
        {
            key: "ram",
            header: <>RAM<SortIndicator active={sortKey === "ram"} dir={sortDir}/></>,
            headerClassName: "cursor-pointer select-none hover:text-accent",
            onHeaderClick: () => toggleSort("ram"),
            render: (server) => <RamBar total={server.memory_mb} used={ramUsage[server.id]}/>,
        },
        {
            key: "cpu",
            header: <>CPU<SortIndicator active={sortKey === "cpu"} dir={sortDir}/></>,
            headerClassName: "cursor-pointer select-none hover:text-accent",
            onHeaderClick: () => toggleSort("cpu"),
            render: (server) => <CpuBar percent={cpuUsage[server.id]} limitMillicores={server.cpu_limit_millicores}/>,
        },
    ];

    if (showNodeColumn) {
        columns.push({
            key: "node",
            header: <>Node<SortIndicator active={sortKey === "node"} dir={sortDir}/></>,
            headerClassName: "cursor-pointer select-none hover:text-accent",
            onHeaderClick: () => toggleSort("node"),
            render: (server) => {
                const node = nodeMap[server.node_id];
                return (
                    <span className="font-mono text-xs text-text-dim">
                        {node?.display_name ?? `${server.node_id.slice(0, 8)}…`}
                    </span>
                );
            },
        });
    }

    function renderMobileCard(server: Server) {
        const node = nodeMap[server.node_id];
        const status = server.status;
        return (
            <div
                onClick={onRowClick ? () => onRowClick(server) : undefined}
                className={`p-3 ${onRowClick ? "cursor-pointer active:bg-surface-high transition-colors" : ""}`}
            >
                <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                        <p className="text-sm font-heading font-medium text-text-primary truncate">{server.display_name}</p>
                        <p className="mt-0.5 font-mono text-xs text-text-dim truncate">
                            {server.server_type}
                            {showNodeColumn && ` · ${node?.display_name ?? `${server.node_id.slice(0, 8)}…`}`}
                        </p>
                        {server.canonical_hostname && (
                            <p className="mt-0.5 font-mono text-xs text-text-muted truncate">{server.canonical_hostname}</p>
                        )}
                        {server.restart_pending && server.status !== "STOPPED" && (
                            <p className="mt-0.5 font-mono text-xs text-warning truncate">Restart pending</p>
                        )}
                    </div>
                    <Badge variant={serverStatusVariant(status)}>{serverStatusLabel(status)}</Badge>
                </div>
                <div className="mt-2.5 flex items-center justify-between gap-3">
                    <div className="flex items-center gap-4">
                        <RamBar total={server.memory_mb} used={ramUsage[server.id]}/>
                        <CpuBar percent={cpuUsage[server.id]} limitMillicores={server.cpu_limit_millicores}/>
                    </div>
                    {renderActions && (
                        <div onClick={(e) => e.stopPropagation()}>
                            {renderActions(server)}
                        </div>
                    )}
                </div>
            </div>
        );
    }

    return (
        <SmartList
            items={sortedServers}
            columns={columns}
            keyFor={(server) => server.id}
            loading={loading}
            skeletonRows={5}
            empty={empty}
            actions={renderActions}
            actionsHeader={actionsHeader}
            onRowClick={onRowClick}
            mobileCard={renderMobileCard}
        />
    );
}
