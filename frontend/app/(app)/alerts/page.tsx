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
import {Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter} from "@/components/ui/dialog";
import {SelectField} from "@/components/ui/form-elements";
import {SmartList, type SmartListColumn} from "@/components/ui/smart-list";

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

// ── Columns ───────────────────────────────────────────────────────────────────

const THRESHOLD_COLUMNS: SmartListColumn<AlertThreshold>[] = [
    {key: 'scope_type', header: 'Scope', title: true, render: (t) => (
        <span className={`inline-block text-xs font-heading font-bold uppercase tracking-wider px-1.5 py-0.5 rounded border ${
            t.scope_type === "NODE"
                ? "text-text-dim border-border bg-surface-high"
                : "text-accent border-accent/30 bg-accent/5"
        }`}>
            {t.scope_type}
        </span>
    )},
    {key: 'scope_id', header: 'Scope ID', render: (t) => <span className="text-text-muted">{t.scope_id.slice(0, 8)}…</span>},
    {key: 'metric', header: 'Metric', render: (t) => <span className="text-text-primary">{t.metric}</span>},
    {key: 'trigger', header: 'Trigger', render: (t) => (
        t.threshold_value != null
            ? <span className="text-warning">&gt; {t.threshold_value}</span>
            : <span className="text-text-dim">= {t.threshold_state}</span>
    )},
    {key: 'created', header: 'Created', render: (t) => <span className="text-text-muted">{timeAgo(t.created_at)}</span>},
]

const EVENT_COLUMNS: SmartListColumn<AlertEvent>[] = [
    {key: 'state', header: 'State', hiddenOnMobile: true, render: (e) => (
        e.resolved_at
            ? <CheckCircle size={14} strokeWidth={2} className="text-healthy"/>
            : <AlertTriangle size={14} strokeWidth={2} className="text-error"/>
    )},
    {key: 'message', header: 'Message', title: true, render: (e) => <span className="text-text-primary max-w-xs truncate">{e.message}</span>},
    {key: 'threshold', header: 'Threshold', render: (e) => <span className="text-text-muted">{e.threshold_id.slice(0, 8)}…</span>},
    {key: 'fired', header: 'Fired', render: (e) => <span className="text-text-muted">{timeAgo(e.fired_at)}</span>},
    {key: 'resolved', header: 'Resolved', render: (e) => (
        e.resolved_at
            ? <span className="text-text-muted">{timeAgo(e.resolved_at)}</span>
            : <span className="text-error text-xs font-heading font-bold uppercase tracking-wider">Active</span>
    )},
]

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

// ── Create modal ──────────────────────────────────────────────────────────────

function CreateThresholdModal({
                                  onClose,
                                  onCreate,
                              }: {
    onClose: () => void;
    onCreate: (threshold: AlertThreshold) => void;
}) {
    const [open, setOpen] = useState(true);
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

    function handleClose() {
        setOpen(false);
        onClose();
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
        handleClose();
    }

    return (
        <Dialog open={open} onOpenChange={setOpen}>
            <DialogContent className="w-full max-w-md">
                <DialogHeader>
                    <DialogTitle>New Alert Threshold</DialogTitle>
                </DialogHeader>

                {error && <p className="text-xs text-error">{error}</p>}

                <div className="space-y-4">
                    <div className="space-y-1">
                        <label className="text-xs font-heading font-bold uppercase tracking-wider text-text-muted">
                            Scope Type
                        </label>
                        <SelectField
                            surface="bg"
                            fieldSize="sm"
                            className="w-full"
                            value={scopeType}
                            onChange={(e) => handleScopeTypeChange(e.target.value as "NODE" | "SERVER")}
                        >
                            <option value="NODE">Node</option>
                            <option value="SERVER">Server</option>
                        </SelectField>
                    </div>

                    <div className="space-y-1">
                        <label className="text-xs font-heading font-bold uppercase tracking-wider text-text-muted">
                            {scopeType === "NODE" ? "Node" : "Server"}
                        </label>
                        <SelectField
                            surface="bg"
                            fieldSize="sm"
                            className="w-full"
                            value={scopeId}
                            onChange={(e) => setScopeId(e.target.value)}
                        >
                            <option value="">- select -</option>
                            {(scopeType === "NODE" ? nodes : servers).map((item) => (
                                <option key={item.id} value={item.id}>{item.display_name}</option>
                            ))}
                        </SelectField>
                    </div>

                    <div className="space-y-1">
                        <label className="text-xs font-heading font-bold uppercase tracking-wider text-text-muted">
                            Metric
                        </label>
                        <SelectField
                            surface="bg"
                            fieldSize="sm"
                            className="w-full"
                            value={metric}
                            onChange={(e) => setMetric(e.target.value)}
                        >
                            {METRICS.map((m) => <option key={m} value={m}>{m}</option>)}
                        </SelectField>
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

                <DialogFooter>
                    <button
                        onClick={handleClose}
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
                </DialogFooter>
            </DialogContent>
        </Dialog>
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
            <SmartList
                items={thresholds}
                columns={THRESHOLD_COLUMNS}
                keyFor={(t) => t.id}
                loading={loading}
                empty="No thresholds configured."
                header={
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
                }
                actions={(t) => canManage ? (
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
                ) : null}
            />

            {/* ── Events ── */}
            <SmartList
                items={displayedEvents}
                columns={EVENT_COLUMNS}
                keyFor={(e) => e.id}
                loading={loading}
                empty={activeOnly ? "No active alerts." : "No alert events."}
                header={
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
                }
            />

            {showCreate && (
                <CreateThresholdModal
                    onClose={() => setShowCreate(false)}
                    onCreate={(t) => setThresholds((prev) => [t, ...prev])}
                />
            )}
        </div>
    );
}
