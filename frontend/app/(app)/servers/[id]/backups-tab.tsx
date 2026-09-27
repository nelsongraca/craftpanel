"use client";

import {useCallback, useEffect, useState} from "react";
import {Clock, Download, Play, RefreshCw, Trash2} from "lucide-react";
import {
    deleteBackup,
    downloadBackup,
    getBackupSchedule,
    listBackups,
    triggerBackup,
    updateBackupSchedule,
} from "@/lib/generated/sdk.gen";
import type {BackupResponse as Backup, BackupScheduleResponse as Schedule} from "@/lib/generated/types.gen";
import {Empty, EmptyDescription} from "@/components/ui/empty";
import {fmtBytes} from "@/lib/utils/format";
import {useWs} from "@/lib/ws-context";

function fmtDate(iso: string): string {
    return new Date(iso).toLocaleString();
}

const STATUS_CLASSES: Record<string, string> = {
    IN_PROGRESS: "text-warning border border-warning/30 bg-warning/10",
    COMPLETED: "text-healthy border border-healthy/30 bg-healthy/10",
    FAILED: "text-error border border-error/30 bg-error/10",
};

export function BackupsTab({serverId}: {serverId: string}) {
    const [backups, setBackups] = useState<Backup[]>([]);
    const [schedule, setSchedule] = useState<Schedule | null>(null);
    const [loading, setLoading] = useState(true);
    const [triggering, setTriggering] = useState(false);
    const [deleting, setDeleting] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [progress, setProgress] = useState<Record<string, number>>({});

    const {subscribe} = useWs();

    // Schedule edit state
    const [editingSchedule, setEditingSchedule] = useState(false);
    const [scheduleInput, setScheduleInput] = useState("");
    const [maxCountInput, setMaxCountInput] = useState("10");
    const [scheduleError, setScheduleError] = useState<string | null>(null);
    const [savingSchedule, setSavingSchedule] = useState(false);

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        const [bRes, sRes] = await Promise.all([
            listBackups({path: {id: serverId}}),
            getBackupSchedule({path: {id: serverId}}),
        ]);
        if (bRes.data) setBackups(bRes.data.backups ?? []);
        else setError("Failed to load backups");
        if (sRes.data) {
            setSchedule(sRes.data);
            setScheduleInput(sRes.data.backup_schedule ?? "");
            setMaxCountInput(String(sRes.data.backup_max_count));
        }
        setLoading(false);
    }, [serverId]);

    useEffect(() => {
        load();
    }, [load]);

    useEffect(() => {
        const unsubProgress = subscribe("server.backup.progress", (payload) => {
            if (payload.server_id !== serverId) return;
            setProgress((prev) => ({
                ...prev,
                [payload.backup_id]: payload.percent_complete,
            }));
        });
        const unsubComplete = subscribe("server.backup.complete", (payload) => {
            if (payload.server_id !== serverId) return;
            setProgress((prev) => {
                const next = {...prev};
                delete next[payload.backup_id];
                return next;
            });
            void load();
        });
        return () => {
            unsubProgress();
            unsubComplete();
        };
    }, [subscribe, serverId, load]);

    async function handleTrigger() {
        setTriggering(true);
        setError(null);
        const res = await triggerBackup({path: {id: serverId}});
        if (res.error) setError((res.error as {message?: string})?.message ?? "Failed to trigger backup");
        else await load();
        setTriggering(false);
    }

    async function handleDownload(backupId: string) {
        setError(null);
        const res = await downloadBackup({path: {id: serverId, backupId}});
        if (res.error) {
            setError((res.error as {message?: string})?.message ?? "Failed to download backup");
            return;
        }
        const blob = res.data as Blob;
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = `${backupId}.tar.gz`;
        a.click();
        URL.revokeObjectURL(url);
    }

    async function handleDelete(backupId: string) {
        setDeleting(backupId);
        setError(null);
        const res = await deleteBackup({path: {id: serverId, backupId}});
        if (res.error) setError((res.error as {message?: string})?.message ?? "Failed to delete backup");
        else setBackups((prev) => prev.filter((b) => b.id !== backupId));
        setDeleting(null);
    }

    async function handleSaveSchedule() {
        setSavingSchedule(true);
        setScheduleError(null);
        const res = await updateBackupSchedule({
            path: {id: serverId},
            body: {
                backup_schedule: scheduleInput || null,
                backup_max_count: parseInt(maxCountInput, 10) || 10,
            },
        });
        if (res.error) {
            setScheduleError((res.error as {message?: string})?.message ?? "Failed to save schedule");
        } else {
            setEditingSchedule(false);
            await load();
        }
        setSavingSchedule(false);
    }

    if (loading) {
        return <div className="p-4 text-sm text-text-dim">Loading backups…</div>;
    }

    return (
        <div className="space-y-6 px-4 py-6">
            {error && (
                <div className="rounded border border-error/30 bg-error/10 px-3 py-2 text-sm text-error">{error}</div>
            )}

            {/* Backup schedule */}
            <div className="rounded-lg border border-border bg-surface p-4">
                <div className="mb-3 flex items-center justify-between">
                    <div className="flex items-center gap-2">
                        <Clock className="h-4 w-4 text-text-dim" />
                        <span className="text-sm font-medium text-text-primary">Backup Schedule</span>
                    </div>
                    {!editingSchedule && (
                        <button
                            onClick={() => setEditingSchedule(true)}
                            className="text-xs text-accent transition-colors hover:text-accent-bright"
                        >
                            Edit
                        </button>
                    )}
                </div>

                {!editingSchedule ? (
                    <div className="space-y-1 text-sm text-text-dim">
                        <div>
                            <span className="text-text-muted">Cron: </span>
                            {schedule?.backup_schedule ? (
                                <span className="font-mono text-text-primary">{schedule.backup_schedule}</span>
                            ) : (
                                <span className="italic">Disabled</span>
                            )}
                        </div>
                        <div>
                            <span className="text-text-muted">Max stored: </span>
                            <span className="text-text-primary">{schedule?.backup_max_count ?? 10}</span>
                        </div>
                    </div>
                ) : (
                    <div className="space-y-3">
                        <div>
                            <label className="mb-1 block text-xs text-text-muted">
                                Cron expression (leave empty to disable)
                            </label>
                            <input
                                value={scheduleInput}
                                onChange={(e) => setScheduleInput(e.target.value)}
                                placeholder="0 2 * * *"
                                className="w-full rounded border border-border bg-bg px-3 py-1.5 font-mono text-sm text-text-primary focus:border-accent focus:outline-none"
                            />
                        </div>
                        <div>
                            <label className="mb-1 block text-xs text-text-muted">Max backups to keep</label>
                            <input
                                type="number"
                                min={1}
                                value={maxCountInput}
                                onChange={(e) => setMaxCountInput(e.target.value)}
                                className="w-24 rounded border border-border bg-bg px-3 py-1.5 text-sm text-text-primary focus:border-accent focus:outline-none"
                            />
                        </div>
                        {scheduleError && <div className="text-xs text-error">{scheduleError}</div>}
                        <div className="flex gap-2">
                            <button
                                onClick={handleSaveSchedule}
                                disabled={savingSchedule}
                                className="rounded bg-accent px-3 py-1.5 text-xs text-bg transition-colors hover:bg-accent-bright disabled:opacity-50"
                            >
                                {savingSchedule ? "Saving…" : "Save"}
                            </button>
                            <button
                                onClick={() => {
                                    setEditingSchedule(false);
                                    setScheduleError(null);
                                }}
                                className="rounded border border-border px-3 py-1.5 text-xs text-text-dim transition-colors hover:text-text-primary"
                            >
                                Cancel
                            </button>
                        </div>
                    </div>
                )}
            </div>

            {/* Manual trigger */}
            <div className="flex items-center justify-between">
                <span className="text-sm text-text-dim">
                    {backups.length} backup{backups.length !== 1 ? "s" : ""}
                </span>
                <div className="flex gap-2">
                    <button
                        onClick={load}
                        className="flex items-center gap-1.5 rounded border border-border px-3 py-1.5 text-xs text-text-dim transition-colors hover:text-text-primary"
                    >
                        <RefreshCw className="h-3 w-3" />
                        Refresh
                    </button>
                    <button
                        onClick={handleTrigger}
                        disabled={triggering}
                        className="flex items-center gap-1.5 rounded bg-accent px-3 py-1.5 text-xs text-bg transition-colors hover:bg-accent-bright disabled:opacity-50"
                    >
                        <Play className="h-3 w-3" />
                        {triggering ? "Triggering…" : "Trigger Backup"}
                    </button>
                </div>
            </div>

            {/* Backup list */}
            {backups.length === 0 ? (
                <Empty>
                    <EmptyDescription>No backups yet</EmptyDescription>
                </Empty>
            ) : (
                <div className="space-y-2">
                    {backups.map((backup) => (
                        <div
                            key={backup.id}
                            className="flex items-center justify-between rounded-lg border border-border bg-surface px-4 py-3"
                        >
                            <div className="flex min-w-0 items-center gap-3">
                                <span
                                    className={`shrink-0 rounded px-2 py-0.5 text-xs font-medium ${STATUS_CLASSES[backup.status] ?? "text-text-dim"}`}
                                >
                                    {backup.status}
                                </span>
                                <div className="min-w-0">
                                    <div className="truncate text-xs text-text-dim">
                                        {fmtDate(backup.created_at)}
                                        {backup.trigger === "SCHEDULED" && (
                                            <span className="ml-2 text-text-muted">(scheduled)</span>
                                        )}
                                    </div>
                                    {backup.size_bytes != null && (
                                        <div className="text-xs text-text-muted">{fmtBytes(backup.size_bytes)}</div>
                                    )}
                                    {backup.error_message && (
                                        <div className="truncate text-xs text-error">{backup.error_message}</div>
                                    )}
                                    {backup.status === "IN_PROGRESS" && (
                                        <div className="mt-1.5 w-full">
                                            <div className="mb-0.5 flex items-center justify-between text-xs text-text-muted">
                                                <span>Backing up…</span>
                                                {progress[backup.id!] != null && <span>{progress[backup.id!]}%</span>}
                                            </div>
                                            <div className="h-1 overflow-hidden rounded bg-surface-high">
                                                <div
                                                    className="h-full bg-accent transition-all duration-300"
                                                    style={{width: `${progress[backup.id!] ?? 0}%`}}
                                                />
                                            </div>
                                        </div>
                                    )}
                                </div>
                            </div>

                            <div className="ml-3 flex shrink-0 items-center gap-2">
                                {backup.status === "COMPLETED" && (
                                    <button
                                        onClick={() => handleDownload(backup.id!)}
                                        className="flex items-center gap-1 rounded border border-border px-2 py-1 text-xs text-text-dim transition-colors hover:text-text-primary"
                                    >
                                        <Download className="h-3 w-3" />
                                        Download
                                    </button>
                                )}
                                {backup.status !== "IN_PROGRESS" && (
                                    <button
                                        onClick={() => handleDelete(backup.id!)}
                                        disabled={deleting === backup.id}
                                        className="rounded p-1.5 text-text-muted transition-colors hover:text-error disabled:opacity-50"
                                        title="Delete backup"
                                    >
                                        <Trash2 className="h-3.5 w-3.5" />
                                    </button>
                                )}
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
