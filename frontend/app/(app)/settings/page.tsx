"use client";

import {useCallback, useEffect, useState} from "react";
import PageHeader from "@/app/components/PageHeader";
import {getSystemSettings, updateSystemSettings} from "@/lib/generated/sdk.gen";
import type {SettingsMap} from "@/lib/types";
import {BTN_PRIMARY, Field, INPUT} from "@/components/ui/form-elements";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";

type FormState = {
    metric_retention_days: string;
    default_backup_max_count: string;
    default_port_range_start: string;
    default_port_range_end: string;
    restart_max_attempts: string;
    restart_window_seconds: string;
    rate_limit_login_per_minute: string;
    rate_limit_refresh_per_minute: string;
    image_minecraft: string;
    image_proxy: string;
};

function toForm(s: SettingsMap): FormState {
    return {
        metric_retention_days: String(s.metric_retention_days),
        default_backup_max_count: String(s.default_backup_max_count),
        default_port_range_start: String(s.default_port_range_start),
        default_port_range_end: String(s.default_port_range_end),
        restart_max_attempts: String(s.restart_max_attempts),
        restart_window_seconds: String(s.restart_window_seconds),
        rate_limit_login_per_minute: String(s.rate_limit_login_per_minute),
        rate_limit_refresh_per_minute: String(s.rate_limit_refresh_per_minute),
        image_minecraft: s.image_minecraft,
        image_proxy: s.image_proxy,
    };
}

export default function SettingsPage() {
    const {user} = useAuth();
    const permissions = user?.permissions ?? [];
    const canEdit = hasPermission(permissions, "system.settings");

    const [form, setForm] = useState<FormState | null>(null);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState("");
    const [success, setSuccess] = useState(false);

    const load = useCallback(async () => {
        const {data} = await getSystemSettings();
        if (data) setForm(toForm(data.settings));
        setLoading(false);
    }, []);

    useEffect(() => {
        void load();
    }, [load]);

    function set(key: keyof FormState, value: string) {
        setForm((f) => f ? {...f, [key]: value} : f);
        setSuccess(false);
        setError("");
    }

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        if (!form) return;
        setError("");
        setSuccess(false);
        setSaving(true);

        const {error: apiError} = await updateSystemSettings({
            body: {
                metric_retention_days: parseInt(form.metric_retention_days, 10) || undefined,
                default_backup_max_count: parseInt(form.default_backup_max_count, 10) || undefined,
                default_port_range_start: parseInt(form.default_port_range_start, 10) || undefined,
                default_port_range_end: parseInt(form.default_port_range_end, 10) || undefined,
                restart_max_attempts: parseInt(form.restart_max_attempts, 10),
                restart_window_seconds: parseInt(form.restart_window_seconds, 10) || undefined,
                rate_limit_login_per_minute: parseInt(form.rate_limit_login_per_minute, 10) || undefined,
                rate_limit_refresh_per_minute: parseInt(form.rate_limit_refresh_per_minute, 10) || undefined,
                image_minecraft: form.image_minecraft || undefined,
                image_proxy: form.image_proxy || undefined,
            },
        });

        setSaving(false);
        if (apiError) {
            setError(apiError.message ?? "Failed to save settings");
            return;
        }
        setSuccess(true);
        void load();
    }

    if (!canEdit) {
        return (
            <div>
                <PageHeader title="Settings" subtitle="Runtime configuration"/>
                <div className="p-6 text-[13px] text-text-muted">You do not have permission to view or edit system settings.</div>
            </div>
        );
    }

    return (
        <div>
            <PageHeader title="Settings" subtitle="Runtime configuration — changes take effect immediately unless noted"/>

            <div className="p-6">
                {loading ? (
                    <div className="text-[12px] text-text-muted">Loading…</div>
                ) : form ? (
                    <form onSubmit={handleSubmit} className="max-w-2xl space-y-8">

                        {/* ── Metrics & Backups ────────────────────────────────── */}
                        <section className="bg-surface border border-border rounded-md p-5 space-y-5">
                            <h2 className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                                Metrics &amp; Backups
                            </h2>
                            <Field label="Metric Retention (days)">
                                <input
                                    className={INPUT}
                                    type="number"
                                    min={1}
                                    value={form.metric_retention_days}
                                    onChange={(e) => set("metric_retention_days", e.target.value)}
                                    required
                                />
                            </Field>
                            <Field label="Default Max Backup Count">
                                <input
                                    className={INPUT}
                                    type="number"
                                    min={1}
                                    value={form.default_backup_max_count}
                                    onChange={(e) => set("default_backup_max_count", e.target.value)}
                                    required
                                />
                            </Field>
                        </section>

                        {/* ── Port Range ──────────────────────────────────────── */}
                        <section className="bg-surface border border-border rounded-md p-5 space-y-5">
                            <h2 className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                                Default Port Range
                            </h2>
                            <div className="grid grid-cols-2 gap-4">
                                <Field label="Start">
                                    <input
                                        className={INPUT}
                                        type="number"
                                        min={1024}
                                        max={65534}
                                        value={form.default_port_range_start}
                                        onChange={(e) => set("default_port_range_start", e.target.value)}
                                        required
                                    />
                                </Field>
                                <Field label="End">
                                    <input
                                        className={INPUT}
                                        type="number"
                                        min={1025}
                                        max={65535}
                                        value={form.default_port_range_end}
                                        onChange={(e) => set("default_port_range_end", e.target.value)}
                                        required
                                    />
                                </Field>
                            </div>
                        </section>

                        {/* ── Crash Restart ───────────────────────────────────── */}
                        <section className="bg-surface border border-border rounded-md p-5 space-y-5">
                            <h2 className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                                Crash Restart
                            </h2>
                            <Field label="Max Restart Attempts">
                                <input
                                    className={INPUT}
                                    type="number"
                                    min={0}
                                    value={form.restart_max_attempts}
                                    onChange={(e) => set("restart_max_attempts", e.target.value)}
                                    required
                                />
                                <p className="text-[11px] text-text-muted mt-1">Set to 0 to disable automatic crash restarts. Takes effect on master restart.</p>
                            </Field>
                            <Field label="Restart Window (seconds)">
                                <input
                                    className={INPUT}
                                    type="number"
                                    min={1}
                                    value={form.restart_window_seconds}
                                    onChange={(e) => set("restart_window_seconds", e.target.value)}
                                    required
                                />
                                <p className="text-[11px] text-text-muted mt-1">Rolling window for counting consecutive crashes. Takes effect on master restart.</p>
                            </Field>
                        </section>

                        {/* ── Rate Limits ─────────────────────────────────────── */}
                        <section className="bg-surface border border-border rounded-md p-5 space-y-5">
                            <h2 className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                                Auth Rate Limits
                            </h2>
                            <p className="text-[11px] text-text-muted -mt-2">Rate limit changes take effect on master restart.</p>
                            <div className="grid grid-cols-2 gap-4">
                                <Field label="Login requests / minute">
                                    <input
                                        className={INPUT}
                                        type="number"
                                        min={1}
                                        value={form.rate_limit_login_per_minute}
                                        onChange={(e) => set("rate_limit_login_per_minute", e.target.value)}
                                        required
                                    />
                                </Field>
                                <Field label="Token refresh / minute">
                                    <input
                                        className={INPUT}
                                        type="number"
                                        min={1}
                                        value={form.rate_limit_refresh_per_minute}
                                        onChange={(e) => set("rate_limit_refresh_per_minute", e.target.value)}
                                        required
                                    />
                                </Field>
                            </div>
                        </section>

                        {/* ── Container Images ────────────────────────────────── */}
                        <section className="bg-surface border border-border rounded-md p-5 space-y-5">
                            <h2 className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                                Container Images
                            </h2>
                            <p className="text-[11px] text-text-muted -mt-2">Image overrides take effect on master restart.</p>
                            <Field label="Minecraft image">
                                <input
                                    className={INPUT}
                                    type="text"
                                    placeholder="itzg/minecraft-server"
                                    value={form.image_minecraft}
                                    onChange={(e) => set("image_minecraft", e.target.value)}
                                    required
                                />
                            </Field>
                            <Field label="Proxy image">
                                <input
                                    className={INPUT}
                                    type="text"
                                    placeholder="itzg/mc-proxy"
                                    value={form.image_proxy}
                                    onChange={(e) => set("image_proxy", e.target.value)}
                                    required
                                />
                            </Field>
                        </section>

                        {error && <p className="text-[12px] text-error">{error}</p>}
                        {success && <p className="text-[12px] text-healthy">Settings saved.</p>}

                        <div className="flex justify-end">
                            <button type="submit" className={BTN_PRIMARY} disabled={saving}>
                                {saving ? "Saving…" : "Save Settings"}
                            </button>
                        </div>
                    </form>
                ) : (
                    <div className="text-[12px] text-error">Failed to load settings.</div>
                )}
            </div>
        </div>
    );
}
