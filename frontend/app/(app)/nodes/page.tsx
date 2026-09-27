"use client";

import {useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import {X} from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import {listNodes, listServers} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import type {Node} from "@/lib/types";
import {EditNodeModal} from "@/components/nodes/edit-node-modal";
import {AgentVersion} from "@/components/nodes/agent-version";
import {timeAgo, fmtMb, fillColor, fmtPct} from "@/lib/utils/format";
import {TokenModal} from "@/components/nodes/token-modal";
import {NodeActions, useNodeActions} from "@/components/nodes/node-actions";
import {useHealth} from "@/lib/hooks/useHealth";
import {useResourceList} from "@/lib/hooks/useResourceList";
import {useWs} from "@/lib/ws-context";
import {nodeDisplayStatus, nodeStatusLabel, nodeStatusVariant} from "@/lib/status";
import {Badge} from "@/components/ui/badge";
import {SelectField} from "@/components/ui/form-elements";
import {SmartList, type SmartListColumn} from "@/components/ui/smart-list";

const STATUS_FILTER_OPTIONS = [
    {label: "All Statuses", value: ""},
    {label: "Active", value: "ACTIVE"},
    {label: "Pending", value: "PENDING"},
    {label: "Degraded", value: "DEGRADED"},
    {label: "Rejected", value: "REJECTED"},
    {label: "Decommissioned", value: "DECOMMISSIONED"},
];

// ── Sub-components ────────────────────────────────────────────────────────────

function MiniBar({used, total, fmt = fmtMb}: {used: number; total: number; fmt?: (n: number) => string}) {
    const pct = total > 0 ? Math.min(100, (used / total) * 100) : 0;
    return (
        <div className="flex flex-col gap-1">
            <span className="font-mono text-xs whitespace-nowrap text-text-muted">
                {fmt(used)} / {fmt(total)}
            </span>
            <div className="h-1 w-20 rounded-full" style={{background: "var(--border)"}}>
                <div className="h-full rounded-full" style={{width: `${pct}%`, background: fillColor(pct)}} />
            </div>
        </div>
    );
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function NodesPage() {
    const router = useRouter();
    const {user} = useAuth();
    const {subscribe} = useWs();
    const permissions = user?.permissions ?? [];

    const {data: nodes, initialLoad, reload: reloadNodes, setData: setNodes} = useResourceList(listNodes, []);
    const health = useHealth();
    const [serverCounts, setServerCounts] = useState<Record<string, number>>({});

    const [filterStatus, setFilterStatus] = useState("");

    // Modals
    const [editNode, setEditNode] = useState<Node | null>(null);
    const [tokenKey, setTokenKey] = useState<string | null>(null);

    const {
        allowedActions,
        trust,
        reject,
        rotate,
        shutdown,
        decommission,
        pendingFor,
        actionError,
        setActionError,
        dialog,
    } = useNodeActions({
        onChanged: reloadNodes,
        onTokenRotated: setTokenKey,
    });

    useEffect(() => {
        listServers().then(({data: serverData}) => {
            if (serverData) {
                const counts: Record<string, number> = {};
                for (const s of serverData) {
                    counts[s.node_id] = (counts[s.node_id] ?? 0) + 1;
                }
                setServerCounts(counts);
            }
        });
    }, []);

    useEffect(() => {
        return subscribe("node.status", (payload) => {
            setNodes((prev) => prev.map((n) => (n.id === payload.node_id ? {...n, health: payload.health} : n)));
        });
    }, [subscribe, setNodes]);

    // ── Derived ────────────────────────────────────────────────────────────────

    const canManage = hasPermission(permissions, "system.nodes");

    const pendingNodes = nodes.filter((n) => n.status === "PENDING");
    const activeCount = nodes.filter((n) => n.status === "ACTIVE").length;

    const subtitle = initialLoad
        ? undefined
        : `${nodes.length} node${nodes.length !== 1 ? "s" : ""} · ${activeCount} active · ${pendingNodes.length} pending`;

    const filtered = nodes.filter((n) => {
        if (filterStatus && nodeDisplayStatus(n.status, n.health) !== filterStatus) return false;
        return true;
    });

    const renderNow = new Date().getTime();

    // ── Render ─────────────────────────────────────────────────────────────────

    return (
        <div>
            <PageHeader title="Nodes" subtitle={subtitle} />

            {/* Filter bar */}
            <div className="flex items-center gap-2 border-b border-border bg-surface px-4 py-3">
                <SelectField
                    surface="surface-higher"
                    fieldSize="sm"
                    className="h-7 w-40 font-heading"
                    value={filterStatus}
                    onChange={(e) => setFilterStatus(e.target.value)}
                >
                    {STATUS_FILTER_OPTIONS.map((o) => (
                        <option key={o.value} value={o.value}>
                            {o.label}
                        </option>
                    ))}
                </SelectField>
            </div>

            {/* Pending callout */}
            {!initialLoad && pendingNodes.length > 0 && (
                <div className="mx-4 mt-4 flex items-center gap-3 rounded border border-warning/30 bg-warning/10 px-4 py-2.5 font-heading text-xs font-bold tracking-wider text-warning uppercase">
                    <span className="h-2 w-2 shrink-0 rounded-full bg-warning" />
                    {pendingNodes.length} node{pendingNodes.length !== 1 ? "s" : ""} awaiting approval - review below
                </div>
            )}

            {/* Error banner */}
            {actionError && (
                <div className="mx-4 mt-4 flex items-center justify-between rounded border border-error/30 bg-error/10 px-3 py-2 text-xs text-error">
                    <span>{actionError}</span>
                    <button onClick={() => setActionError(null)} className="ml-4 hover:opacity-70" aria-label="Dismiss">
                        <X size={13} />
                    </button>
                </div>
            )}

            {/* List */}
            <div className="px-4 py-4">
                {(() => {
                    const NODE_COLUMNS: SmartListColumn<Node>[] = [
                        {
                            key: "node",
                            header: "Node",
                            title: true,
                            render: (n) => (
                                <>
                                    <p className="font-heading text-sm leading-none font-bold text-text-primary transition-colors group-hover:text-accent">
                                        {n.display_name}
                                    </p>
                                    <p className="mt-0.5 font-mono text-xs leading-none text-text-muted">
                                        {n.hostname}
                                    </p>
                                </>
                            ),
                        },
                        {
                            key: "status",
                            header: "Status",
                            label: "Status",
                            render: (n) => (
                                <Badge variant={nodeStatusVariant(n.status, n.health)}>
                                    {nodeStatusLabel(n.status, n.health)}
                                </Badge>
                            ),
                        },
                        {
                            key: "ram",
                            header: "RAM",
                            label: "RAM",
                            render: (n) => (
                                <MiniBar
                                    used={Math.max(n.allocated_ram_mb, n.system_ram_used_mb ?? 0)}
                                    total={n.total_ram_mb}
                                />
                            ),
                        },
                        {
                            key: "cpu",
                            header: "CPU",
                            label: "CPU",
                            render: (n) => <MiniBar used={n.system_cpu_percent ?? 0} total={100} fmt={fmtPct} />,
                        },
                        {
                            key: "servers",
                            header: "Servers",
                            label: "Servers",
                            render: (n) => (
                                <span className="font-mono text-xs text-text-dim">{serverCounts[n.id] ?? 0}</span>
                            ),
                        },
                        {
                            key: "version",
                            header: "Version",
                            label: "Version",
                            render: (n) => (
                                <AgentVersion version={n.agent_version} masterVersion={health?.masterVersion} />
                            ),
                        },
                        {
                            key: "lastSeen",
                            header: "Last Seen",
                            label: "Last seen",
                            render: (n) => {
                                const lastSeen = n.last_seen_at;
                                const stale = lastSeen ? (renderNow - new Date(lastSeen).getTime()) / 1000 > 300 : true;
                                return (
                                    <span className={`font-mono text-xs ${stale ? "text-error" : "text-text-muted"}`}>
                                        {lastSeen ? timeAgo(lastSeen) : "never"}
                                    </span>
                                );
                            },
                        },
                    ];

                    const renderActions = (node: Node) => (
                        <NodeActions
                            actions={canManage ? allowedActions(node, serverCounts[node.id] ?? 0) : []}
                            pending={pendingFor(node.id)}
                            onTrust={() => trust(node.id)}
                            onReject={() => reject(node.id)}
                            onRotate={() => rotate(node.id)}
                            onShutdown={() => shutdown(node)}
                            onDecommission={() => decommission(node)}
                            onEdit={() => setEditNode(node)}
                        />
                    );

                    const renderMobileCard = (node: Node) => {
                        const servers = serverCounts[node.id] ?? 0;
                        const lastSeen = node.last_seen_at;
                        const stale = lastSeen ? (renderNow - new Date(lastSeen).getTime()) / 1000 > 300 : true;
                        return (
                            <div
                                onClick={() => router.push(`/nodes/${node.id}`)}
                                className="cursor-pointer p-3 transition-colors active:bg-surface-high"
                            >
                                <div className="flex items-start justify-between gap-2">
                                    <div className="min-w-0">
                                        <p className="truncate font-heading text-sm font-bold text-text-primary">
                                            {node.display_name}
                                        </p>
                                        <p className="mt-0.5 truncate font-mono text-xs text-text-muted">
                                            {node.hostname}
                                        </p>
                                    </div>
                                    <Badge variant={nodeStatusVariant(node.status, node.health)}>
                                        {nodeStatusLabel(node.status, node.health)}
                                    </Badge>
                                </div>
                                <div className="mt-2.5 grid grid-cols-2 gap-x-4 gap-y-1.5">
                                    <div>
                                        <p className="text-xs text-text-muted">RAM</p>
                                        <MiniBar
                                            used={Math.max(node.allocated_ram_mb, node.system_ram_used_mb ?? 0)}
                                            total={node.total_ram_mb}
                                        />
                                    </div>
                                    <div>
                                        <p className="text-xs text-text-muted">CPU</p>
                                        <MiniBar used={node.system_cpu_percent ?? 0} total={100} fmt={fmtPct} />
                                    </div>
                                    <div>
                                        <p className="text-xs text-text-muted">Servers</p>
                                        <p className="font-mono text-xs text-text-dim">{servers}</p>
                                    </div>
                                    <div>
                                        <p className="text-xs text-text-muted">Last seen</p>
                                        <p className={`font-mono text-xs ${stale ? "text-error" : "text-text-muted"}`}>
                                            {lastSeen ? timeAgo(lastSeen) : "never"}
                                        </p>
                                    </div>
                                    <div>
                                        <p className="text-xs text-text-muted">Version</p>
                                        <p className="font-mono text-xs text-text-dim">
                                            <AgentVersion
                                                version={node.agent_version}
                                                masterVersion={health?.masterVersion}
                                            />
                                        </p>
                                    </div>
                                </div>
                                <div className="mt-2.5 flex justify-end" onClick={(e) => e.stopPropagation()}>
                                    {renderActions(node)}
                                </div>
                            </div>
                        );
                    };

                    return (
                        <SmartList
                            items={filtered}
                            columns={NODE_COLUMNS}
                            keyFor={(n) => n.id}
                            loading={initialLoad}
                            skeletonRows={4}
                            empty={
                                nodes.length === 0
                                    ? "No nodes registered yet - start an agent with a bootstrap token"
                                    : "No nodes match the current filter"
                            }
                            actions={renderActions}
                            actionsHeader=""
                            onRowClick={(n) => router.push(`/nodes/${n.id}`)}
                            mobileCard={renderMobileCard}
                        />
                    );
                })()}
            </div>

            {/* Modals */}
            {editNode && <EditNodeModal node={editNode} onClose={() => setEditNode(null)} onSaved={reloadNodes} />}
            {tokenKey && <TokenModal nodeKey={tokenKey} onClose={() => setTokenKey(null)} />}
            {dialog}
        </div>
    );
}
