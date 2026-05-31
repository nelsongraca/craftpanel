"use client";

import {useCallback, useEffect, useState} from "react";
import {useRouter} from "next/navigation";
import Link from "next/link";
import {Ban, Check, KeyRound, MoreHorizontal, Pencil, Power, Trash2, X} from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import {decommissionNode, listNodes, listServers, rejectNode, rotateNodeToken, shutdownNode, trustNode, updateNode,} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import type {Node} from "@/lib/types";
import {timeAgo} from "@/lib/utils/format";
import {TokenModal} from "@/components/nodes/TokenModal";
import {ConfirmDialog} from "@/components/ui/confirm-dialog";
import {useWs} from "@/lib/ws-context";

// ── Status helpers ────────────────────────────────────────────────────────────

type NodeStatus = "ACTIVE" | "PENDING" | "DEGRADED" | "REJECTED" | "DECOMMISSIONED";

function toNodeStatus(status: string): NodeStatus {
    return (["ACTIVE", "PENDING", "DEGRADED", "REJECTED", "DECOMMISSIONED"].includes(status)
        ? status
        : "PENDING") as NodeStatus;
}

const STATUS_LABELS: Record<NodeStatus, string> = {
    ACTIVE: "Active",
    PENDING: "Pending",
    DEGRADED: "Degraded",
    REJECTED: "Rejected",
    DECOMMISSIONED: "Decommissioned",
};

const STATUS_CLASSES: Record<NodeStatus, string> = {
    ACTIVE: "text-healthy  border border-healthy/30  bg-healthy/10",
    PENDING: "text-warning  border border-warning/30  bg-warning/10",
    DEGRADED: "text-error    border border-error/30    bg-error/10",
    REJECTED: "text-text-muted border border-border   bg-surface-high",
    DECOMMISSIONED: "text-text-muted border border-border   bg-surface-high",
};

const STATUS_FILTER_OPTIONS = [
    {label: "All Statuses", value: ""},
    {label: "Active", value: "ACTIVE"},
    {label: "Pending", value: "PENDING"},
    {label: "Degraded", value: "DEGRADED"},
    {label: "Rejected", value: "REJECTED"},
    {label: "Decommissioned", value: "DECOMMISSIONED"},
];

// ── Utilities ─────────────────────────────────────────────────────────────────

function fmtMb(mb: number): string {
    if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`;
    return `${mb} MB`;
}

function fillColor(pct: number): string {
    if (pct >= 86) return "var(--error)";
    if (pct >= 66) return "var(--warning)";
    return "var(--accent)";
}

// ── Sub-components ────────────────────────────────────────────────────────────

function fmtShares(shares: number): string {
    const cores = shares / 1024;
    return cores >= 1 ? `${cores % 1 === 0 ? cores : cores.toFixed(1)}c` : `${shares}`;
}

function MiniBar({used, total, fmt = fmtMb}: { used: number; total: number; fmt?: (n: number) => string }) {
    const pct = total > 0 ? Math.min(100, (used / total) * 100) : 0;
    return (
        <div className="flex flex-col gap-1">
      <span className="font-mono text-[11px] text-text-muted whitespace-nowrap">
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
                    display_name: displayName || null,
                    port_range_start: portStart ? parseInt(portStart) : null,
                    port_range_end: portEnd ? parseInt(portEnd) : null,
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
                    <p className="text-[13px] font-heading font-bold uppercase tracking-widest text-text-primary">
                        Edit Node
                    </p>
                    <button onClick={onClose} className="text-text-muted hover:text-text-primary">
                        <X size={14}/>
                    </button>
                </div>

                {error && (
                    <div className="mb-4 text-[12px] text-error bg-error/10 border border-error/30 rounded px-3 py-2">
                        {error}
                    </div>
                )}

                <div className="space-y-4">
                    <label className="block">
            <span className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">
              Display Name
            </span>
                        <input
                            value={displayName}
                            onChange={(e) => setDisplayName(e.target.value)}
                            className="w-full h-8 bg-surface border border-border rounded px-2.5 text-[12px] font-mono text-text-primary focus:outline-none focus:border-accent"
                        />
                    </label>
                    <div className="grid grid-cols-2 gap-3">
                        <label className="block">
              <span className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">
                Port Range Start
              </span>
                            <input
                                type="number"
                                value={portStart}
                                onChange={(e) => setPortStart(e.target.value)}
                                className="w-full h-8 bg-surface border border-border rounded px-2.5 text-[12px] font-mono text-text-primary focus:outline-none focus:border-accent"
                            />
                        </label>
                        <label className="block">
              <span className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">
                Port Range End
              </span>
                            <input
                                type="number"
                                value={portEnd}
                                onChange={(e) => setPortEnd(e.target.value)}
                                className="w-full h-8 bg-surface border border-border rounded px-2.5 text-[12px] font-mono text-text-primary focus:outline-none focus:border-accent"
                            />
                        </label>
                    </div>
                </div>

                <div className="flex justify-end gap-2 mt-6">
                    <button
                        onClick={onClose}
                        className="px-3 py-1.5 text-[11px] font-heading font-bold uppercase tracking-widest text-text-muted border border-border rounded hover:bg-surface-high transition-colors"
                    >
                        Cancel
                    </button>
                    <button
                        onClick={save}
                        disabled={saving}
                        className="px-3 py-1.5 text-[11px] font-heading font-bold uppercase tracking-widest bg-accent text-bg rounded hover:bg-accent-bright transition-colors disabled:opacity-40"
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

    const [nodes, setNodes] = useState<Node[]>([]);
    const [serverCounts, setServerCounts] = useState<Record<string, number>>({});
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

    const [filterStatus, setFilterStatus] = useState("");

    // Modals
    const [editNode, setEditNode] = useState<Node | null>(null);
    const [tokenKey, setTokenKey] = useState<string | null>(null);

    const fetchNodes = useCallback(async () => {
        const {data} = await listNodes();
        if (data) setNodes(data);
    }, []);

    useEffect(() => {
        async function init() {
            await fetchNodes();
            setInitialLoad(false);
            const {data: serverData} = await listServers();
            if (serverData) {
                const counts: Record<string, number> = {};
                for (const s of serverData) {
                    counts[s.node_id] = (counts[s.node_id] ?? 0) + 1;
                }
                setServerCounts(counts);
            }
        }

        init();
    }, [fetchNodes]);

    useEffect(() => {
        const id = setInterval(fetchNodes, 30_000);
        return () => clearInterval(id);
    }, [fetchNodes]);

    useEffect(() => {
        const handler = () => setOpenMenuId(null);
        document.addEventListener("click", handler);
        return () => document.removeEventListener("click", handler);
    }, []);

    useEffect(() => {
        return subscribe("node.status", (payload) => {
            const {node_id, status} = payload as { node_id: string; status: string };
            setNodes((prev) =>
                prev.map((n) => n.id === node_id ? {...n, status} : n),
            );
        });
    }, [subscribe]);

    // ── Actions ────────────────────────────────────────────────────────────────

    async function doTrust(nodeId: string) {
        setPendingAction((p) => ({...p, [nodeId]: "trust"}));
        setActionError(null);
        try {
            const {error} = await trustNode({path: {id: nodeId}});
            if (error) {
                setActionError(error.message ?? "Failed to trust node");
            } else {
                await fetchNodes();
            }
        } catch {
            setActionError("Failed to trust node");
        } finally {
            setPendingAction((p) => {
                const n = {...p};
                delete n[nodeId];
                return n;
            });
        }
    }

    function doReject(nodeId: string) {
        setConfirmDialog({
            title: "Reject Node?",
            description: "The agent will not be able to connect.",
            destructive: true,
            onConfirm: async () => {
                setPendingAction((p) => ({...p, [nodeId]: "reject"}));
                setActionError(null);
                try {
                    const {error} = await rejectNode({path: {id: nodeId}});
                    if (error) {
                        setActionError(error.message ?? "Failed to reject node");
                    } else {
                        await fetchNodes();
                    }
                } catch {
                    setActionError("Failed to reject node");
                } finally {
                    setPendingAction((p) => {
                        const n = {...p};
                        delete n[nodeId];
                        return n;
                    });
                }
            },
        });
    }

    function doRotateToken(nodeId: string) {
        setConfirmDialog({
            title: "Rotate Node Key?",
            description: "The agent will need to re-register.",
            onConfirm: async () => {
                setPendingAction((p) => ({...p, [nodeId]: "rotate"}));
                setActionError(null);
                try {
                    const {data, error} = await rotateNodeToken({path: {id: nodeId}});
                    if (error) {
                        setActionError(error.message ?? "Failed to rotate token");
                    } else if (data) {
                        setTokenKey(data.node_key);
                    }
                } catch {
                    setActionError("Failed to rotate token");
                } finally {
                    setPendingAction((p) => {
                        const n = {...p};
                        delete n[nodeId];
                        return n;
                    });
                }
            },
        });
    }

    function doShutdown(nodeId: string, displayName: string) {
        setConfirmDialog({
            title: "Shutdown Node?",
            description: `Send shutdown command to "${displayName}"?`,
            onConfirm: async () => {
                setPendingAction((p) => ({...p, [nodeId]: "shutdown"}));
                setActionError(null);
                try {
                    const {error} = await shutdownNode({path: {id: nodeId}});
                    if (error) {
                        setActionError(error.message ?? "Failed to shutdown node");
                    } else {
                        await fetchNodes();
                    }
                } catch {
                    setActionError("Failed to shutdown node");
                } finally {
                    setPendingAction((p) => {
                        const n = {...p};
                        delete n[nodeId];
                        return n;
                    });
                }
            },
        });
    }

    function doDecommission(node: Node) {
        setConfirmDialog({
            title: "Decommission Node?",
            description: `Decommission "${node.display_name}"? This cannot be undone.`,
            destructive: true,
            onConfirm: async () => {
                setPendingAction((p) => ({...p, [node.id]: "decommission"}));
                setActionError(null);
                try {
                    const {error} = await decommissionNode({path: {id: node.id}});
                    if (error) {
                        setActionError(error.message ?? "Failed to decommission node");
                    } else {
                        await fetchNodes();
                    }
                } catch {
                    setActionError("Failed to decommission node");
                } finally {
                    setPendingAction((p) => {
                        const n = {...p};
                        delete n[node.id];
                        return n;
                    });
                }
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
        if (filterStatus && n.status !== filterStatus) return false;
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
                    className="h-7 bg-surface-higher border border-border rounded px-2 text-[12px] font-heading text-text-primary focus:outline-none focus:border-accent"
                >
                    {STATUS_FILTER_OPTIONS.map((o) => (
                        <option key={o.value} value={o.value}>{o.label}</option>
                    ))}
                </select>
            </div>

            {/* Pending callout */}
            {!initialLoad && pendingNodes.length > 0 && (
                <div className="mx-6 mt-4 flex items-center gap-3 bg-warning/10 border border-warning/30 rounded px-4 py-2.5 text-warning text-[12px] font-heading font-bold uppercase tracking-wider">
                    <span className="w-2 h-2 rounded-full bg-warning shrink-0"/>
                    {pendingNodes.length} node{pendingNodes.length !== 1 ? "s" : ""} awaiting approval — review below
                </div>
            )}

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
                        {Array.from({length: 4}).map((_, i) => (
                            <div key={i} className="h-12 bg-surface rounded animate-pulse"/>
                        ))}
                    </div>
                ) : filtered.length === 0 ? (
                    <div className="border-2 border-dashed border-border rounded-md py-10 text-center text-text-muted text-[13px]">
                        {nodes.length === 0
                            ? "No nodes registered yet — start an agent with a bootstrap token"
                            : "No nodes match the current filter"}
                    </div>
                ) : (
                    <table className="w-full border-collapse">
                        <thead>
                        <tr className="border-b border-border">
                            {["Node", "Status", "RAM", "CPU", "Servers", "Last Seen", "Actions"].map((col) => (
                                <th
                                    key={col}
                                    className={[
                                        "pb-2 text-[9px] font-mono font-semibold uppercase tracking-[0.1em] text-text-muted",
                                        col === "Actions" ? "text-right" : "text-left pr-4",
                                    ].join(" ")}
                                >
                                    {col}
                                </th>
                            ))}
                        </tr>
                        </thead>
                        <tbody>
                        {filtered.map((node) => {
                            const ns = toNodeStatus(node.status);
                            const pending = pendingAction[node.id];
                            const servers = serverCounts[node.id] ?? 0;
                            const lastSeen = node.last_seen_at;
                            const stale = lastSeen
                                ? (renderNow - new Date(lastSeen).getTime()) / 1000 > 120
                                : true;

                            return (
                                <tr
                                    key={node.id}
                                    onClick={() => router.push(`/nodes/${node.id}`)}
                                    className="border-b border-border hover:bg-surface cursor-pointer group transition-colors"
                                >
                                    {/* NODE */}
                                    <td className="py-3 pr-4">
                                        <p className="text-[13px] font-heading font-bold text-text-primary group-hover:text-accent transition-colors leading-none">
                                            {node.display_name}
                                        </p>
                                        <p className="mt-0.5 font-mono text-[11px] text-text-muted leading-none">
                                            {node.hostname}
                                        </p>
                                    </td>

                                    {/* STATUS */}
                                    <td className="py-3 pr-4">
                      <span
                          className={`text-[11px] font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${STATUS_CLASSES[ns]}`}
                      >
                        {STATUS_LABELS[ns]}
                      </span>
                                    </td>

                                    {/* RAM */}
                                    <td className="py-3 pr-4">
                                        <MiniBar used={Math.max(node.allocated_ram_mb, node.system_ram_used_mb ?? 0)} total={node.total_ram_mb}/>
                                    </td>

                                    {/* CPU */}
                                    <td className="py-3 pr-4">
                                        <MiniBar used={node.allocated_cpu_shares} total={node.total_cpu_shares} fmt={fmtShares}/>
                                    </td>

                                    {/* SERVERS */}
                                    <td className="py-3 pr-4">
                                        <span className="font-mono text-[12px] text-text-dim">{servers}</span>
                                    </td>

                                    {/* LAST SEEN */}
                                    <td className="py-3 pr-4">
                      <span
                          className={`font-mono text-[11px] ${stale ? "text-error" : "text-text-muted"}`}
                      >
                        {lastSeen ? timeAgo(lastSeen) : "never"}
                      </span>
                                    </td>

                                    {/* ACTIONS */}
                                    <td
                                        className="py-3 text-right"
                                        onClick={(e) => e.stopPropagation()}
                                    >
                                        <div className="flex items-center justify-end gap-1">

                                            {/* PENDING: Trust + Reject */}
                                            {ns === "PENDING" && canManage && (
                                                <>
                                                    <button
                                                        onClick={() => doTrust(node.id)}
                                                        disabled={!!pending}
                                                        title="Trust node"
                                                        className="flex items-center gap-1 px-2 py-1 text-[11px] font-heading font-bold uppercase tracking-wider border rounded-[2px] text-healthy border-healthy/40 hover:bg-healthy/10 transition-colors disabled:opacity-40"
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
                                                        className="flex items-center gap-1 px-2 py-1 text-[11px] font-heading font-bold uppercase tracking-wider border rounded-[2px] text-error border-error/40 hover:bg-error/10 transition-colors disabled:opacity-40"
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
                                            {ns !== "PENDING" && (
                                                <>
                                                    <Link
                                                        href={`/nodes/${node.id}`}
                                                        onClick={(e) => e.stopPropagation()}
                                                        className="flex items-center justify-center px-2 py-1 border rounded-[2px] border-border bg-surface-high text-text-muted hover:border-border-high hover:text-text-primary transition-colors text-[11px] font-heading font-bold uppercase tracking-wider"
                                                    >
                                                        View
                                                    </Link>

                                                    <div className="relative">
                                                        <button
                                                            onClick={(e) => {
                                                                e.stopPropagation();
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
                                                                    className="flex items-center gap-2 w-full text-left px-3 py-1.5 text-[12px] font-heading font-bold uppercase tracking-wider text-text-primary hover:bg-surface-high transition-colors"
                                                                >
                                                                    <Pencil size={11} strokeWidth={2}/>
                                                                    Edit
                                                                </button>
                                                                <button
                                                                    onClick={() => {
                                                                        setOpenMenuId(null);
                                                                        doRotateToken(node.id);
                                                                    }}
                                                                    className="flex items-center gap-2 w-full text-left px-3 py-1.5 text-[12px] font-heading font-bold uppercase tracking-wider text-text-primary hover:bg-surface-high transition-colors"
                                                                >
                                                                    <KeyRound size={11} strokeWidth={2}/>
                                                                    Rotate Key
                                                                </button>
                                                                {ns === "ACTIVE" && (
                                                                    <button
                                                                        onClick={() => {
                                                                            setOpenMenuId(null);
                                                                            doShutdown(node.id, node.display_name);
                                                                        }}
                                                                        className="flex items-center gap-2 w-full text-left px-3 py-1.5 text-[12px] font-heading font-bold uppercase tracking-wider text-warning hover:bg-surface-high transition-colors"
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
                                                                        className="flex items-center gap-2 w-full text-left px-3 py-1.5 text-[12px] font-heading font-bold uppercase tracking-wider text-error hover:bg-surface-high transition-colors"
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
                                    </td>
                                </tr>
                            );
                        })}
                        </tbody>
                    </table>
                )}
            </div>

            {/* Modals */}
            {editNode && (
                <EditModal
                    node={editNode}
                    onClose={() => setEditNode(null)}
                    onSaved={fetchNodes}
                />
            )}
            {tokenKey && (
                <TokenModal nodeKey={tokenKey} onClose={() => setTokenKey(null)}/>
            )}
            <ConfirmDialog
                open={confirmDialog !== null}
                onOpenChange={(open) => !open && setConfirmDialog(null)}
                title={confirmDialog?.title ?? ""}
                description={confirmDialog?.description ?? ""}
                destructive={confirmDialog?.destructive}
                onConfirm={confirmDialog?.onConfirm ?? (() => {
                })}
            />
        </div>
    );
}
