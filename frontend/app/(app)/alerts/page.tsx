"use client";

import {useEffect, useState} from "react";
import {AlertTriangle, CheckCircle, Plus, Trash2, X} from "lucide-react";
import {createAlertThreshold, deleteAlertThreshold, listAlertEvents, listAlertThresholds, listNodes, listServers,} from "@/lib/generated/sdk.gen";
import type {CreateAlertThresholdRequest as CreateRequest} from "@/lib/generated/types.gen";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import type {AlertEvent, AlertThreshold} from "@/lib/types";
import {useResourceList} from "@/lib/hooks/useResourceList";
import {useWs} from "@/lib/ws-context";
import {timeAgo} from "@/lib/utils/format";

async function loadThresholds() {
    const {data} = await listAlertThresholds();
    return {data: data?.thresholds};
}


// ── Helpers ───────────────────────────────────────────────────────────────────

const METRICS = [
    "cpu_percent",
    "ram_percent",
    "net_in_bytes",
    "net_out_bytes",
    "disk_used_percent",
];

// ── Sub-components ────────────────────────────────────────────────────────────

function SectionHeader({title, action}: { title: string; action?: React.ReactNode }) {
    return (
        <div className="flex items-center justify-between mb-4">
            <h2 className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">
                {title}
            </h2>
            {action}
        </div>
    );
}

function EmptyRow({message}: { message: string }) {
    return (
        <tr>
            <td colSpan={99} className="py-8 text-center text-xs text-text-muted">
                {message}
            </td>
        </tr>
    );
}

function Th({children}: { children?: React.ReactNode }) {
    return (
        <th className="text-left px-4 py-3 text-xs font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border">
            {children}
        </th>
    );
}

function Td({children, className = ""}: { children: React.ReactNode; className?: string }) {
    return (
        <td className={`px-4 py-3 text-xs border-b border-border/50 ${className}`}>
            {children}
        </td>
    );
}

// ── Create modal ──────────────────────────────────────────────────────────────

function CreateThresholdModal({
                                  onClose,
                                  onCreate,
                              }: {
    onClose: () => void;
    onCreate: (threshold: AlertThreshold) => void;
}) {
    const [scopeType, setScopeType] = useState<"NODE" | "SERVER">("NODE");
    const [scopeId, setScopeId] = useState("");
    const [metric, setMetric] = useState(METRICS[0]);
    const [valueType, setValueType] = useState<"numeric" | "state">("numeric");
    const [thresholdValue, setThresholdValue] = useState<string>("80");
    const [thresholdState, setThresholdState] = useState("UNHEALTHY");
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [nodes, setNodes] = useState<{ id: string; display_name: string }[]>([]);
    const [servers, setServers] = useState<{ id: string; display_name: string }[]>([]);

    useEffect(() => {
        listNodes().then(({data}) => {
            if (data) setNodes(data);
        });
        listServers().then(({data}) => {
            if (data) setServers(data);
        });
    }, []);

    function handleScopeTypeChange(t: "NODE" | "SERVER") {
        setScopeType(t);
        setScopeId("");
    }

    async function submit() {
        setSaving(true);
        setError(null);
        const body: CreateRequest = {
            scope_type: scopeType,
            scope_id: scopeId.trim(),
            metric,
            ...(valueType === "numeric"
                ? {threshold_value: parseFloat(thresholdValue)}
                : {threshold_state: thresholdState}),
        };
        const {error, data} = await createAlertThreshold({body});
        setSaving(false);
        if (error || !data) {
            setError(error?.message ?? "Failed to create threshold");
            return;
        }
        onCreate(data);
        onClose();
    }

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-bg/80 backdrop-blur-sm">
            <div className="bg-surface-higher border border-border rounded-lg shadow-2xl w-full max-w-md p-6">
                <div className="flex items-center justify-between mb-5">
                    <h2 className="text-sm font-heading font-bold uppercase tracking-widest text-text-primary">
                        New Alert Threshold
                    </h2>
                    <button onClick={onClose} className="text-text-muted hover:text-text-primary transition-colors">
                        <X size={14}/>
                    </button>
                </div>

                {error && <p className="text-xs text-error mb-4">{error}</p>}

                <div className="space-y-4">
                    <div className="space-y-1">
                        <label className="text-xs font-heading font-bold uppercase tracking-wider text-text-muted">
                            Scope Type
                        </label>
                        <select
                            value={scopeType}
                            onChange={(e) => handleScopeTypeChange(e.target.value as "NODE" | "SERVER")}
                            className="w-full bg-bg border border-border rounded px-2.5 py-1.5 text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
                        >
                            <option value="NODE">Node</option>
                            <option value="SERVER">Server</option>
                        </select>
                    </div>

                    <div className="space-y-1">
                        <label className="text-xs font-heading font-bold uppercase tracking-wider text-text-muted">
                            {scopeType === "NODE" ? "Node" : "Server"}
                        </label>
                        <select
                            value={scopeId}
                            onChange={(e) => setScopeId(e.target.value)}
                            className="w-full bg-bg border border-border rounded px-2.5 py-1.5 text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
                        >
                            <option value="">- select -</option>
                            {(scopeType === "NODE" ? nodes : servers).map((item) => (
                                <option key={item.id} value={item.id}>{item.display_name}</option>
                            ))}
                        </select>
                    </div>

                    <div className="space-y-1">
                        <label className="text-xs font-heading font-bold uppercase tracking-wider text-text-muted">
                            Metric
                        </label>
                        <select
                            value={metric}
                            onChange={(e) => setMetric(e.target.value)}
                            className="w-full bg-bg border border-border rounded px-2.5 py-1.5 text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
                        >
                            {METRICS.map((m) => <option key={m} value={m}>{m}</option>)}
                        </select>
                    </div>

                    <div className="space-y-1">
                        <label className="text-xs font-heading font-bold uppercase tracking-wider text-text-muted">
                            Trigger Type
                        </label>
                        <div className="flex gap-2">
                            {(["numeric", "state"] as const).map((t) => (
                                <button
                                    key={t}
                                    onClick={() => setValueType(t)}
                                    className={`flex-1 py-1.5 rounded border text-xs font-heading font-bold uppercase tracking-wider transition-colors ${
                                        valueType === t
                                            ? "border-accent text-accent bg-accent/10"
                                            : "border-border text-text-muted hover:text-text-primary"
                                    }`}
                                >
                                    {t}
                                </button>
                            ))}
                        </div>
                    </div>

                    {valueType === "numeric" ? (
                        <div className="space-y-1">
                            <label className="text-xs font-heading font-bold uppercase tracking-wider text-text-muted">
                                Threshold Value
                            </label>
                            <input
                                type="number"
                                value={thresholdValue}
                                onChange={(e) => setThresholdValue(e.target.value)}
                                className="w-full bg-bg border border-border rounded px-2.5 py-1.5 text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
                            />
                            <p className="text-xs text-text-muted">Alert fires when metric exceeds this value.</p>
                        </div>
                    ) : (
                        <div className="space-y-1">
                            <label className="text-xs font-heading font-bold uppercase tracking-wider text-text-muted">
                                Threshold State
                            </label>
                            <input
                                value={thresholdState}
                                onChange={(e) => setThresholdState(e.target.value)}
                                placeholder="UNHEALTHY"
                                className="w-full bg-bg border border-border rounded px-2.5 py-1.5 text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
                            />
                        </div>
                    )}
                </div>

                <div className="flex items-center justify-end gap-2 mt-6">
                    <button
                        onClick={onClose}
                        className="px-3 py-1 text-xs font-heading font-bold uppercase tracking-wider text-text-muted hover:text-text-primary transition-colors"
                    >
                        Cancel
                    </button>
                    <button
                        onClick={() => void submit()}
                        disabled={saving || !scopeId.trim()}
                        className="px-4 py-1.5 rounded bg-accent text-bg text-xs font-heading font-bold uppercase tracking-wider hover:bg-accent-bright transition-colors disabled:opacity-50"
                    >
                        {saving ? "Creating…" : "Create"}
                    </button>
                </div>
            </div>
        </div>
    );
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function AlertsPage() {
    const {user} = useAuth();
    const permissions = user?.permissions ?? [];
    const canManage = hasPermission(permissions, "system.settings");
    const {subscribe} = useWs();

    const {data: thresholds, initialLoad: loading, setData: setThresholds} = useResourceList(loadThresholds, [], {pollMs: 0});
    const [events, setEvents] = useState<AlertEvent[]>([]);
    const [activeOnly, setActiveOnly] = useState(false);
    const [showCreate, setShowCreate] = useState(false);
    const [deleteId, setDeleteId] = useState<string | null>(null);
    const [deleteError, setDeleteError] = useState<string | null>(null);

    useEffect(() => {
        void listAlertEvents().then(({data}) => {
            if (data?.events) setEvents(data.events);
        });
    }, []);

    // Live alert.fired / alert.resolved updates via WS
    useEffect(() => {
        const unsubFired = subscribe("alert.fired", (payload) => {
            const event: AlertEvent = {
                id: payload.event_id,
                threshold_id: payload.threshold_id,
                message: payload.message,
                fired_at: payload.fired_at!,
            };
            setEvents((prev) => [event, ...prev]);
        });
        const unsubResolved = subscribe("alert.resolved", (payload) => {
            setEvents((prev) =>
                prev.map((e) =>
                    e.id === payload.event_id
                        ? {...e, resolved_at: payload.resolved_at!}
                        : e
                )
            );
        });
        return () => {
            unsubFired();
            unsubResolved();
        };
    }, [subscribe]);

    async function confirmDelete(id: string) {
        setDeleteId(id);
        setDeleteError(null);
        const {error} = await deleteAlertThreshold({path: {id}});
        if (error) {
            setDeleteError(error.message ?? "Failed to delete threshold");
        } else {
            setThresholds((prev) => prev.filter((t) => t.id !== id));
            setEvents((prev) => prev.filter((e) => e.threshold_id !== id));
        }
        setDeleteId(null);
    }

    const displayedEvents = activeOnly ? events.filter((e) => !e.resolved_at) : events;

    return (
        <div className="px-6 py-6 space-y-8">
            {/* Header */}
            <div>
                <h1 className="text-[22px] font-heading font-bold uppercase tracking-wide text-text-primary leading-none mb-1">
                    Alerts
                </h1>
                <p className="text-xs text-text-muted">
                    Configure metric thresholds and view fired alert events.
                </p>
            </div>

            {deleteError && (
                <div className="flex items-center justify-between bg-error/10 border border-error/30 text-error rounded px-3 py-2 text-xs">
                    <span>{deleteError}</span>
                    <button onClick={() => setDeleteError(null)} className="ml-4 hover:opacity-70" aria-label="Dismiss">
                        <X size={13}/>
                    </button>
                </div>
            )}

            {/* ── Thresholds ── */}
            <div className="bg-surface border border-border rounded-md overflow-hidden">
                <div className="px-4 pt-4 pb-3 border-b border-border">
                    <SectionHeader
                        title="Thresholds"
                        action={
                            canManage && (
                                <button
                                    onClick={() => setShowCreate(true)}
                                    className="flex items-center gap-1.5 px-3 py-1.5 rounded border border-accent/50 text-accent text-xs font-heading font-bold uppercase tracking-wider hover:bg-accent/10 transition-colors"
                                >
                                    <Plus size={11} strokeWidth={2.5}/>
                                    New Threshold
                                </button>
                            )
                        }
                    />
                </div>
                <div className="overflow-x-auto hidden md:block">
                    <table className="w-full">
                        <thead>
                        <tr>
                            <Th>Scope</Th>
                            <Th>Scope ID</Th>
                            <Th>Metric</Th>
                            <Th>Trigger</Th>
                            <Th>Created</Th>
                            {canManage && <Th></Th>}
                        </tr>
                        </thead>
                        <tbody>
                        {loading ? (
                            <EmptyRow message="Loading…"/>
                        ) : thresholds.length === 0 ? (
                            <EmptyRow message="No thresholds configured."/>
                        ) : (
                            thresholds.map((t) => (
                                <tr key={t.id} className="border-b border-border/50 hover:bg-surface-high/40 transition-colors">
                                    <Td>
                      <span className={`inline-block text-xs font-heading font-bold uppercase tracking-wider px-1.5 py-0.5 rounded border ${
                          t.scope_type === "NODE"
                              ? "text-text-dim border-border bg-surface-high"
                              : "text-accent border-accent/30 bg-accent/5"
                      }`}>
                        {t.scope_type}
                      </span>
                                    </Td>
                                    <Td className="text-text-muted">{t.scope_id.slice(0, 8)}…</Td>
                                    <Td className="text-text-primary">{t.metric}</Td>
                                    <Td>
                                        {t.threshold_value != null ? (
                                            <span className="text-warning">&gt; {t.threshold_value}</span>
                                        ) : (
                                            <span className="text-text-dim">= {t.threshold_state}</span>
                                        )}
                                    </Td>
                                    <Td className="text-text-muted">{timeAgo(t.created_at)}</Td>
                                    {canManage && (
                                        <Td>
                                            <button
                                                onClick={() => void confirmDelete(t.id)}
                                                disabled={deleteId === t.id}
                                                className="text-text-muted hover:text-error transition-colors disabled:opacity-40"
                                                title="Delete threshold"
                                            >
                                                {deleteId === t.id ? (
                                                    <span className="w-3 h-3 border border-current border-t-transparent rounded-full animate-spin inline-block"/>
                                                ) : (
                                                    <Trash2 size={13} strokeWidth={2}/>
                                                )}
                                            </button>
                                        </Td>
                                    )}
                                </tr>
                            ))
                        )}
                        </tbody>
                    </table>
                </div>

                {/* Mobile card list (mobile) */}
                <div className="md:hidden divide-y divide-border">
                    {loading ? (
                        <p className="p-3 text-xs text-text-muted">Loading…</p>
                    ) : thresholds.length === 0 ? (
                        <p className="p-3 text-xs text-text-muted">No thresholds configured.</p>
                    ) : (
                        thresholds.map((t) => (
                            <div key={t.id} className="p-3 flex items-center justify-between gap-2">
                                <div className="min-w-0">
                                    <div className="flex items-center gap-2">
                                        <span className={`inline-block text-xs font-heading font-bold uppercase tracking-wider px-1.5 py-0.5 rounded border ${
                                            t.scope_type === "NODE"
                                                ? "text-text-dim border-border bg-surface-high"
                                                : "text-accent border-accent/30 bg-accent/5"
                                        }`}>
                                            {t.scope_type}
                                        </span>
                                        <span className="text-sm font-medium text-text-primary truncate">{t.metric}</span>
                                    </div>
                                    <p className="mt-1 font-mono text-xs text-text-muted truncate">
                                        {t.scope_id.slice(0, 8)}… · {t.threshold_value != null ? `> ${t.threshold_value}` : `= ${t.threshold_state}`}
                                    </p>
                                </div>
                                {canManage && (
                                    <button
                                        onClick={() => void confirmDelete(t.id)}
                                        disabled={deleteId === t.id}
                                        className="p-1.5 rounded hover:bg-surface-higher text-text-muted hover:text-error transition-colors disabled:opacity-40 shrink-0"
                                        title="Delete threshold"
                                    >
                                        {deleteId === t.id ? (
                                            <span className="w-3 h-3 border border-current border-t-transparent rounded-full animate-spin inline-block"/>
                                        ) : (
                                            <Trash2 size={13} strokeWidth={2}/>
                                        )}
                                    </button>
                                )}
                            </div>
                        ))
                    )}
                </div>
            </div>

            {/* ── Events ── */}
            <div className="bg-surface border border-border rounded-md overflow-hidden">
                <div className="px-4 pt-4 pb-3 border-b border-border">
                    <SectionHeader
                        title="Alert Events"
                        action={
                            <button
                                onClick={() => setActiveOnly((v) => !v)}
                                className={`px-3 py-1 rounded border text-xs font-heading font-bold uppercase tracking-wider transition-colors ${
                                    activeOnly
                                        ? "border-error/50 text-error bg-error/10"
                                        : "border-border text-text-muted hover:text-text-primary"
                                }`}
                            >
                                {activeOnly ? "Active Only" : "All Events"}
                            </button>
                        }
                    />
                </div>
                <div className="overflow-x-auto hidden md:block">
                    <table className="w-full">
                        <thead>
                        <tr>
                            <Th>State</Th>
                            <Th>Message</Th>
                            <Th>Threshold</Th>
                            <Th>Fired</Th>
                            <Th>Resolved</Th>
                        </tr>
                        </thead>
                        <tbody>
                        {loading ? (
                            <EmptyRow message="Loading…"/>
                        ) : displayedEvents.length === 0 ? (
                            <EmptyRow message={activeOnly ? "No active alerts." : "No alert events."}/>
                        ) : (
                            displayedEvents.map((e) => (
                                <tr key={e.id} className="border-b border-border/50 hover:bg-surface-high/40 transition-colors">
                                    <Td>
                                        {e.resolved_at ? (
                                            <CheckCircle size={14} strokeWidth={2} className="text-healthy"/>
                                        ) : (
                                            <AlertTriangle size={14} strokeWidth={2} className="text-error"/>
                                        )}
                                    </Td>
                                    <Td className="text-text-primary max-w-xs truncate">{e.message}</Td>
                                    <Td className="text-text-muted">{e.threshold_id.slice(0, 8)}…</Td>
                                    <Td className="text-text-muted">{timeAgo(e.fired_at)}</Td>
                                    <Td className="text-text-muted">
                                        {e.resolved_at ? timeAgo(e.resolved_at) : (
                                            <span className="text-error text-xs font-heading font-bold uppercase tracking-wider">Active</span>
                                        )}
                                    </Td>
                                </tr>
                            ))
                        )}
                        </tbody>
                    </table>
                </div>

                {/* Mobile card list (mobile) */}
                <div className="md:hidden divide-y divide-border">
                    {loading ? (
                        <p className="p-3 text-xs text-text-muted">Loading…</p>
                    ) : displayedEvents.length === 0 ? (
                        <p className="p-3 text-xs text-text-muted">{activeOnly ? "No active alerts." : "No alert events."}</p>
                    ) : (
                        displayedEvents.map((e) => (
                            <div key={e.id} className="p-3">
                                <div className="flex items-start gap-2">
                                    {e.resolved_at ? (
                                        <CheckCircle size={14} strokeWidth={2} className="text-healthy mt-0.5 shrink-0"/>
                                    ) : (
                                        <AlertTriangle size={14} strokeWidth={2} className="text-error mt-0.5 shrink-0"/>
                                    )}
                                    <div className="min-w-0">
                                        <p className="text-sm text-text-primary">{e.message}</p>
                                        <p className="mt-1 font-mono text-xs text-text-muted">
                                            {e.threshold_id.slice(0, 8)}… · fired {timeAgo(e.fired_at)}
                                            {e.resolved_at ? ` · resolved ${timeAgo(e.resolved_at)}` : " · active"}
                                        </p>
                                    </div>
                                </div>
                            </div>
                        ))
                    )}
                </div>
            </div>

            {showCreate && (
                <CreateThresholdModal
                    onClose={() => setShowCreate(false)}
                    onCreate={(t) => setThresholds((prev) => [t, ...prev])}
                />
            )}
        </div>
    );
}
