"use client";

import {useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import {Ban, Check, KeyRound, MoreHorizontal, Pencil, Power, Trash2, X} from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import {decommissionNode, listNodes, listServers, rejectNode, rotateNodeToken, shutdownNode, trustNode, updateNode,} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import type {Node} from "@/lib/types";
import {timeAgo, fmtMb, fillColor} from "@/lib/utils/format";
import {TokenModal} from "@/components/nodes/TokenModal";
import {useConfirmDialog} from "@/lib/hooks/useConfirmDialog";
import {useResourceList} from "@/lib/hooks/useResourceList";
import {useWs} from "@/lib/ws-context";
import {nodeDisplayStatus, nodeStatusClass, nodeStatusLabel} from "@/lib/status";
import {ListTh, ListTd} from "@/components/ui/list-table";

const STATUS_FILTER_OPTIONS = [
    {label: "All Statuses", value: ""},
    {label: "Active", value: "ACTIVE"},
    {label: "Pending", value: "PENDING"},
    {label: "Degraded", value: "DEGRADED"},
    {label: "Rejected", value: "REJECTED"},
    {label: "Decommissioned", value: "DECOMMISSIONED"},
];

// ── Sub-components ────────────────────────────────────────────────────────────

function fmtShares(shares: number): string {
    const cores = shares / 1024;
    return cores >= 1 ? `${cores % 1 === 0 ? cores : cores.toFixed(1)}c` : `${shares}`;
}

function MiniBar({used, total, fmt = fmtMb}: { used: number; total: number; fmt?: (n: number) => string }) {
    const pct = total > 0 ? Math.min(100, (used / total) * 100) : 0;
    return (
        <div className="flex flex-col gap-1">
      <span className="font-mono text-xs text-text-muted whitespace-nowrap">
        {fmt(used)} / {fmt(total)}
      </span>
            <div className="w-20 h-1 rounded-full" style={{background: "var(--border)"}}>
                <div
                    className="h-full rounded-full"
                    style={{width: `${pct}%`, background: fillColor(pct)}}
                />
            </div>
        </div>
    );
}

// ── Row actions (shared by desktop table + mobile card) ─────────────────────────

function NodeActions({
                         node, pending, servers, canManage, openMenuId, setOpenMenuId,
                         doTrust, doReject, doRotateToken, doShutdown, doDecommission, setEditNode,
                     }: {
    node: Node;
    pending: string | undefined;
    servers: number;
    canManage: boolean;
    openMenuId: string | null;
    setOpenMenuId: React.Dispatch<React.SetStateAction<string | null>>;
    doTrust: (id: string) => void;
    doReject: (id: string) => void;
    doRotateToken: (id: string) => void;
    doShutdown: (id: string, name: string) => void;
    doDecommission: (node: Node) => void;
    setEditNode: (node: Node) => void;
}) {
    return (
        <div className="flex items-center justify-end gap-1">
            {/* PENDING: Trust + Reject */}
            {node.status === "PENDING" && canManage && (
                <>
                    <button
                        onClick={() => doTrust(node.id)}
                        disabled={!!pending}
                        title="Trust node"
                        className="flex items-center gap-1 px-2 py-1 text-xs font-heading font-bold uppercase tracking-wider border rounded-[2px] text-healthy border-healthy/40 hover:bg-healthy/10 transition-colors disabled:opacity-40"
                    >
                        {pending === "trust" ? (
                            <span className="w-2.5 h-2.5 border border-current border-t-transparent rounded-full animate-spin"/>
                        ) : (
                            <Check size={11} strokeWidth={2.5}/>
                        )}
                        Trust
                    </button>
                    <button
                        onClick={() => doReject(node.id)}
                        disabled={!!pending}
                        title="Reject node"
                        className="flex items-center gap-1 px-2 py-1 text-xs font-heading font-bold uppercase tracking-wider border rounded-[2px] text-error border-error/40 hover:bg-error/10 transition-colors disabled:opacity-40"
                    >
                        {pending === "reject" ? (
                            <span className="w-2.5 h-2.5 border border-current border-t-transparent rounded-full animate-spin"/>
                        ) : (
                            <Ban size={11} strokeWidth={2.5}/>
                        )}
                        Reject
                    </button>
                </>
            )}

            {/* Non-PENDING: View + ··· menu */}
            {node.status !== "PENDING" && (
                <>
                    {/* No explicit View action — the whole row/card is clickable to open the node. */}
                    <div className="relative">
                        <button
                            onClick={(e) => {
                                // stopImmediatePropagation on the native event: the document-level
                                // close listener is native, so React's stopPropagation alone lets it
                                // fire and immediately re-close the menu.
                                e.nativeEvent.stopImmediatePropagation();
                                setOpenMenuId((id) => (id === node.id ? null : node.id));
                            }}
                            className="flex items-center justify-center px-2 py-1 border rounded-[2px] border-border bg-surface-high text-text-muted hover:border-border-high hover:text-text-primary transition-colors"
                        >
                            <MoreHorizontal size={12} strokeWidth={2}/>
                        </button>

                        {openMenuId === node.id && (
                            <div
                                className="absolute right-0 top-full mt-1 z-50 bg-surface-higher border border-border rounded shadow-xl min-w-[150px] py-1"
                                onClick={(e) => e.stopPropagation()}
                            >
                                <button
                                    onClick={() => {
                                        setOpenMenuId(null);
                                        setEditNode(node);
                                    }}
                                    className="flex items-center gap-2 w-full text-left px-3 py-1.5 text-xs font-heading font-bold uppercase tracking-wider text-text-primary hover:bg-surface-high transition-colors"
                                >
                                    <Pencil size={11} strokeWidth={2}/>
                                    Edit
                                </button>
                                <button
                                    onClick={() => {
                                        setOpenMenuId(null);
                                        doRotateToken(node.id);
                                    }}
                                    className="flex items-center gap-2 w-full text-left px-3 py-1.5 text-xs font-heading font-bold uppercase tracking-wider text-text-primary hover:bg-surface-high transition-colors"
                                >
                                    <KeyRound size={11} strokeWidth={2}/>
                                    Rotate Key
                                </button>
                                {node.status === "ACTIVE" && (
                                    <button
                                        onClick={() => {
                                            setOpenMenuId(null);
                                            doShutdown(node.id, node.display_name);
                                        }}
                                        className="flex items-center gap-2 w-full text-left px-3 py-1.5 text-xs font-heading font-bold uppercase tracking-wider text-warning hover:bg-surface-high transition-colors"
                                    >
                                        <Power size={11} strokeWidth={2}/>
                                        Shutdown
                                    </button>
                                )}
                                {servers === 0 && (
                                    <button
                                        onClick={() => {
                                            setOpenMenuId(null);
                                            doDecommission(node);
                                        }}
                                        className="flex items-center gap-2 w-full text-left px-3 py-1.5 text-xs font-heading font-bold uppercase tracking-wider text-error hover:bg-surface-high transition-colors"
                                    >
                                        <Trash2 size={11} strokeWidth={2}/>
                                        Decommission
                                    </button>
                                )}
                            </div>
                        )}
                    </div>
                </>
            )}
        </div>
    );
}

// ── Edit modal ─────────────────────────────────────────────────────────────────

function EditModal({
                       node,
                       onClose,
                       onSaved,
                   }: {
    node: Node;
    onClose: () => void;
    onSaved: () => void;
}) {
    const [displayName, setDisplayName] = useState(node.display_name);
    const [portStart, setPortStart] = useState(String(node.port_range_start));
    const [portEnd, setPortEnd] = useState(String(node.port_range_end));
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    async function save() {
        setSaving(true);
        setError(null);
        try {
            const {error: e} = await updateNode({
                path: {id: node.id},
                body: {
                    display_name: displayName || undefined,
                    port_range_start: portStart ? parseInt(portStart) : undefined,
                    port_range_end: portEnd ? parseInt(portEnd) : undefined,
                },
            });
            if (e) {
                setError(e.message ?? "Failed to save");
            } else {
                onSaved();
                onClose();
            }
        } catch {
            setError("Failed to save");
        } finally {
            setSaving(false);
        }
    }

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
            <div className="bg-surface-higher border border-border rounded shadow-2xl w-[400px] p-6">
                <div className="flex items-center justify-between mb-5">
                    <p className="text-sm font-heading font-bold uppercase tracking-widest text-text-primary">
                        Edit Node
                    </p>
                    <button onClick={onClose} className="text-text-muted hover:text-text-primary">
                        <X size={14}/>
                    </button>
                </div>

                {error && (
                    <div className="mb-4 text-xs text-error bg-error/10 border border-error/30 rounded px-3 py-2">
                        {error}
                    </div>
                )}

                <div className="space-y-4">
                    <label className="block">
            <span className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">
              Display Name
            </span>
                        <input
                            value={displayName}
                            onChange={(e) => setDisplayName(e.target.value)}
                            className="w-full h-8 bg-surface border border-border rounded px-2.5 text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
                        />
                    </label>
                    <div className="grid grid-cols-2 gap-3">
                        <label className="block">
              <span className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">
                Port Range Start
              </span>
                            <input
                                type="number"
                                value={portStart}
                                onChange={(e) => setPortStart(e.target.value)}
                                className="w-full h-8 bg-surface border border-border rounded px-2.5 text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
                            />
                        </label>
                        <label className="block">
              <span className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">
                Port Range End
              </span>
                            <input
                                type="number"
                                value={portEnd}
                                onChange={(e) => setPortEnd(e.target.value)}
                                className="w-full h-8 bg-surface border border-border rounded px-2.5 text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
                            />
                        </label>
                    </div>
                </div>

                <div className="flex justify-end gap-2 mt-6">
                    <button
                        onClick={onClose}
                        className="px-3 py-1.5 text-xs font-heading font-bold uppercase tracking-widest text-text-muted border border-border rounded hover:bg-surface-high transition-colors"
                    >
                        Cancel
                    </button>
                    <button
                        onClick={save}
                        disabled={saving}
                        className="px-3 py-1.5 text-xs font-heading font-bold uppercase tracking-widest bg-accent text-bg rounded hover:bg-accent-bright transition-colors disabled:opacity-40"
                    >
                        {saving ? "Saving…" : "Save"}
                    </button>
                </div>
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

    const {data: nodes, initialLoad, reload: reloadNodes, setData: setNodes} = useResourceList(listNodes);
    const [serverCounts, setServerCounts] = useState<Record<string, number>>({});
    const [actionError, setActionError] = useState<string | null>(null);
    const [pendingAction, setPendingAction] = useState<Record<string, string>>({});
    const [openMenuId, setOpenMenuId] = useState<string | null>(null);
    const {confirm, dialog} = useConfirmDialog();

    const [filterStatus, setFilterStatus] = useState("");

    // Modals
    const [editNode, setEditNode] = useState<Node | null>(null);
    const [tokenKey, setTokenKey] = useState<string | null>(null);

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
        const handler = () => setOpenMenuId(null);
        document.addEventListener("click", handler);
        return () => document.removeEventListener("click", handler);
    }, []);

    useEffect(() => {
        return subscribe("node.status", (payload) => {
            setNodes((prev) =>
                prev.map((n) => n.id === payload.node_id ? {...n, health: payload.health} : n),
            );
        });
    }, [subscribe, setNodes]);

    // ── Actions ────────────────────────────────────────────────────────────────

    async function doTrust(nodeId: string) {
        setPendingAction((p) => ({...p, [nodeId]: "trust"}));
        setActionError(null);
        const {error} = await trustNode({path: {id: nodeId}});
        if (error) {
            setActionError(error.message ?? "Failed to trust node");
        } else {
            reloadNodes();
        }
        setPendingAction((p) => {
            const n = {...p};
            delete n[nodeId];
            return n;
        });
    }

    function doReject(nodeId: string) {
        confirm({
            title: "Reject Node?",
            description: "The agent will not be able to connect.",
            destructive: true,
            onConfirm: async () => {
                setPendingAction((p) => ({...p, [nodeId]: "reject"}));
                setActionError(null);
                const {error: rejectErr} = await rejectNode({path: {id: nodeId}});
                if (rejectErr) {
                    setActionError(rejectErr.message ?? "Failed to reject node");
                } else {
                    reloadNodes();
                }
                setPendingAction((p) => {
                    const n = {...p};
                    delete n[nodeId];
                    return n;
                });
            },
        });
    }

    function doRotateToken(nodeId: string) {
        confirm({
            title: "Rotate Node Key?",
            description: "The agent will need to re-register.",
            onConfirm: async () => {
                setPendingAction((p) => ({...p, [nodeId]: "rotate"}));
                setActionError(null);
                const {error: rotateErr, data: rotateData} = await rotateNodeToken({path: {id: nodeId}});
                if (rotateErr) {
                    setActionError(rotateErr.message ?? "Failed to rotate key");
                } else if (rotateData?.node_key) {
                    setTokenKey(rotateData.node_key);
                }
                setPendingAction((p) => {
                    const n = {...p};
                    delete n[nodeId];
                    return n;
                });
            },
        });
    }

    function doShutdown(nodeId: string, displayName: string) {
        confirm({
            title: "Shutdown Node?",
            description: `Send shutdown command to "${displayName}"?`,
            onConfirm: async () => {
                setPendingAction((p) => ({...p, [nodeId]: "shutdown"}));
                setActionError(null);
                const {error: shutdownErr} = await shutdownNode({path: {id: nodeId}});
                if (shutdownErr) {
                    setActionError(shutdownErr.message ?? "Failed to shutdown node");
                } else {
                    reloadNodes();
                }
                setPendingAction((p) => {
                    const n = {...p};
                    delete n[nodeId];
                    return n;
                });
            },
        });
    }

    function doDecommission(node: Node) {
        confirm({
            title: "Decommission Node?",
            description: `Decommission "${node.display_name}"? This cannot be undone.`,
            destructive: true,
            onConfirm: async () => {
                setPendingAction((p) => ({...p, [node.id]: "decommission"}));
                setActionError(null);
                const {error: decomErr} = await decommissionNode({path: {id: node.id}});
                if (decomErr) {
                    setActionError(decomErr.message ?? "Failed to decommission node");
                } else {
                    reloadNodes();
                }
                setPendingAction((p) => {
                    const n = {...p};
                    delete n[node.id];
                    return n;
                });
            },
        });
    }

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
            <PageHeader title="Nodes" subtitle={subtitle}/>

            {/* Filter bar */}
            <div className="flex items-center gap-2 px-6 py-3 border-b border-border bg-surface">
                <select
                    value={filterStatus}
                    onChange={(e) => setFilterStatus(e.target.value)}
                    className="h-7 bg-surface-higher border border-border rounded px-2 text-xs font-heading text-text-primary focus:outline-none focus:border-accent"
                >
                    {STATUS_FILTER_OPTIONS.map((o) => (
                        <option key={o.value} value={o.value}>{o.label}</option>
                    ))}
                </select>
            </div>

            {/* Pending callout */}
            {!initialLoad && pendingNodes.length > 0 && (
                <div className="mx-6 mt-4 flex items-center gap-3 bg-warning/10 border border-warning/30 rounded px-4 py-2.5 text-warning text-xs font-heading font-bold uppercase tracking-wider">
                    <span className="w-2 h-2 rounded-full bg-warning shrink-0"/>
                    {pendingNodes.length} node{pendingNodes.length !== 1 ? "s" : ""} awaiting approval - review below
                </div>
            )}

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
                        {Array.from({length: 4}).map((_, i) => (
                            <div key={i} className="h-12 bg-surface rounded animate-pulse"/>
                        ))}
                    </div>
                ) : filtered.length === 0 ? (
                    <div className="border-2 border-dashed border-border rounded-md py-10 text-center text-text-muted text-sm">
                        {nodes.length === 0
                            ? "No nodes registered yet - start an agent with a bootstrap token"
                            : "No nodes match the current filter"}
                    </div>
                ) : (
                    <div className="bg-surface border border-border rounded-md overflow-hidden hidden md:block">
                        <table className="w-full text-xs">
                            <thead>
                            <tr className="border-b border-border">
                                <ListTh>Node</ListTh>
                                <ListTh>Status</ListTh>
                                <ListTh>RAM</ListTh>
                                <ListTh>CPU</ListTh>
                                <ListTh>Servers</ListTh>
                                <ListTh>Last Seen</ListTh>
                                <ListTh/>
                            </tr>
                            </thead>
                            <tbody>
                            {filtered.map((node) => {
                                const pending = pendingAction[node.id];
                                const servers = serverCounts[node.id] ?? 0;
                                const lastSeen = node.last_seen_at;
                                const stale = lastSeen
                                    ? (renderNow - new Date(lastSeen).getTime()) / 1000 > 300
                                    : true;

                                return (
                                    <tr
                                        key={node.id}
                                        onClick={() => router.push(`/nodes/${node.id}`)}
                                        className="border-b border-border/50 hover:bg-surface-high/40 cursor-pointer group transition-colors"
                                    >
                                        {/* NODE */}
                                        <ListTd firstCol>
                                            <p className="text-sm font-heading font-bold text-text-primary group-hover:text-accent transition-colors leading-none">
                                                {node.display_name}
                                            </p>
                                            <p className="mt-0.5 font-mono text-xs text-text-muted leading-none">
                                                {node.hostname}
                                            </p>
                                        </ListTd>

                                        {/* STATUS */}
                                        <ListTd>
                        <span
                            className={`text-xs font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${nodeStatusClass(node.status, node.health)}`}
                        >
                          {nodeStatusLabel(node.status, node.health)}
                        </span>
                                        </ListTd>

                                        {/* RAM */}
                                        <ListTd>
                                            <MiniBar used={Math.max(node.allocated_ram_mb, node.system_ram_used_mb ?? 0)} total={node.total_ram_mb}/>
                                        </ListTd>

                                        {/* CPU */}
                                        <ListTd>
                                            <MiniBar used={node.allocated_cpu_shares} total={node.total_cpu_shares} fmt={fmtShares}/>
                                        </ListTd>

                                        {/* SERVERS */}
                                        <ListTd>
                                            <span className="font-mono text-xs text-text-dim">{servers}</span>
                                        </ListTd>

                                        {/* LAST SEEN */}
                                        <ListTd>
                        <span
                            className={`font-mono text-xs ${stale ? "text-error" : "text-text-muted"}`}
                        >
                          {lastSeen ? timeAgo(lastSeen) : "never"}
                        </span>
                                        </ListTd>

                                        {/* ACTIONS */}
                                        <ListTd className="text-right">
                                            <div onClick={(e) => e.stopPropagation()}>
                                                <NodeActions
                                                    node={node} pending={pending} servers={servers} canManage={canManage}
                                                    openMenuId={openMenuId} setOpenMenuId={setOpenMenuId}
                                                    doTrust={doTrust} doReject={doReject} doRotateToken={doRotateToken}
                                                    doShutdown={doShutdown} doDecommission={doDecommission} setEditNode={setEditNode}
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

                {/* Mobile card list (< md) */}
                {!initialLoad && filtered.length > 0 && (
                    <div className="md:hidden divide-y divide-border">
                        {filtered.map((node) => {
                            const pending = pendingAction[node.id];
                            const servers = serverCounts[node.id] ?? 0;
                            const lastSeen = node.last_seen_at;
                            const stale = lastSeen
                                ? (renderNow - new Date(lastSeen).getTime()) / 1000 > 300
                                : true;
                            return (
                                <div
                                    key={node.id}
                                    onClick={() => router.push(`/nodes/${node.id}`)}
                                    className="p-3 cursor-pointer active:bg-surface-high transition-colors"
                                >
                                    <div className="flex items-start justify-between gap-2">
                                        <div className="min-w-0">
                                            <p className="text-sm font-heading font-bold text-text-primary truncate">{node.display_name}</p>
                                            <p className="mt-0.5 font-mono text-xs text-text-muted truncate">{node.hostname}</p>
                                        </div>
                                        <span className={`shrink-0 text-xs font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${nodeStatusClass(node.status, node.health)}`}>
                                            {nodeStatusLabel(node.status, node.health)}
                                        </span>
                                    </div>
                                    <div className="mt-2.5 grid grid-cols-2 gap-x-4 gap-y-1.5">
                                        <div>
                                            <p className="text-xs text-text-muted">RAM</p>
                                            <MiniBar used={Math.max(node.allocated_ram_mb, node.system_ram_used_mb ?? 0)} total={node.total_ram_mb}/>
                                        </div>
                                        <div>
                                            <p className="text-xs text-text-muted">CPU</p>
                                            <MiniBar used={node.allocated_cpu_shares} total={node.total_cpu_shares} fmt={fmtShares}/>
                                        </div>
                                        <div>
                                            <p className="text-xs text-text-muted">Servers</p>
                                            <p className="font-mono text-xs text-text-dim">{servers}</p>
                                        </div>
                                        <div>
                                            <p className="text-xs text-text-muted">Last seen</p>
                                            <p className={`font-mono text-xs ${stale ? "text-error" : "text-text-muted"}`}>{lastSeen ? timeAgo(lastSeen) : "never"}</p>
                                        </div>
                                    </div>
                                    <div className="mt-2.5 flex justify-end" onClick={(e) => e.stopPropagation()}>
                                        <NodeActions
                                            node={node} pending={pending} servers={servers} canManage={canManage}
                                            openMenuId={openMenuId} setOpenMenuId={setOpenMenuId}
                                            doTrust={doTrust} doReject={doReject} doRotateToken={doRotateToken}
                                            doShutdown={doShutdown} doDecommission={doDecommission} setEditNode={setEditNode}
                                        />
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                )}
            </div>

            {/* Modals */}
            {editNode && (
                <EditModal
                    node={editNode}
                    onClose={() => setEditNode(null)}
                    onSaved={reloadNodes}
                />
            )}
            {tokenKey && (
                <TokenModal nodeKey={tokenKey} onClose={() => setTokenKey(null)}/>
            )}
            {dialog}
        </div>
    );
}
