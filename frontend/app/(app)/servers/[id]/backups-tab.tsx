"use client";

import {useCallback, useEffect, useState} from "react";
import {Clock, Download, Play, RefreshCw, Trash2} from "lucide-react";
import {deleteBackup, getBackupSchedule, listBackups, triggerBackup, updateBackupSchedule,} from "@/lib/generated/sdk.gen";
import type {IoCraftpanelMasterServiceBackupResponse as Backup, IoCraftpanelMasterServiceBackupScheduleResponse as Schedule,} from "@/lib/generated/types.gen";
import {fmtBytes} from "@/lib/utils/format";

function fmtDate(iso: string): string {
    return new Date(iso).toLocaleString();
}

const STATUS_CLASSES: Record<string, string> = {
    IN_PROGRESS: "text-warning border border-warning/30 bg-warning/10",
    COMPLETED: "text-healthy border border-healthy/30 bg-healthy/10",
    FAILED: "text-error border border-error/30 bg-error/10",
};

export function BackupsTab({serverId}: { serverId: string }) {
    const [backups, setBackups] = useState<Backup[]>([]);
    const [schedule, setSchedule] = useState<Schedule | null>(null);
    const [loading, setLoading] = useState(true);
    const [triggering, setTriggering] = useState(false);
    const [deleting, setDeleting] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);

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

    async function handleTrigger() {
        setTriggering(true);
        setError(null);
        const res = await triggerBackup({path: {id: serverId}});
        if (res.error) setError((res.error as { message?: string })?.message ?? "Failed to trigger backup");
        else await load();
        setTriggering(false);
    }

    async function handleDelete(backupId: string) {
        setDeleting(backupId);
        setError(null);
        const res = await deleteBackup({path: {id: serverId, backupId}});
        if (res.error) setError((res.error as { message?: string })?.message ?? "Failed to delete backup");
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
            setScheduleError((res.error as { message?: string })?.message ?? "Failed to save schedule");
        } else {
            setEditingSchedule(false);
            await load();
        }
        setSavingSchedule(false);
    }

    if (loading) {
        return <div className="text-text-dim text-sm p-4">Loading backups…</div>;
    }

    return (
        <div className="px-6 py-6 space-y-6">
            {error && (
                <div className="text-error text-sm bg-error/10 border border-error/30 rounded px-3 py-2">{error}</div>
            )}

            {/* Backup schedule */}
            <div className="bg-surface rounded-lg border border-border p-4">
                <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-2">
                        <Clock className="w-4 h-4 text-text-dim"/>
                        <span className="text-sm font-medium text-text-primary">Backup Schedule</span>
                    </div>
                    {!editingSchedule && (
                        <button
                            onClick={() => setEditingSchedule(true)}
                            className="text-xs text-accent hover:text-accent-bright transition-colors"
                        >
                            Edit
                        </button>
                    )}
                </div>

                {!editingSchedule ? (
                    <div className="text-sm text-text-dim space-y-1">
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
                            <label className="block text-xs text-text-muted mb-1">Cron expression (leave empty to disable)</label>
                            <input
                                value={scheduleInput}
                                onChange={(e) => setScheduleInput(e.target.value)}
                                placeholder="0 2 * * *"
                                className="w-full bg-bg border border-border rounded px-3 py-1.5 text-sm font-mono text-text-primary focus:outline-none focus:border-accent"
                            />
                        </div>
                        <div>
                            <label className="block text-xs text-text-muted mb-1">Max backups to keep</label>
                            <input
                                type="number"
                                min={1}
                                value={maxCountInput}
                                onChange={(e) => setMaxCountInput(e.target.value)}
                                className="w-24 bg-bg border border-border rounded px-3 py-1.5 text-sm text-text-primary focus:outline-none focus:border-accent"
                            />
                        </div>
                        {scheduleError && (
                            <div className="text-error text-xs">{scheduleError}</div>
                        )}
                        <div className="flex gap-2">
                            <button
                                onClick={handleSaveSchedule}
                                disabled={savingSchedule}
                                className="px-3 py-1.5 bg-accent text-bg text-xs rounded hover:bg-accent-bright transition-colors disabled:opacity-50"
                            >
                                {savingSchedule ? "Saving…" : "Save"}
                            </button>
                            <button
                                onClick={() => {
                                    setEditingSchedule(false);
                                    setScheduleError(null);
                                }}
                                className="px-3 py-1.5 border border-border text-text-dim text-xs rounded hover:text-text-primary transition-colors"
                            >
                                Cancel
                            </button>
                        </div>
                    </div>
                )}
            </div>

            {/* Manual trigger */}
            <div className="flex items-center justify-between">
                <span className="text-sm text-text-dim">{backups.length} backup{backups.length !== 1 ? "s" : ""}</span>
                <div className="flex gap-2">
                    <button
                        onClick={load}
                        className="flex items-center gap-1.5 px-3 py-1.5 border border-border rounded text-xs text-text-dim hover:text-text-primary transition-colors"
                    >
                        <RefreshCw className="w-3 h-3"/>
                        Refresh
                    </button>
                    <button
                        onClick={handleTrigger}
                        disabled={triggering}
                        className="flex items-center gap-1.5 px-3 py-1.5 bg-accent text-bg text-xs rounded hover:bg-accent-bright transition-colors disabled:opacity-50"
                    >
                        <Play className="w-3 h-3"/>
                        {triggering ? "Triggering…" : "Trigger Backup"}
                    </button>
                </div>
            </div>

            {/* Backup list */}
            {backups.length === 0 ? (
                <div className="text-center text-text-muted text-sm py-8 border border-border rounded-lg bg-surface">
                    No backups yet
                </div>
            ) : (
                <div className="space-y-2">
                    {backups.map((backup) => (
                        <div
                            key={backup.id}
                            className="flex items-center justify-between bg-surface border border-border rounded-lg px-4 py-3"
                        >
                            <div className="flex items-center gap-3 min-w-0">
                <span
                    className={`px-2 py-0.5 rounded text-xs font-medium shrink-0 ${STATUS_CLASSES[backup.status] ?? "text-text-dim"}`}
                >
                  {backup.status}
                </span>
                                <div className="min-w-0">
                                    <div className="text-xs text-text-dim truncate">
                                        {fmtDate(backup.created_at)}
                                        {backup.trigger === "SCHEDULED" && (
                                            <span className="ml-2 text-text-muted">(scheduled)</span>
                                        )}
                                    </div>
                                    {backup.size_bytes != null && (
                                        <div className="text-xs text-text-muted">{fmtBytes(backup.size_bytes)}</div>
                                    )}
                                    {backup.error_message && (
                                        <div className="text-xs text-error truncate">{backup.error_message}</div>
                                    )}
                                </div>
                            </div>

                            <div className="flex items-center gap-2 shrink-0 ml-3">
                                {backup.status === "COMPLETED" && (
                                    <a
                                        href={`/api/servers/${serverId}/backups/${backup.id}/download`}
                                        download
                                        className="flex items-center gap-1 px-2 py-1 text-xs border border-border rounded text-text-dim hover:text-text-primary transition-colors"
                                    >
                                        <Download className="w-3 h-3"/>
                                        Download
                                    </a>
                                )}
                                {backup.status !== "IN_PROGRESS" && (
                                    <button
                                        onClick={() => handleDelete(backup.id!)}
                                        disabled={deleting === backup.id}
                                        className="p-1.5 rounded text-text-muted hover:text-error transition-colors disabled:opacity-50"
                                        title="Delete backup"
                                    >
                                        <Trash2 className="w-3.5 h-3.5"/>
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
