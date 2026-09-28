"use client";

import {useCallback, useEffect, useMemo, useState} from "react";
import {CalendarClock, Pencil, Plus, RefreshCw, Trash2} from "lucide-react";
import {
    createScheduledJob,
    deleteScheduledJob,
    listScheduledJobs,
    updateScheduledJob,
} from "@/lib/generated/sdk.gen";
import type {ScheduledJobResponse as Job, ScheduledJobType} from "@/lib/generated/types.gen";
import {Empty, EmptyDescription} from "@/components/ui/empty";
import {BTN_GHOST, BTN_PRIMARY, Field, SelectField, TextField} from "@/components/ui/form-elements";
import {Dialog, DialogContent, DialogHeader, DialogTitle} from "@/components/ui/dialog";
import {hasPermission} from "@/lib/permissions";

const JOB_TYPE_LABELS: Record<ScheduledJobType, string> = {
    START: "Start server",
    STOP: "Stop server",
    RESTART: "Restart server",
    RCON_COMMAND: "Console command",
};

/** Action permission the caller must hold to schedule that job type. */
const JOB_TYPE_PERMISSION: Record<ScheduledJobType, string> = {
    START: "server.start",
    STOP: "server.stop",
    RESTART: "server.restart",
    RCON_COMMAND: "server.console",
};

function fmtDate(iso: string): string {
    return new Date(iso).toLocaleString();
}

type FormState = {id: string | null; type: ScheduledJobType; cron: string; payload: string; enabled: boolean};

const EMPTY_FORM: FormState = {id: null, type: "RESTART", cron: "0 4 * * *", payload: "", enabled: true};

export function JobsTab({serverId, permissions}: {serverId: string; permissions: string[]}) {
    const [jobs, setJobs] = useState<Job[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);
    const [dialogOpen, setDialogOpen] = useState(false);
    const [form, setForm] = useState<FormState>(EMPTY_FORM);

    const allowedTypes = useMemo(
        () =>
            (Object.keys(JOB_TYPE_PERMISSION) as ScheduledJobType[]).filter((type) =>
                hasPermission(permissions, JOB_TYPE_PERMISSION[type]),
            ),
        [permissions],
    );

    const canManage = hasPermission(permissions, "server.cron");
    const showPayload = form.type === "RCON_COMMAND";

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        const res = await listScheduledJobs({path: {id: serverId}});
        if (res.data) setJobs(res.data);
        else setError("Failed to load scheduled jobs");
        setLoading(false);
    }, [serverId]);

    useEffect(() => {
        void load();
    }, [load]);

    function openCreate() {
        setForm({...EMPTY_FORM, type: allowedTypes[0] ?? "RESTART"});
        setError(null);
        setDialogOpen(true);
    }

    function openEdit(job: Job) {
        setForm({id: job.id, type: job.type, cron: job.cron_expression, payload: job.payload ?? "", enabled: job.enabled});
        setError(null);
        setDialogOpen(true);
    }

    async function handleSave() {
        setSaving(true);
        setError(null);
        const res = form.id
            ? await updateScheduledJob({
                path: {id: serverId, jobId: form.id},
                body: {cron_expression: form.cron, payload: form.payload || null, enabled: form.enabled},
            })
            : await createScheduledJob({
                path: {id: serverId},
                body: {type: form.type, cron_expression: form.cron, payload: form.payload || null, enabled: form.enabled},
            });
        if (res.error) {
            setError((res.error as {message?: string})?.message ?? "Failed to save job");
        } else {
            setDialogOpen(false);
            await load();
        }
        setSaving(false);
    }

    async function handleToggle(job: Job) {
        setError(null);
        const res = await updateScheduledJob({path: {id: serverId, jobId: job.id}, body: {enabled: !job.enabled}});
        if (res.error) setError((res.error as {message?: string})?.message ?? "Failed to update job");
        else await load();
    }

    async function handleDelete(jobId: string) {
        setError(null);
        const res = await deleteScheduledJob({path: {id: serverId, jobId}});
        if (res.error) setError((res.error as {message?: string})?.message ?? "Failed to delete job");
        else setJobs((prev) => prev.filter((j) => j.id !== jobId));
    }

    if (loading) {
        return <div className="p-4 text-sm text-text-dim">Loading scheduled jobs…</div>;
    }

    return (
        <div className="space-y-6 px-4 py-6">
            {error && (
                <div className="rounded border border-error/30 bg-error/10 px-3 py-2 text-sm text-error">{error}</div>
            )}

            <div className="flex items-center justify-between">
                <span className="text-sm text-text-dim">
                    {jobs.length} scheduled job{jobs.length !== 1 ? "s" : ""}
                </span>
                <div className="flex gap-2">
                    <button
                        onClick={load}
                        className="flex items-center gap-1.5 rounded border border-border px-3 py-1.5 text-xs text-text-dim transition-colors hover:text-text-primary"
                    >
                        <RefreshCw className="h-3 w-3" />
                        Refresh
                    </button>
                    {canManage && (
                        <button
                            onClick={openCreate}
                            disabled={allowedTypes.length === 0}
                            title={allowedTypes.length === 0 ? "You lack the permission for any schedulable job type" : undefined}
                            className="flex items-center gap-1.5 rounded bg-accent px-3 py-1.5 text-xs text-bg transition-colors hover:bg-accent-bright disabled:opacity-50"
                        >
                            <Plus className="h-3 w-3" />
                            Add Job
                        </button>
                    )}
                </div>
            </div>

            {jobs.length === 0 ? (
                <Empty>
                    <EmptyDescription>No scheduled jobs yet</EmptyDescription>
                </Empty>
            ) : (
                <div className="space-y-2">
                    {jobs.map((job) => (
                        <div
                            key={job.id}
                            className="flex items-center justify-between rounded-lg border border-border bg-surface px-4 py-3"
                        >
                            <div className="flex min-w-0 items-center gap-3">
                                <CalendarClock className={`h-4 w-4 shrink-0 ${job.enabled ? "text-accent" : "text-text-muted"}`} />
                                <div className="min-w-0">
                                    <div className="flex items-center gap-2">
                                        <span className="text-sm font-medium text-text-primary">{JOB_TYPE_LABELS[job.type]}</span>
                                        {!job.enabled && <span className="text-xs text-text-muted">(disabled)</span>}
                                    </div>
                                    <div className="truncate font-mono text-xs text-text-dim">{job.cron_expression}</div>
                                    {job.type === "RCON_COMMAND" && job.payload && (
                                        <div className="truncate font-mono text-xs text-text-muted">{job.payload}</div>
                                    )}
                                    {job.last_fired_at && (
                                        <div className="text-xs text-text-muted">Last fired {fmtDate(job.last_fired_at)}</div>
                                    )}
                                </div>
                            </div>

                            {canManage && (
                                <div className="ml-3 flex shrink-0 items-center gap-2">
                                    <button
                                        onClick={() => handleToggle(job)}
                                        className="rounded border border-border px-2 py-1 text-xs text-text-dim transition-colors hover:text-text-primary"
                                    >
                                        {job.enabled ? "Disable" : "Enable"}
                                    </button>
                                    <button
                                        onClick={() => openEdit(job)}
                                        className="rounded p-1.5 text-text-muted transition-colors hover:text-text-primary"
                                        title="Edit job"
                                    >
                                        <Pencil className="h-3.5 w-3.5" />
                                    </button>
                                    <button
                                        onClick={() => handleDelete(job.id)}
                                        className="rounded p-1.5 text-text-muted transition-colors hover:text-error"
                                        title="Delete job"
                                    >
                                        <Trash2 className="h-3.5 w-3.5" />
                                    </button>
                                </div>
                            )}
                        </div>
                    ))}
                </div>
            )}

            <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>{form.id ? "Edit scheduled job" : "Add scheduled job"}</DialogTitle>
                    </DialogHeader>
                    <div className="space-y-4">
                        <Field label="Job type" htmlFor="job-type">
                            <SelectField
                                id="job-type"
                                value={form.type}
                                disabled={form.id !== null}
                                onChange={(e) => setForm((f) => ({...f, type: e.target.value as ScheduledJobType}))}
                            >
                                {allowedTypes.map((type) => (
                                    <option key={type} value={type}>
                                        {JOB_TYPE_LABELS[type]}
                                    </option>
                                ))}
                            </SelectField>
                        </Field>

                        <Field label="Cron expression" htmlFor="job-cron">
                            <TextField
                                id="job-cron"
                                value={form.cron}
                                onChange={(e) => setForm((f) => ({...f, cron: e.target.value}))}
                                placeholder="0 4 * * *"
                            />
                        </Field>

                        {showPayload && (
                            <Field label="Console command" htmlFor="job-payload">
                                <TextField
                                    id="job-payload"
                                    value={form.payload}
                                    onChange={(e) => setForm((f) => ({...f, payload: e.target.value}))}
                                    placeholder="say Server restarting in 5 minutes"
                                />
                            </Field>
                        )}

                        {error && <div className="text-xs text-error">{error}</div>}

                        <div className="flex justify-end gap-2">
                            <button onClick={() => setDialogOpen(false)} className={BTN_GHOST}>Cancel</button>
                            <button onClick={handleSave} disabled={saving} className={BTN_PRIMARY}>
                                {saving ? "Saving…" : "Save"}
                            </button>
                        </div>
                    </div>
                </DialogContent>
            </Dialog>
        </div>
    );
}
