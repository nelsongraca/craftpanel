"use client";

import {useEffect, useMemo, useState} from "react";
import {useRouter} from "next/navigation";
import Link from "next/link";
import {MoreHorizontal, Play, Plus, RotateCcw, Square, Trash2, X} from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import {deleteServer, listNetworks, listNodes, listServers, restartServer, startServer, stopServer} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import type {Network, Node, Server} from "@/lib/types";
import {useConfirmDialog} from "@/lib/hooks/useConfirmDialog";
import {useResourceList} from "@/lib/hooks/useResourceList";
import {ListTh, ListTd, ListActions, IconActionButton} from "@/components/ui/list-table";
import {fillColor} from "@/lib/utils/format";
import {serverStatusClass, serverStatusLabel} from "@/lib/status";

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

function RamBar({total, used}: { total: number; used?: number }) {
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
    const tone = isStop
        ? "text-text-muted hover:text-error"
        : "text-text-muted hover:text-text-primary";

    return (
        <button
            onClick={onClick}
            disabled={loading}
            title={label}
            className={`p-1.5 rounded hover:bg-surface-higher ${tone} transition-colors disabled:opacity-40`}
        >
            {loading ? (
                <span className="w-2.5 h-2.5 border border-current border-t-transparent rounded-full animate-spin"/>
            ) : (
                icon
            )}
        </button>
    );
}

function ServerActions({
                           server, status, pending, permissions, openMenuId, setOpenMenuId, doAction, doDelete,
                       }: {
    server: Server;
    status: string;
    pending: string | undefined;
    permissions: string[];
    openMenuId: string | null;
    setOpenMenuId: React.Dispatch<React.SetStateAction<string | null>>;
    doAction: (id: string, action: "start" | "stop" | "restart") => void;
    doDelete: (s: Server) => void;
}) {
    return (
        <div className="flex items-center justify-end gap-1">
            {status === "STOPPED" && hasPermission(permissions, "server.start") && (
                <ActionButton
                    icon={<Play size={11} strokeWidth={2.5}/>}
                    label="Start"
                    loading={pending === "start"}
                    onClick={() => doAction(server.id, "start")}
                />
            )}
            {(status === "HEALTHY" || status === "STARTING") && hasPermission(permissions, "server.stop") && (
                <ActionButton
                    icon={<Square size={11} strokeWidth={2.5}/>}
                    label="Stop"
                    loading={pending === "stop"}
                    onClick={() => doAction(server.id, "stop")}
                    isStop
                />
            )}
            {status === "HEALTHY" && hasPermission(permissions, "server.restart") && (
                <ActionButton
                    icon={<RotateCcw size={11} strokeWidth={2.5}/>}
                    label="Restart"
                    loading={pending === "restart"}
                    onClick={() => doAction(server.id, "restart")}
                />
            )}
            {status === "STOPPED" && hasPermission(permissions, "server.delete") && (
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
                        // stopImmediatePropagation on the native event: the document-level
                        // close listener is native, so React's stopPropagation alone lets it
                        // fire and immediately re-close the menu.
                        e.nativeEvent.stopImmediatePropagation();
                        setOpenMenuId((id) => (id === server.id ? null : server.id));
                    }}
                    className="flex items-center justify-center p-1.5 rounded hover:bg-surface-higher text-text-muted hover:text-text-primary transition-colors"
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
                            className="flex items-center px-3 py-1.5 text-xs font-heading font-bold uppercase tracking-wider text-text-primary hover:bg-surface-high transition-colors"
                        >
                            View
                        </Link>
                        {status === "STOPPED" && hasPermission(permissions, "server.delete") && (
                            <button
                                onClick={() => {
                                    setOpenMenuId(null);
                                    doDelete(server);
                                }}
                                className="flex items-center w-full text-left px-3 py-1.5 text-xs font-heading font-bold uppercase tracking-wider text-error hover:bg-surface-high transition-colors"
                            >
                                Delete
                            </button>
                        )}
                    </div>
                )}
            </div>
        </div>
    );
}

type SortKey = "name" | "type" | "status" | "ram" | "node";
type SortDir = "asc" | "desc";

function SortIndicator({active, dir}: { active: boolean; dir: SortDir }) {
    if (!active) return <span className="text-text-muted/50 ml-1">↕</span>;
    return <span className="text-accent ml-1">{dir === "asc" ? "↑" : "↓"}</span>;
}

function sortServers(servers: Server[], key: SortKey | null, dir: SortDir, nodeMap: Record<string, Node>): Server[] {
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

export default function ServersPage() {
    const router = useRouter();
    const {user} = useAuth();
    const permissions = user?.permissions ?? [];

    const {data: servers, initialLoad, reload: reloadServers} = useResourceList(listServers);
    const [nodes, setNodes] = useState<Node[]>([]);
    const [networks, setNetworks] = useState<Network[]>([]);
    const [actionError, setActionError] = useState<string | null>(null);
    const [pendingAction, setPendingAction] = useState<Record<string, string>>({});
    const [openMenuId, setOpenMenuId] = useState<string | null>(null);
    const {confirm, dialog} = useConfirmDialog();

    const [search, setSearch] = useState("");
    const [filterStatus, setFilterStatus] = useState("");
    const [filterNetwork, setFilterNetwork] = useState("");
    const [filterNode, setFilterNode] = useState("");
    const [filterType, setFilterType] = useState("");

    const [sortKey, setSortKey] = useState<SortKey | null>(null);
    const [sortDir, setSortDir] = useState<SortDir>("asc");

    const typeOptions = useMemo(() => {
        const types = Array.from(new Set(servers.map((s) => s.server_type))).sort();
        return types;
    }, [servers]);

    function toggleSort(key: SortKey) {
        if (sortKey === key) {
            setSortDir((d) => (d === "asc" ? "desc" : "asc"));
        } else {
            setSortKey(key);
            setSortDir("asc");
        }
    }

    useEffect(() => {
        void Promise.all([listNodes(), listNetworks()]).then(([nRes, netRes]) => {
            if (nRes.data) setNodes(nRes.data);
            if (netRes.data) setNetworks(netRes.data);
        });
    }, []);

    // Close ··· menu on any document click
    useEffect(() => {
        const handler = () => setOpenMenuId(null);
        document.addEventListener("click", handler);
        return () => document.removeEventListener("click", handler);
    }, []);

    const nodeMap = Object.fromEntries(nodes.map((n) => [n.id, n]));

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
        if (filterType && s.server_type !== filterType) return false;
        return true;
    });

    const sortedServers = useMemo(
        () => sortServers(filteredServers, sortKey, sortDir, nodeMap),
        [filteredServers, sortKey, sortDir, nodeMap]
    );

    const ACTION_FNS = {
        start: startServer,
        stop: stopServer,
        restart: restartServer,
    } as const;

    async function doAction(serverId: string, action: "start" | "stop" | "restart") {
        setPendingAction((p) => ({...p, [serverId]: action}));
        setActionError(null);
        const {error} = await ACTION_FNS[action]({path: {id: serverId}});
        if (error) {
            setActionError(error.message ?? "Action failed");
        } else {
            reloadServers();
        }
        setPendingAction((p) => {
            const n = {...p};
            delete n[serverId];
            return n;
        });
    }

    function doDelete(server: Server) {
        confirm({
            title: "Delete Server?",
            description: `Delete "${server.display_name}"? This cannot be undone.`,
            destructive: true,
            onConfirm: async () => {
                setActionError(null);
                const {error} = await deleteServer({path: {id: server.id}});
                if (error) {
                    setActionError(error.message ?? "Failed to delete server");
                } else {
                    reloadServers();
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
                                className="flex items-center gap-1.5 bg-accent hover:bg-accent-bright text-bg font-heading font-bold text-xs uppercase tracking-widest px-3 py-1.5 rounded transition-colors hover:shadow-[0_0_16px_var(--accent-glow)]"
                            >
                                <Plus size={12} strokeWidth={3}/>
                                New Server
                            </Link>
                        ) : undefined
                    }
                />

                {/* Filter bar */}
                <div className="flex flex-wrap items-center gap-2 px-6 py-3 border-b border-border bg-surface">
                    <input
                        type="text"
                        placeholder="Search servers…"
                        value={search}
                        onChange={(e) => setSearch(e.target.value)}
                        className="h-7 bg-surface-higher border border-border rounded px-2.5 text-xs font-mono text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent w-48"
                    />
                    <select
                        value={filterStatus}
                        onChange={(e) => setFilterStatus(e.target.value)}
                        className="h-7 bg-surface-higher border border-border rounded px-2 text-xs font-heading text-text-primary focus:outline-none focus:border-accent"
                    >
                        {FILTER_OPTIONS.map((o) => (
                            <option key={o.value} value={o.value}>{o.label}</option>
                        ))}
                    </select>
                    {networks.length > 0 && (
                        <select
                            value={filterNetwork}
                            onChange={(e) => setFilterNetwork(e.target.value)}
                            className="h-7 bg-surface-higher border border-border rounded px-2 text-xs font-heading text-text-primary focus:outline-none focus:border-accent"
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
                            className="h-7 bg-surface-higher border border-border rounded px-2 text-xs font-heading text-text-primary focus:outline-none focus:border-accent"
                        >
                            <option value="">All Nodes</option>
                            {nodes.map((n) => (
                                <option key={n.id} value={n.id}>{n.display_name}</option>
                            ))}
                        </select>
                    )}
                    {typeOptions.length > 0 && (
                        <select
                            value={filterType}
                            onChange={(e) => setFilterType(e.target.value)}
                            className="h-7 bg-surface-higher border border-border rounded px-2 text-xs font-heading text-text-primary focus:outline-none focus:border-accent"
                        >
                            <option value="">All Types</option>
                            {typeOptions.map((t) => (
                                <option key={t} value={t}>{t}</option>
                            ))}
                        </select>
                    )}
                </div>

                {/* Error banner */}
                {actionError && (
                    <div className="mx-6 mt-4 flex items-center justify-between bg-error/10 border border-error/30 text-error rounded px-3 py-2 text-xs">
                        <span>{actionError}</span>
                        <button onClick={() => setActionError(null)} className="ml-4 hover:opacity-70" aria-label="Dismiss">
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
                    ) : sortedServers.length === 0 ? (
                        <div className="border-2 border-dashed border-border rounded-md py-10 text-center text-text-muted text-sm">
                            {servers.length === 0
                                ? "No servers yet - create one to get started"
                                : "No servers match the current filters"}
                        </div>
                    ) : (
                        <div className="bg-surface border border-border rounded-md overflow-hidden">
                            <table className="hidden md:table w-full text-xs">
                                <thead>
                                <tr className="border-b border-border">
                                    <ListTh
                                        align="left"
                                        className="cursor-pointer select-none hover:text-accent"
                                        onClick={() => toggleSort("name")}
                                    >
                                        Server<SortIndicator active={sortKey === "name"} dir={sortDir}/>
                                    </ListTh>
                                    <ListTh
                                        align="left"
                                        className="cursor-pointer select-none hover:text-accent"
                                        onClick={() => toggleSort("type")}
                                    >
                                        Type<SortIndicator active={sortKey === "type"} dir={sortDir}/>
                                    </ListTh>
                                    <ListTh
                                        align="left"
                                        className="cursor-pointer select-none hover:text-accent"
                                        onClick={() => toggleSort("status")}
                                    >
                                        Status<SortIndicator active={sortKey === "status"} dir={sortDir}/>
                                    </ListTh>
                                    <ListTh align="left">Players</ListTh>
                                    <ListTh
                                        align="left"
                                        className="cursor-pointer select-none hover:text-accent"
                                        onClick={() => toggleSort("ram")}
                                    >
                                        RAM<SortIndicator active={sortKey === "ram"} dir={sortDir}/>
                                    </ListTh>
                                    <ListTh
                                        align="left"
                                        className="cursor-pointer select-none hover:text-accent"
                                        onClick={() => toggleSort("node")}
                                    >
                                        Node<SortIndicator active={sortKey === "node"} dir={sortDir}/>
                                    </ListTh>
                                    <ListTh align="right">Actions</ListTh>
                                </tr>
                                </thead>
                                <tbody>
                                {sortedServers.map((server) => {
                                    const node = nodeMap[server.node_id];
                                    const pending = pendingAction[server.id];
                                    const status = server.status;

                                    return (
                                        <tr
                                            key={server.id}
                                            onClick={() => router.push(`/servers/${server.id}`)}
                                            className="border-b border-border/50 hover:bg-surface-high/40 cursor-pointer group transition-colors"
                                        >
                                            {/* SERVER */}
                                            <ListTd firstCol>
                                                <p className="text-sm font-heading font-bold text-text-primary group-hover:text-accent transition-colors leading-none">
                                                    {server.display_name}
                                                </p>
                                                {server.is_migrating && (
                                                    <p className="mt-1 text-xs font-mono text-warning leading-none">
                                                        ⟳ Migrating
                                                    </p>
                                                )}
                                                {server.exposed_externally && server.public_subdomain && (
                                                    <p className="mt-0.5 text-xs font-mono text-text-muted leading-none">
                                                        {server.public_subdomain}
                                                    </p>
                                                )}
                                            </ListTd>

                                            {/* TYPE */}
                                            <ListTd>
                      <span
                          className="font-mono text-xs uppercase tracking-wider text-text-dim border border-border px-1.5 py-0.5 rounded"
                          style={{background: "var(--text-dim-bg)"}}
                      >
                        {server.server_type}
                      </span>
                                            </ListTd>

                                            {/* STATUS */}
                                            <ListTd>
                      <span
                          className={`text-xs font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${serverStatusClass(status)}`}
                      >
                        {serverStatusLabel(status)}
                      </span>
                                            </ListTd>

                                            {/* PLAYERS */}
                                            <ListTd>
                                                <span className="font-mono text-xs text-text-muted">-/-</span>
                                            </ListTd>

                                            {/* RAM */}
                                            <ListTd>
                                                <RamBar total={server.memory_mb}/>
                                            </ListTd>

                                            {/* NODE */}
                                            <ListTd>
                      <span className="font-mono text-xs text-text-dim">
                        {node?.display_name ?? `${server.node_id.slice(0, 8)}…`}
                      </span>
                                            </ListTd>

                                            {/* ACTIONS */}
                                            <ListTd className="text-right">
                                                <div onClick={(e) => e.stopPropagation()}>
                                                    <ServerActions
                                                        server={server} status={status} pending={pending}
                                                        permissions={permissions}
                                                        openMenuId={openMenuId} setOpenMenuId={setOpenMenuId}
                                                        doAction={doAction} doDelete={doDelete}
                                                    />
                                                </div>
                                            </ListTd>
                                        </tr>
                                    );
                                })}
                                </tbody>
                            </table>
                        </div>
                    )}

                    {/* Mobile card list (mobile) */}
                    {!initialLoad && sortedServers.length > 0 && (
                        <div className="md:hidden divide-y divide-border">
                            {sortedServers.map((server) => {
                                const node = nodeMap[server.node_id];
                                const pending = pendingAction[server.id];
                                const status = server.status;
                                return (
                                    <div
                                        key={server.id}
                                        onClick={() => router.push(`/servers/${server.id}`)}
                                        className="p-3 cursor-pointer active:bg-surface-high transition-colors"
                                    >
                                        <div className="flex items-start justify-between gap-2">
                                            <div className="min-w-0">
                                                <p className="text-sm font-heading font-bold text-text-primary truncate">{server.display_name}</p>
                                                <p className="mt-0.5 font-mono text-xs text-text-dim truncate">
                                                    {server.server_type} · {node?.display_name ?? `${server.node_id.slice(0, 8)}…`}
                                                </p>
                                                {server.exposed_externally && server.public_subdomain && (
                                                    <p className="mt-0.5 font-mono text-xs text-text-muted truncate">{server.public_subdomain}</p>
                                                )}
                                            </div>
                                            <span className={`shrink-0 text-xs font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${serverStatusClass(status)}`}>
                                                {serverStatusLabel(status)}
                                            </span>
                                        </div>
                                        <div className="mt-2.5 flex items-center justify-between gap-2">
                                            <RamBar total={server.memory_mb}/>
                                            <div onClick={(e) => e.stopPropagation()}>
                                                <ServerActions
                                                    server={server} status={status} pending={pending}
                                                    permissions={permissions}
                                                    openMenuId={openMenuId} setOpenMenuId={setOpenMenuId}
                                                    doAction={doAction} doDelete={doDelete}
                                                />
                                            </div>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    )}
                </div>
            </div>
            {dialog}
        </>
    );
}
