"use client";

import {useCallback, useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import Link from "next/link";
import {MoreHorizontal, Play, Plus, RotateCcw, Square, Trash2, X} from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import {deleteServer, listNetworks, listNodes, listServers, restartServer, startServer, stopServer} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import type {Network, Node, Server} from "@/lib/types";
import {ConfirmDialog} from "@/components/ui/confirm-dialog";

type DisplayStatus = "HEALTHY" | "UNHEALTHY" | "STARTING" | "STOPPING" | "STOPPED";

function toDisplayStatus(status: string): DisplayStatus {
    return (["HEALTHY", "UNHEALTHY", "STARTING", "STOPPING", "STOPPED"].includes(status)
        ? status
        : "STOPPED") as DisplayStatus;
}

const DISPLAY_LABELS: Record<DisplayStatus, string> = {
    HEALTHY: "Healthy",
    UNHEALTHY: "Unhealthy",
    STARTING: "Starting",
    STOPPING: "Stopping",
    STOPPED: "Stopped",
};

const DISPLAY_CLASSES: Record<DisplayStatus, string> = {
    HEALTHY: "text-healthy  border border-healthy/30  bg-healthy/10",
    UNHEALTHY: "text-error    border border-error/30    bg-error/10",
    STARTING: "text-warning  border border-warning/30  bg-warning/10",
    STOPPING: "text-warning  border border-warning/30  bg-warning/10",
    STOPPED: "text-text-muted border border-border    bg-surface-high",
};

// Filter option → backend statuses that match
const FILTER_MATCHES: Record<string, string[]> = {
    HEALTHY: ["HEALTHY"],
    UNHEALTHY: ["UNHEALTHY"],
    STARTING: ["STARTING", "STOPPING"],
    STOPPED: ["STOPPED"],
};

const FILTER_OPTIONS = [
    {label: "All Statuses", value: ""},
    {label: "Healthy", value: "HEALTHY"},
    {label: "Unhealthy", value: "UNHEALTHY"},
    {label: "Starting", value: "STARTING"},
    {label: "Stopped", value: "STOPPED"},
];

function ramFillColor(pct: number): string {
    if (pct >= 86) return "var(--error)";
    if (pct >= 66) return "var(--warning)";
    return "var(--accent)";
}

function RamBar({total, used}: { total: number; used?: number }) {
    const hasData = used != null;
    const pct = hasData && total > 0 ? Math.min(100, (used! / total) * 100) : 0;
    return (
        <div className="flex flex-col gap-1">
      <span className="font-mono text-[11px] text-text-muted whitespace-nowrap">
        {hasData ? `${used} / ${total} MB` : `— / ${total} MB`}
      </span>
            <div className="w-20 h-1 rounded-full" style={{background: "var(--border)"}}>
                {hasData && pct > 0 && (
                    <div
                        className="h-full rounded-full"
                        style={{width: `${pct}%`, background: ramFillColor(pct)}}
                    />
                )}
            </div>
        </div>
    );
}


function ActionButton({
                          icon,
                          label,
                          loading,
                          onClick,
                          isStop,
                      }: {
    icon: React.ReactNode;
    label: string;
    loading: boolean;
    onClick: () => void;
    isStop?: boolean;
}) {
    const cls = isStop
        ? "bg-error/10 border-error/20 text-error"
        : "bg-surface-high border-border text-text-muted hover:border-border-high hover:text-text-primary";

    return (
        <button
            onClick={onClick}
            disabled={loading}
            title={label}
            className={`flex items-center justify-center px-2 py-1 border rounded-[2px] transition-colors disabled:opacity-40 ${cls}`}
        >
            {loading ? (
                <span className="w-2.5 h-2.5 border border-current border-t-transparent rounded-full animate-spin"/>
            ) : (
                icon
            )}
        </button>
    );
}

export default function ServersPage() {
    const router = useRouter();
    const {user} = useAuth();
    const permissions = user?.permissions ?? [];

    const [servers, setServers] = useState<Server[]>([]);
    const [nodes, setNodes] = useState<Node[]>([]);
    const [networks, setNetworks] = useState<Network[]>([]);
    const [initialLoad, setInitialLoad] = useState(true);
    const [actionError, setActionError] = useState<string | null>(null);
    const [pendingAction, setPendingAction] = useState<Record<string, string>>({});
    const [openMenuId, setOpenMenuId] = useState<string | null>(null);
    const [confirmDialog, setConfirmDialog] = useState<{
        title: string;
        description: string;
        destructive?: boolean;
        onConfirm: () => void;
    } | null>(null);

    const [search, setSearch] = useState("");
    const [filterStatus, setFilterStatus] = useState("");
    const [filterNetwork, setFilterNetwork] = useState("");
    const [filterNode, setFilterNode] = useState("");

    const fetchServers = useCallback(async () => {
        const {data} = await listServers();
        if (data) setServers(data);
    }, []);

    useEffect(() => {
        async function init() {
            await fetchServers();
            setInitialLoad(false);
            // best-effort — non-admins may get 403
            const [{data: nodeData}, {data: networkData}] = await Promise.all([
                listNodes(),
                listNetworks(),
            ]);
            if (nodeData) setNodes(nodeData);
            if (networkData) setNetworks(networkData);
        }

        init();
    }, [fetchServers]);

    useEffect(() => {
        const id = setInterval(fetchServers, 30_000);
        return () => clearInterval(id);
    }, [fetchServers]);

    // Close ··· menu on any document click
    useEffect(() => {
        const handler = () => setOpenMenuId(null);
        document.addEventListener("click", handler);
        return () => document.removeEventListener("click", handler);
    }, []);

    const nodeMap = Object.fromEntries(nodes.map((n) => [n.id, n]));

    // Subtitle: unique node_ids across the full (unfiltered) server list
    const uniqueNodeCount = new Set(servers.map((s) => s.node_id)).size;
    const subtitle = initialLoad
        ? undefined
        : `${servers.length} server${servers.length !== 1 ? "s" : ""} across ${uniqueNodeCount} node${uniqueNodeCount !== 1 ? "s" : ""}`;

    const filteredServers = servers.filter((s) => {
        if (search) {
            const q = search.toLowerCase();
            if (
                !s.display_name.toLowerCase().includes(q) &&
                !s.name.toLowerCase().includes(q) &&
                !(s.public_subdomain?.toLowerCase().includes(q))
            ) return false;
        }
        if (filterStatus) {
            const allowed = FILTER_MATCHES[filterStatus] ?? [];
            if (!allowed.includes(s.status)) return false;
        }
        if (filterNetwork && s.network_id !== filterNetwork) return false;
        if (filterNode && s.node_id !== filterNode) return false;
        return true;
    });

    const ACTION_FNS = {
        start: startServer,
        stop: stopServer,
        restart: restartServer,
    } as const;

    async function doAction(serverId: string, action: "start" | "stop" | "restart") {
        setPendingAction((p) => ({...p, [serverId]: action}));
        setActionError(null);
        try {
            const {error} = await ACTION_FNS[action]({path: {id: serverId}});
            if (error) {
                setActionError(error.message ?? `Failed to ${action} server`);
            } else {
                await fetchServers();
            }
        } catch {
            setActionError(`Failed to ${action} server`);
        } finally {
            setPendingAction((p) => {
                const n = {...p};
                delete n[serverId];
                return n;
            });
        }
    }

    function doDelete(server: Server) {
        setConfirmDialog({
            title: "Delete Server?",
            description: `Delete "${server.display_name}"? This cannot be undone.`,
            destructive: true,
            onConfirm: async () => {
                setActionError(null);
                try {
                    const {error} = await deleteServer({path: {id: server.id}});
                    if (error) {
                        setActionError(error.message ?? "Failed to delete server");
                    } else {
                        await fetchServers();
                    }
                } catch {
                    setActionError("Failed to delete server");
                }
            },
        });
    }

    const canCreate = hasPermission(permissions, "server.create");

    return (
        <>
            <div>
                <PageHeader
                    title="Servers"
                    subtitle={subtitle}
                    action={
                        canCreate ? (
                            <Link
                                href="/servers/new"
                                className="flex items-center gap-1.5 bg-accent hover:bg-accent-bright text-bg font-heading font-bold text-[11px] uppercase tracking-widest px-3 py-1.5 rounded transition-colors hover:shadow-[0_0_16px_var(--accent-glow)]"
                            >
                                <Plus size={12} strokeWidth={3}/>
                                New Server
                            </Link>
                        ) : undefined
                    }
                />

                {/* Filter bar */}
                <div className="flex items-center gap-2 px-6 py-3 border-b border-border bg-surface">
                    <input
                        type="text"
                        placeholder="Search servers…"
                        value={search}
                        onChange={(e) => setSearch(e.target.value)}
                        className="h-7 bg-surface-higher border border-border rounded px-2.5 text-[12px] font-mono text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent w-48"
                    />
                    <select
                        value={filterStatus}
                        onChange={(e) => setFilterStatus(e.target.value)}
                        className="h-7 bg-surface-higher border border-border rounded px-2 text-[12px] font-heading text-text-primary focus:outline-none focus:border-accent"
                    >
                        {FILTER_OPTIONS.map((o) => (
                            <option key={o.value} value={o.value}>{o.label}</option>
                        ))}
                    </select>
                    {networks.length > 0 && (
                        <select
                            value={filterNetwork}
                            onChange={(e) => setFilterNetwork(e.target.value)}
                            className="h-7 bg-surface-higher border border-border rounded px-2 text-[12px] font-heading text-text-primary focus:outline-none focus:border-accent"
                        >
                            <option value="">All Networks</option>
                            {networks.map((n) => (
                                <option key={n.id} value={n.id}>{n.name}</option>
                            ))}
                        </select>
                    )}
                    {nodes.length > 0 && (
                        <select
                            value={filterNode}
                            onChange={(e) => setFilterNode(e.target.value)}
                            className="h-7 bg-surface-higher border border-border rounded px-2 text-[12px] font-heading text-text-primary focus:outline-none focus:border-accent"
                        >
                            <option value="">All Nodes</option>
                            {nodes.map((n) => (
                                <option key={n.id} value={n.id}>{n.display_name}</option>
                            ))}
                        </select>
                    )}
                </div>

                {/* Error banner */}
                {actionError && (
                    <div className="mx-6 mt-4 flex items-center justify-between bg-error/10 border border-error/30 text-error rounded px-3 py-2 text-[12px]">
                        <span>{actionError}</span>
                        <button onClick={() => setActionError(null)} className="ml-4 hover:opacity-70">
                            <X size={13}/>
                        </button>
                    </div>
                )}

                {/* Table */}
                <div className="px-6 py-4">
                    {initialLoad ? (
                        <div className="space-y-2">
                            {Array.from({length: 5}).map((_, i) => (
                                <div key={i} className="h-12 bg-surface rounded animate-pulse"/>
                            ))}
                        </div>
                    ) : filteredServers.length === 0 ? (
                        <div className="border-2 border-dashed border-border rounded-md py-10 text-center text-text-muted text-[13px]">
                            {servers.length === 0
                                ? "No servers yet — create one to get started"
                                : "No servers match the current filters"}
                        </div>
                    ) : (
                        <table className="w-full border-collapse">
                            <thead>
                            <tr className="border-b border-border">
                                {["Server", "Type", "Status", "Players", "RAM", "Node", "Actions"].map(
                                    (col) => (
                                        <th
                                            key={col}
                                            className={[
                                                "pb-2 text-[9px] font-mono font-semibold uppercase tracking-[0.1em] text-text-muted",
                                                col === "Actions" ? "text-right" : "text-left pr-4",
                                            ].join(" ")}
                                        >
                                            {col}
                                        </th>
                                    )
                                )}
                            </tr>
                            </thead>
                            <tbody>
                            {filteredServers.map((server) => {
                                const ds = toDisplayStatus(server.status);
                                const node = nodeMap[server.node_id];
                                const pending = pendingAction[server.id];

                                return (
                                    <tr
                                        key={server.id}
                                        onClick={() => router.push(`/servers/${server.id}`)}
                                        className="border-b border-border hover:bg-surface cursor-pointer group transition-colors"
                                    >
                                        {/* SERVER */}
                                        <td className="py-3 pr-4">
                                            <p className="text-[13px] font-heading font-bold text-text-primary group-hover:text-accent transition-colors leading-none">
                                                {server.display_name}
                                            </p>
                                            {server.is_migrating && (
                                                <p className="mt-1 text-[11px] font-mono text-warning leading-none">
                                                    ⟳ Migrating
                                                </p>
                                            )}
                                            {server.exposed_externally && server.public_subdomain && (
                                                <p className="mt-0.5 text-[11px] font-mono text-text-muted leading-none">
                                                    {server.public_subdomain}
                                                </p>
                                            )}
                                        </td>

                                        {/* TYPE */}
                                        <td className="py-3 pr-4">
                      <span
                          className="font-mono text-[10px] uppercase tracking-wider text-text-dim border border-border px-1.5 py-0.5 rounded"
                          style={{background: "var(--text-dim-bg)"}}
                      >
                        {server.server_type}
                      </span>
                                        </td>

                                        {/* STATUS */}
                                        <td className="py-3 pr-4">
                      <span
                          className={`text-[11px] font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${DISPLAY_CLASSES[ds]}`}
                      >
                        {DISPLAY_LABELS[ds]}
                      </span>
                                        </td>

                                        {/* PLAYERS */}
                                        <td className="py-3 pr-4">
                                            <span className="font-mono text-[11px] text-text-muted">—/—</span>
                                        </td>

                                        {/* RAM */}
                                        <td className="py-3 pr-4">
                                            <RamBar total={server.memory_mb}/>
                                        </td>

                                        {/* NODE */}
                                        <td className="py-3 pr-4">
                      <span className="font-mono text-[11px] text-text-dim">
                        {node?.display_name ?? `${server.node_id.slice(0, 8)}…`}
                      </span>
                                        </td>

                                        {/* ACTIONS */}
                                        <td
                                            className="py-3 text-right"
                                            onClick={(e) => e.stopPropagation()}
                                        >
                                            <div className="flex items-center justify-end gap-1">
                                                {ds === "STOPPED" && hasPermission(permissions, "server.start") && (
                                                    <ActionButton
                                                        icon={<Play size={11} strokeWidth={2.5}/>}
                                                        label="Start"
                                                        loading={pending === "start"}
                                                        onClick={() => doAction(server.id, "start")}
                                                    />
                                                )}
                                                {(ds === "HEALTHY" || ds === "STARTING") && hasPermission(permissions, "server.stop") && (
                                                    <ActionButton
                                                        icon={<Square size={11} strokeWidth={2.5}/>}
                                                        label="Stop"
                                                        loading={pending === "stop"}
                                                        onClick={() => doAction(server.id, "stop")}
                                                        isStop
                                                    />
                                                )}
                                                {ds === "HEALTHY" && hasPermission(permissions, "server.restart") && (
                                                    <ActionButton
                                                        icon={<RotateCcw size={11} strokeWidth={2.5}/>}
                                                        label="Restart"
                                                        loading={pending === "restart"}
                                                        onClick={() => doAction(server.id, "restart")}
                                                    />
                                                )}
                                                {ds === "STOPPED" && hasPermission(permissions, "server.delete") && (
                                                    <div onClick={(e) => e.stopPropagation()}>
                                                        <ActionButton
                                                            icon={<Trash2 size={11} strokeWidth={2.5}/>}
                                                            label="Delete"
                                                            loading={false}
                                                            onClick={() => doDelete(server)}
                                                            isStop
                                                        />
                                                    </div>
                                                )}

                                                {/* ··· overflow menu */}
                                                <div className="relative">
                                                    <button
                                                        onClick={(e) => {
                                                            e.stopPropagation();
                                                            setOpenMenuId((id) => (id === server.id ? null : server.id));
                                                        }}
                                                        className="flex items-center justify-center px-2 py-1 border rounded-[2px] border-border bg-surface-high text-text-muted hover:border-border-high hover:text-text-primary transition-colors"
                                                    >
                                                        <MoreHorizontal size={12} strokeWidth={2}/>
                                                    </button>

                                                    {openMenuId === server.id && (
                                                        <div
                                                            className="absolute right-0 top-full mt-1 z-50 bg-surface-higher border border-border rounded shadow-xl min-w-[130px] py-1"
                                                            onClick={(e) => e.stopPropagation()}
                                                        >
                                                            <Link
                                                                href={`/servers/${server.id}`}
                                                                onClick={() => setOpenMenuId(null)}
                                                                className="flex items-center px-3 py-1.5 text-[12px] font-heading font-bold uppercase tracking-wider text-text-primary hover:bg-surface-high transition-colors"
                                                            >
                                                                View
                                                            </Link>
                                                            {ds === "STOPPED" && hasPermission(permissions, "server.delete") && (
                                                                <button
                                                                    onClick={() => {
                                                                        setOpenMenuId(null);
                                                                        doDelete(server);
                                                                    }}
                                                                    className="flex items-center w-full text-left px-3 py-1.5 text-[12px] font-heading font-bold uppercase tracking-wider text-error hover:bg-surface-high transition-colors"
                                                                >
                                                                    Delete
                                                                </button>
                                                            )}
                                                        </div>
                                                    )}
                                                </div>
                                            </div>
                                        </td>
                                    </tr>
                                );
                            })}
                            </tbody>
                        </table>
                    )}
                </div>
            </div>
            <ConfirmDialog
                open={confirmDialog !== null}
                onOpenChange={(open) => !open && setConfirmDialog(null)}
                title={confirmDialog?.title ?? ""}
                description={confirmDialog?.description ?? ""}
                destructive={confirmDialog?.destructive}
                onConfirm={confirmDialog?.onConfirm ?? (() => {
                })}
            />
        </>
    );
}
