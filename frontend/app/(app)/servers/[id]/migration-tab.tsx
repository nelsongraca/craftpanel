"use client";

import {useCallback, useEffect, useRef, useState} from "react";
import {ArrowRight, ChevronDown, ChevronRight, Loader2, Shuffle, X} from "lucide-react";
import {listMigrations, listNodes, startMigration} from "@/lib/generated/sdk.gen";
import type {MigrationResponse, MigrationStepData} from "@/lib/types";
import type {Node} from "@/lib/types";

function fmtDate(iso: string): string {
    return new Date(iso).toLocaleString();
}

const MIGRATION_STATUS_CLASSES: Record<string, string> = {
    PENDING: "text-text-dim border border-border bg-surface-high",
    SYNCING: "text-warning border border-warning/30 bg-warning/10",
    CUTTING_OVER: "text-accent border border-accent/30 bg-accent/10",
    COMPLETED: "text-healthy border border-healthy/30 bg-healthy/10",
    FAILED: "text-error border border-error/30 bg-error/10",
    CANCELLED: "text-text-muted border border-border bg-surface-high",
};

const STEP_STATUS_CLASSES: Record<string, string> = {
    PENDING: "text-text-muted",
    RUNNING: "text-warning",
    SUCCESS: "text-healthy",
    FAILED: "text-error",
};

function StepRow({step}: { step: MigrationStepData }) {
    return (
        <div className="flex items-start gap-3 py-1.5">
            <span className="font-mono text-xs text-text-muted w-5 flex-shrink-0 pt-0.5">{step.step_number}</span>
            <span
                className={`font-mono text-xs uppercase w-16 flex-shrink-0 pt-0.5 ${STEP_STATUS_CLASSES[step.status] ?? "text-text-muted"}`}>{step.status}</span>
            <span className="text-xs text-text-primary flex-1">{step.description}</span>
            {step.error_message && (
                <span className="text-xs text-error ml-2">{step.error_message}</span>
            )}
        </div>
    );
}

function MigrationCard({migration, defaultOpen}: { migration: MigrationResponse; defaultOpen?: boolean }) {
    const [open, setOpen] = useState(defaultOpen ?? false);
    return (
        <div className="border border-border rounded-md bg-surface overflow-hidden">
            <button
                className="w-full flex items-center gap-3 px-4 py-3 hover:bg-surface-high transition-colors text-left"
                onClick={() => setOpen(o => !o)}
            >
                {open ? <ChevronDown size={14} className="text-text-muted flex-shrink-0"/> :
                    <ChevronRight size={14} className="text-text-muted flex-shrink-0"/>}
                <span className="font-mono text-xs text-text-muted flex-shrink-0">{migration.id.slice(0, 8)}</span>
                <span className={`font-mono text-xs uppercase px-1.5 py-0.5 rounded flex-shrink-0 ${MIGRATION_STATUS_CLASSES[migration.status] ?? "text-text-muted border border-border"}`}>
                    {migration.status}
                </span>
                <span className="text-xs text-text-dim flex-1 truncate">
                    {migration.source_node_id.slice(0, 8)} <ArrowRight size={10}
                                                                       className="inline"/> {migration.target_node_id.slice(0, 8)}
                </span>
                <span className="text-xs text-text-muted flex-shrink-0">{fmtDate(migration.created_at)}</span>
            </button>
            {open && (
                <div className="border-t border-border px-4 py-3">
                    {migration.steps.length === 0 ? (
                        <p className="text-xs text-text-muted">No steps recorded.</p>
                    ) : (
                        migration.steps.map(s => <StepRow key={s.step_number} step={s}/>)
                    )}
                    {migration.completed_at && (
                        <p className="text-xs text-text-muted mt-2">Completed: {fmtDate(migration.completed_at)}</p>
                    )}
                </div>
            )}
        </div>
    );
}

// ── Live migration progress via WebSocket ─────────────────────────────────────

interface LiveStep {
    step: number;
    description: string;
    status: "RUNNING" | "SUCCESS" | "FAILED";
    error?: string;
    rsyncPercent?: number;
}

function ActiveMigration({migrationId, onDone}: { migrationId: string; onDone: () => void }) {
    const [steps, setSteps] = useState<LiveStep[]>([]);
    const [migrationStatus, setMigrationStatus] = useState<string>("PENDING");
    const [error, setError] = useState<string | null>(null);
    const [connected, setConnected] = useState(false);
    const wsRef = useRef<WebSocket | null>(null);
    const doneRef = useRef(false);

    useEffect(() => {
        const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
        const url = `${proto}//${window.location.host}/api/migrations/${migrationId}/events`;
        const ws = new WebSocket(url);
        wsRef.current = ws;

        ws.onopen = () => setConnected(true);

        ws.onmessage = (e) => {
            let msg: Record<string, unknown>;
            try {
                msg = JSON.parse(e.data as string);
            } catch {
                return;
            }
            const type = msg["type"] as string;

            if (type === "status") {
                setMigrationStatus(msg["status"] as string);
            } else if (type === "step.started") {
                const stepNum = msg["step"] as number;
                const desc = msg["description"] as string;
                setSteps(prev => {
                    const existing = prev.find(s => s.step === stepNum);
                    if (existing) return prev.map(s => s.step === stepNum ? {...s, status: "RUNNING"} : s);
                    return [...prev, {step: stepNum, description: desc, status: "RUNNING"}];
                });
            } else if (type === "rsync.progress") {
                const stepNum = msg["step"] as number | undefined;
                const pct = msg["percent"] as number;
                if (stepNum !== undefined) {
                    setSteps(prev => prev.map(s => s.step === stepNum ? {...s, rsyncPercent: pct} : s));
                }
            } else if (type === "failed") {
                setError(msg["error"] as string ?? "Migration failed");
                setMigrationStatus("FAILED");
                if (!doneRef.current) {
                    doneRef.current = true;
                    setTimeout(onDone, 3000);
                }
            } else if (type === "completed") {
                setMigrationStatus("COMPLETED");
                if (!doneRef.current) {
                    doneRef.current = true;
                    ws.close();
                    setTimeout(onDone, 2000);
                }
            }
        };

        ws.onclose = () => setConnected(false);
        ws.onerror = () => setError("WebSocket connection error");

        return () => {
            ws.close();
        };
    }, [migrationId, onDone]);

    const statusColor = {
        PENDING: "text-text-dim",
        SYNCING: "text-warning",
        CUTTING_OVER: "text-accent",
        COMPLETED: "text-healthy",
        FAILED: "text-error",
    }[migrationStatus] ?? "text-text-dim";

    return (
        <div className="border border-border rounded-md bg-surface p-4 space-y-4">
            <div className="flex items-center gap-3">
                {connected && migrationStatus !== "COMPLETED" && migrationStatus !== "FAILED" ? (
                    <Loader2 size={14} className="text-warning animate-spin flex-shrink-0"/>
                ) : null}
                <span className={`font-mono text-xs uppercase font-bold ${statusColor}`}>{migrationStatus}</span>
                {!connected && migrationStatus !== "COMPLETED" && migrationStatus !== "FAILED" && (
                    <span className="text-xs text-text-muted">Connecting…</span>
                )}
            </div>

            {error && (
                <p className="text-xs text-error">{error}</p>
            )}

            <div className="space-y-1">
                {steps.map(s => (
                    <div key={s.step} className="space-y-1">
                        <div className="flex items-start gap-3">
                            <span className="font-mono text-xs text-text-muted w-5 flex-shrink-0 pt-0.5">{s.step}</span>
                            <span className={`font-mono text-xs uppercase w-16 flex-shrink-0 pt-0.5 ${STEP_STATUS_CLASSES[s.status] ?? "text-text-muted"}`}>
                                {s.status === "RUNNING" ? (
                                    <span className="flex items-center gap-1">
                                        <span className="w-2 h-2 border border-current border-t-transparent rounded-full animate-spin"/>
                                        run
                                    </span>
                                ) : s.status}
                            </span>
                            <span className="text-xs text-text-primary flex-1">{s.description}</span>
                            {s.error && <span className="text-xs text-error">{s.error}</span>}
                        </div>
                        {s.rsyncPercent !== undefined && s.status === "RUNNING" && (
                            <div className="ml-24 h-1 bg-surface-high rounded-full overflow-hidden">
                                <div
                                    className="h-full bg-accent transition-all duration-300"
                                    style={{width: `${s.rsyncPercent}%`}}
                                />
                            </div>
                        )}
                    </div>
                ))}
                {steps.length === 0 && (
                    <p className="text-xs text-text-muted">Waiting for first step…</p>
                )}
            </div>
        </div>
    );
}

// ── Migrate modal ─────────────────────────────────────────────────────────────

function MigrateModal({
                          serverId,
                          currentNodeId,
                          onClose,
                          onStarted,
                      }: {
    serverId: string;
    currentNodeId: string;
    onClose: () => void;
    onStarted: (migrationId: string) => void;
}) {
    const [nodes, setNodes] = useState<Node[]>([]);
    const [loadingNodes, setLoadingNodes] = useState(true);
    const [targetNodeId, setTargetNodeId] = useState("");
    const [rsyncImage, setRsyncImage] = useState("alpine");
    const [warningMessage, setWarningMessage] = useState("Server is restarting in 60 seconds");
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        listNodes().then(res => {
            if (res.data) {
                const eligible = res.data.filter((n: Node) => n.id !== currentNodeId && n.status === "ACTIVE");
                setNodes(eligible);
                if (eligible.length > 0) setTargetNodeId(eligible[0].id);
            }
            setLoadingNodes(false);
        });
    }, [currentNodeId]);

    async function submit() {
        if (!targetNodeId) return;
        setSubmitting(true);
        setError(null);
        const {data, error: err} = await startMigration({
            path: {id: serverId},
            body: {
                target_node_id: targetNodeId,
                rsync_image: rsyncImage,
                player_warning_message: warningMessage,
            },
        });
        setSubmitting(false);
        if (err) {
            setError((err as { message?: string }).message ?? "Failed to start migration");
            return;
        }
        if (data) onStarted(data.id);
    }

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
            <div className="bg-surface border border-border rounded-md w-full max-w-md shadow-2xl">
                <div className="flex items-center justify-between px-5 py-4 border-b border-border">
                    <h2 className="text-sm font-heading font-bold uppercase tracking-wider text-text-primary flex items-center gap-2">
                        <Shuffle size={14} className="text-accent"/>
                        Migrate Server
                    </h2>
                    <button onClick={onClose} className="text-text-muted hover:text-text-primary transition-colors">
                        <X size={16}/>
                    </button>
                </div>
                <div className="p-5 space-y-4">
                    {loadingNodes ? (
                        <p className="text-xs text-text-muted">Loading nodes…</p>
                    ) : nodes.length === 0 ? (
                        <p className="text-xs text-text-muted">No eligible target nodes available (requires ACTIVE node different from current).</p>
                    ) : (
                        <>
                            <div>
                                <label
                                    className="block text-xs font-heading font-bold uppercase tracking-wider text-text-dim mb-1.5">
                                    Target Node
                                </label>
                                <select
                                    value={targetNodeId}
                                    onChange={e => setTargetNodeId(e.target.value)}
                                    className="w-full bg-surface-high border border-border rounded px-3 py-2 text-xs text-text-primary focus:outline-none focus:border-accent"
                                >
                                    {nodes.map(n => (
                                        <option key={n.id} value={n.id}>{n.display_name} ({n.public_ip})</option>
                                    ))}
                                </select>
                            </div>

                            <div>
                                <label
                                    className="block text-xs font-heading font-bold uppercase tracking-wider text-text-dim mb-1.5">
                                    Rsync Image
                                </label>
                                <input
                                    type="text"
                                    value={rsyncImage}
                                    onChange={e => setRsyncImage(e.target.value)}
                                    className="w-full bg-surface-high border border-border rounded px-3 py-2 text-xs text-text-primary font-mono focus:outline-none focus:border-accent"
                                    placeholder="alpine"
                                />
                            </div>

                            <div>
                                <label
                                    className="block text-xs font-heading font-bold uppercase tracking-wider text-text-dim mb-1.5">
                                    Player Warning Message
                                </label>
                                <input
                                    type="text"
                                    value={warningMessage}
                                    onChange={e => setWarningMessage(e.target.value)}
                                    className="w-full bg-surface-high border border-border rounded px-3 py-2 text-xs text-text-primary focus:outline-none focus:border-accent"
                                    placeholder="Server is restarting…"
                                />
                            </div>

                            {error && (
                                <p className="text-xs text-error">{error}</p>
                            )}

                            <div className="flex gap-2 justify-end pt-2">
                                <button
                                    onClick={onClose}
                                    className="px-4 py-2 text-xs font-heading font-bold uppercase tracking-wider text-text-muted hover:text-text-primary border border-border rounded hover:bg-surface-high transition-colors"
                                >
                                    Cancel
                                </button>
                                <button
                                    onClick={submit}
                                    disabled={submitting || !targetNodeId}
                                    className="px-4 py-2 text-xs font-heading font-bold uppercase tracking-wider bg-accent text-bg rounded hover:bg-accent-bright transition-colors disabled:opacity-50 flex items-center gap-2"
                                >
                                    {submitting && <span className="w-3 h-3 border border-current border-t-transparent rounded-full animate-spin"/>}
                                    {submitting ? "Starting…" : "Start Migration"}
                                </button>
                            </div>
                        </>
                    )}
                </div>
            </div>
        </div>
    );
}

// ── Main tab ──────────────────────────────────────────────────────────────────

export function MigrationTab({
                                 serverId,
                                 nodeId,
                                 canMigrate,
                             }: {
    serverId: string;
    nodeId: string;
    canMigrate: boolean;
}) {
    const [migrations, setMigrations] = useState<MigrationResponse[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [showModal, setShowModal] = useState(false);
    const [activeMigrationId, setActiveMigrationId] = useState<string | null>(null);

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        const {data, error: err} = await listMigrations({path: {id: serverId}});
        if (data) setMigrations(data.migrations ?? []);
        else setError((err as { message?: string } | undefined)?.message ?? "Failed to load migrations");
        setLoading(false);
    }, [serverId]);

    useEffect(() => {
        void load();
    }, [load]);

    function handleStarted(migrationId: string) {
        setShowModal(false);
        setActiveMigrationId(migrationId);
    }

    function handleDone() {
        setActiveMigrationId(null);
        void load();
    }

    return (
        <div className="space-y-4">
            <div className="flex items-center justify-between">
                <h3 className="text-xs font-heading font-bold uppercase tracking-wider text-text-dim">
                    Migration History
                </h3>
                {canMigrate && !activeMigrationId && (
                    <button
                        onClick={() => setShowModal(true)}
                        className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-heading font-bold uppercase tracking-wider bg-accent text-bg rounded hover:bg-accent-bright transition-colors"
                    >
                        <Shuffle size={11}/>
                        Migrate
                    </button>
                )}
            </div>

            {activeMigrationId && (
                <div>
                    <p className="text-xs font-heading font-bold uppercase tracking-wider text-text-dim mb-2">
                        Active Migration
                    </p>
                    <ActiveMigration migrationId={activeMigrationId} onDone={handleDone}/>
                </div>
            )}

            {loading ? (
                <p className="text-xs text-text-muted">Loading…</p>
            ) : error ? (
                <p className="text-xs text-error">{error}</p>
            ) : migrations.length === 0 ? (
                <p className="text-xs text-text-muted">No migrations yet.</p>
            ) : (
                <div className="space-y-2">
                    {migrations.map((m, i) => (
                        <MigrationCard key={m.id} migration={m} defaultOpen={i === 0}/>
                    ))}
                </div>
            )}

            {showModal && (
                <MigrateModal
                    serverId={serverId}
                    currentNodeId={nodeId}
                    onClose={() => setShowModal(false)}
                    onStarted={handleStarted}
                />
            )}
        </div>
    );
}
