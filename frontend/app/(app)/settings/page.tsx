"use client";

import {useCallback, useEffect, useRef, useState} from "react";
import Image from "next/image";
import PageHeader from "@/app/components/PageHeader";
import {getSystemSettings, updateSystemSettings} from "@/lib/generated/sdk.gen";
import type {SettingsMap} from "@/lib/types";
import {BTN_PRIMARY, BTN_GHOST, Field, TextField} from "@/components/ui/form-elements";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import {resetBrandingCache} from "@/lib/config";

type FormState = {
    app_name: string;
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
    console_tail_lines: string;
    dns_domain_suffix: string;
    dns_zone_id: string;
};

function toForm(s: SettingsMap): FormState {
    return {
        app_name: s.app_name,
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
        console_tail_lines: String(s.console_tail_lines),
        dns_domain_suffix: s.dns_domain_suffix ?? "",
        dns_zone_id: s.dns_zone_id ?? "",
    };
}

export default function SettingsPage() {
    const {user} = useAuth();
    const permissions = user?.permissions ?? [];
    const canEdit = hasPermission(permissions, "system.settings");

    const [form, setForm] = useState<FormState | null>(null);
    const [settingsData, setSettingsData] = useState<SettingsMap | null>(null);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState("");
    const [success, setSuccess] = useState(false);
    const [logoData, setLogoData] = useState<string | undefined>(undefined);
    const logoRef = useRef<HTMLInputElement>(null);

    const load = useCallback(async () => {
        const {data} = await getSystemSettings();
        if (data) {
            setForm(toForm(data.settings));
            setSettingsData(data.settings);
        }
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

    function handleLogoFile(e: React.ChangeEvent<HTMLInputElement>) {
        const file = e.target.files?.[0];
        if (!file) return;
        const reader = new FileReader();
        reader.onload = () => {
            setLogoData(reader.result as string);
            setSuccess(false);
            setError("");
        };
        reader.readAsDataURL(file);
    }

    function handleResetLogo() {
        setLogoData("");
        setSuccess(false);
        setError("");
        if (logoRef.current) logoRef.current.value = "";
    }

    function logoPreview(): string | null {
        if (logoData !== undefined) return logoData || null;
        return settingsData?.app_logo ?? null;
    }

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        if (!form) return;
        setError("");
        setSuccess(false);
        setSaving(true);

        const body: Record<string, unknown> = {
            app_name: form.app_name || undefined,
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
            console_tail_lines: parseInt(form.console_tail_lines, 10) || undefined,
            dns_domain_suffix: form.dns_domain_suffix,
            dns_zone_id: form.dns_zone_id,
        };

        if (logoData !== undefined) {
            body.app_logo = logoData;
        }

        const {error: apiError} = await updateSystemSettings({body} as never);

        setSaving(false);
        if (apiError) {
            setError(apiError.message ?? "Failed to save settings");
            return;
        }
        setSuccess(true);
        setLogoData(undefined);
        resetBrandingCache();
        void load();
    }

    if (!canEdit) {
        return (
            <div>
                <PageHeader title="Settings" subtitle="Runtime configuration"/>
                <div className="p-6 text-sm text-text-muted">You do not have permission to view or edit system settings.</div>
            </div>
        );
    }

    return (
        <div>
            <PageHeader title="Settings" subtitle="Runtime configuration - changes take effect immediately unless noted"/>

            <div className="p-6">
                {loading ? (
                    <div className="text-xs text-text-muted">Loading…</div>
                ) : form ? (
                    <form onSubmit={handleSubmit} className="max-w-4xl space-y-8">

                        {/* ── Branding ───────────────────────────────────────── */}
                        <section className="bg-surface border border-border rounded-md p-5 space-y-5">
                            <h2 className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                                Branding
                            </h2>
                            <Field label="App Name">
                                <TextField
                                    type="text"
                                    placeholder="CraftPanel"
                                    value={form.app_name}
                                    onChange={(e) => set("app_name", e.target.value)}
                                />
                                <p className="text-xs text-text-muted mt-1">Displayed in the top bar, browser tab, and PWA manifest.</p>
                            </Field>
                            <Field label="Logo">
                                <div className="flex items-center gap-4">
                                    {logoPreview() ? (
                                        <Image
                                            src={logoPreview()!}
                                            alt="Logo preview"
                                            width={40}
                                            height={40}
                                            unoptimized
                                            className="rounded object-contain bg-surface-high border border-border"
                                        />
                                    ) : (
                                        <div className="w-10 h-10 rounded bg-surface-high border border-border flex items-center justify-center text-xs text-text-muted">
                                            —
                                        </div>
                                    )}
                                    <button
                                        type="button"
                                        className={BTN_GHOST}
                                        onClick={() => logoRef.current?.click()}
                                    >
                                        {logoData !== undefined ? "Change Logo" : "Upload Logo"}
                                    </button>
                                    {logoData !== undefined && (
                                        <button
                                            type="button"
                                            className="text-xs text-text-muted hover:text-error transition-colors"
                                            onClick={handleResetLogo}
                                        >
                                            Cancel
                                        </button>
                                    )}
                                    {(logoData === undefined && settingsData?.app_logo) && (
                                        <button
                                            type="button"
                                            className="text-xs text-text-muted hover:text-error transition-colors"
                                            onClick={handleResetLogo}
                                        >
                                            Reset to Default
                                        </button>
                                    )}
                                </div>
                                <input
                                    ref={logoRef}
                                    type="file"
                                    accept="image/*"
                                    className="hidden"
                                    onChange={handleLogoFile}
                                />
                                <p className="text-xs text-text-muted mt-1">Upload a logo image. Displayed in the top bar, login page, and PWA icon. Saved as a data URI.</p>
                            </Field>
                        </section>

                        {/* ── Metrics & Backups ────────────────────────────────── */}
                        <section className="bg-surface border border-border rounded-md p-5 space-y-5">
                            <h2 className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                                Metrics &amp; Backups
                            </h2>
                            <Field label="Metric Retention (days)">
                                <TextField
                                    type="number"
                                    min={1}
                                    value={form.metric_retention_days}
                                    onChange={(e) => set("metric_retention_days", e.target.value)}
                                    required
                                />
                            </Field>
                            <Field label="Default Max Backup Count">
                                <TextField
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
                            <h2 className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                                Default Port Range
                            </h2>
                            <div className="grid grid-cols-2 gap-4">
                                <Field label="Start">
                                    <TextField
                                        type="number"
                                        min={1024}
                                        max={65534}
                                        value={form.default_port_range_start}
                                        onChange={(e) => set("default_port_range_start", e.target.value)}
                                        required
                                    />
                                </Field>
                                <Field label="End">
                                    <TextField
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
                            <h2 className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                                Crash Restart
                            </h2>
                            <Field label="Max Restart Attempts">
                                <TextField
                                    type="number"
                                    min={0}
                                    value={form.restart_max_attempts}
                                    onChange={(e) => set("restart_max_attempts", e.target.value)}
                                    required
                                />
                                <p className="text-xs text-text-muted mt-1">Set to 0 to disable automatic crash restarts. Takes effect on master restart.</p>
                            </Field>
                            <Field label="Restart Window (seconds)">
                                <TextField
                                    type="number"
                                    min={1}
                                    value={form.restart_window_seconds}
                                    onChange={(e) => set("restart_window_seconds", e.target.value)}
                                    required
                                />
                                <p className="text-xs text-text-muted mt-1">Rolling window for counting consecutive crashes. Takes effect on master restart.</p>
                            </Field>
                        </section>

                        {/* ── Rate Limits ─────────────────────────────────────── */}
                        <section className="bg-surface border border-border rounded-md p-5 space-y-5">
                            <h2 className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                                Auth Rate Limits
                            </h2>
                            <p className="text-xs text-text-muted -mt-2">Rate limit changes take effect on master restart.</p>
                            <div className="grid grid-cols-2 gap-4">
                                <Field label="Login requests / minute">
                                    <TextField
                                        type="number"
                                        min={1}
                                        value={form.rate_limit_login_per_minute}
                                        onChange={(e) => set("rate_limit_login_per_minute", e.target.value)}
                                        required
                                    />
                                </Field>
                                <Field label="Token refresh / minute">
                                    <TextField
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
                            <h2 className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                                Container Images
                            </h2>
                            <p className="text-xs text-text-muted -mt-2">Image overrides take effect on master restart.</p>
                            <Field label="Minecraft image">
                                <TextField
                                    type="text"
                                    placeholder="itzg/minecraft-server"
                                    value={form.image_minecraft}
                                    onChange={(e) => set("image_minecraft", e.target.value)}
                                    required
                                />
                            </Field>
                            <Field label="Proxy image">
                                <TextField
                                    type="text"
                                    placeholder="itzg/mc-proxy"
                                    value={form.image_proxy}
                                    onChange={(e) => set("image_proxy", e.target.value)}
                                    required
                                />
                            </Field>
                        </section>

                        {/* ── DNS (Cloudflare) ────────────────────────────────── */}
                        <section className="bg-surface border border-border rounded-md p-5 space-y-5">
                            <h2 className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                                DNS (Cloudflare)
                            </h2>
                            <p className="text-xs text-text-muted -mt-2">
                                Optional. Required to expose servers publicly. Applies to the whole install — see the docs for the one-time Cloudflare setup.
                            </p>
                            <Field label="DNS Zone ID">
                                <TextField
                                    type="text"
                                    placeholder="32-hex Cloudflare zone ID"
                                    value={form.dns_zone_id}
                                    onChange={(e) => set("dns_zone_id", e.target.value)}
                                />
                            </Field>
                            <Field label="DNS Domain Suffix">
                                <TextField
                                    type="text"
                                    placeholder="mc.example.com"
                                    value={form.dns_domain_suffix}
                                    onChange={(e) => set("dns_domain_suffix", e.target.value)}
                                />
                            </Field>
                        </section>

                        {/* ── Console ──────────────────────────────────────────── */}
                        <section className="bg-surface border border-border rounded-md p-5 space-y-5">
                            <h2 className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                                Console
                            </h2>
                            <Field label="History lines shown on console open">
                                <TextField
                                    type="number"
                                    min={1}
                                    max={5000}
                                    value={form.console_tail_lines}
                                    onChange={(e) => set("console_tail_lines", e.target.value)}
                                    required
                                />
                            </Field>
                        </section>

                        {error && <p className="text-xs text-error">{error}</p>}
                        {success && <p className="text-xs text-healthy">Settings saved.</p>}

                        <div className="flex justify-end">
                            <button type="submit" className={BTN_PRIMARY} disabled={saving}>
                                {saving ? "Saving…" : "Save Settings"}
                            </button>
                        </div>
                    </form>
                ) : (
                    <div className="text-xs text-error">Failed to load settings.</div>
                )}
            </div>
        </div>
    );
}
