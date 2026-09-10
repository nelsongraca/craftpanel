"use client";

import {useCallback, useEffect, useState} from "react";
import {CheckCircle2, ShieldAlert} from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import {useAuth} from "@/lib/auth-context";
import {authTotpDisable, authTotpStatus} from "@/lib/generated";
import type {TotpStatus} from "@/lib/types";
import {BTN_GHOST, BTN_PRIMARY, Field, TextField} from "@/components/ui/form-elements";
import {TotpSetupModal} from "@/components/auth/TotpSetupModal";

export default function AccountPage() {
    const {user, changePassword, logoutAll} = useAuth();
    const [status, setStatus] = useState<TotpStatus | null>(null);
    const [statusError, setStatusError] = useState("");
    const [setupOpen, setSetupOpen] = useState(false);
    const [disableOpen, setDisableOpen] = useState(false);
    const [code, setCode] = useState("");
    const [error, setError] = useState("");
    const [busy, setBusy] = useState(false);
    const [pwOld, setPwOld] = useState("");
    const [pwNew, setPwNew] = useState("");
    const [pwConfirm, setPwConfirm] = useState("");
    const [pwError, setPwError] = useState("");
    const [pwChanged, setPwChanged] = useState(false);
    const [pwSaving, setPwSaving] = useState(false);
    const [sessionsBusy, setSessionsBusy] = useState(false);
    const [sessionsMsg, setSessionsMsg] = useState("");

    const loadStatus = useCallback(async () => {
        const {data, error} = await authTotpStatus();
        setStatusError(error?.message ?? "");
        if (data) setStatus(data);
    }, []);

    useEffect(() => {
        void loadStatus();
    }, [loadStatus]);

    async function disable() {
        setError("");
        setBusy(true);
        const {error: apiError} = await authTotpDisable({body: {code: code.trim()}});
        setBusy(false);
        if (apiError) {
            setError(apiError.message ?? "Invalid verification code");
            return;
        }
        setDisableOpen(false);
        setCode("");
        setStatus(null);
        void loadStatus();
    }

    async function handlePasswordSubmit(e: React.FormEvent) {
        e.preventDefault();
        setPwError("");
        if (pwNew !== pwConfirm) {
            setPwError("Passwords do not match");
            return;
        }
        setPwSaving(true);
        try {
            await changePassword(pwOld, pwNew);
            setPwChanged(true);
            setPwOld("");
            setPwNew("");
            setPwConfirm("");
        } catch (err) {
            setPwError(err instanceof Error ? err.message : "Failed to change password");
        } finally {
            setPwSaving(false);
        }
    }

    return (
        <div>
            <PageHeader title="Account" subtitle="Your profile and sign-in security"/>

            <div className="p-6 max-w-4xl space-y-6">
                {/* ── Profile ─────────────────────────────────────────────── */}
                <section className="bg-surface border border-border rounded-md p-5 space-y-4">
                    <h2 className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                        Profile
                    </h2>
                    <div className="grid grid-cols-2 gap-4">
                        <div>
                            <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">Username</p>
                            <p className="text-sm text-text-primary mt-1">{user?.username ?? "—"}</p>
                        </div>
                        <div>
                            <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">Email</p>
                            <p className="text-sm text-text-primary mt-1">{user?.email ?? "—"}</p>
                        </div>
                    </div>
                </section>

                {/* ── Password ────────────────────────────────────────────── */}
                <section className="bg-surface border border-border rounded-md p-5 space-y-4">
                    <h2 className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                        Password
                    </h2>

                    {pwChanged ? (
                        <div className="space-y-4">
                            <p className="text-sm text-text-dim">Your password has been changed. You can continue using the panel, or sign out to test your new password.</p>
                            <div className="flex justify-end">
                                <button className={BTN_PRIMARY} onClick={() => setPwChanged(false)}>
                                    Change password again
                                </button>
                            </div>
                        </div>
                    ) : (
                        <form onSubmit={handlePasswordSubmit} className="space-y-4 max-w-md">
                            <Field label="Current Password">
                                <TextField type="password" value={pwOld} onChange={(e) => setPwOld(e.target.value)} required autoComplete="current-password"/>
                            </Field>
                            <Field label="New Password">
                                <TextField type="password" value={pwNew} onChange={(e) => setPwNew(e.target.value)} required autoComplete="new-password"/>
                            </Field>
                            <Field label="Confirm New Password">
                                <TextField type="password" value={pwConfirm} onChange={(e) => setPwConfirm(e.target.value)} required autoComplete="new-password"/>
                            </Field>
                            {pwError && <p className="text-xs text-error">{pwError}</p>}
                            <div className="flex justify-end">
                                <button type="submit" className={BTN_PRIMARY} disabled={pwSaving}>
                                    {pwSaving ? "Saving…" : "Change Password"}
                                </button>
                            </div>
                        </form>
                    )}
                </section>

                {/* ── Two-factor authentication ───────────────────────────── */}
                <section className="bg-surface border border-border rounded-md p-5 space-y-4">
                    <h2 className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                        Two-Factor Authentication
                    </h2>

                    {statusError && <p className="text-xs text-error">{statusError}</p>}

                    <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                            {status?.enabled ? (
                                <CheckCircle2 size={16} className="text-healthy"/>
                            ) : (
                                <ShieldAlert size={16} className="text-text-muted"/>
                            )}
                            <p className={`text-sm ${status?.enabled ? "text-healthy" : "text-text-muted"}`}>
                                {status ? (status.enabled ? "TOTP is enabled" : "TOTP is disabled") : "…"}
                            </p>
                        </div>
                        {status?.enabled && (
                            <span className="text-xs text-text-muted">
                                {status.recovery_codes_remaining} recovery code{status.recovery_codes_remaining === 1 ? "" : "s"} remaining
                            </span>
                        )}
                    </div>

                    <p className="text-xs text-text-muted">
                        {status?.enabled
                            ? "Each sign-in requires a code from your authenticator app. Use a recovery code if you lose your device."
                            : "Add an extra layer of security: sign-in will require a one-time code from an authenticator app."}
                    </p>

                    {!status?.enabled && (
                        <div className="flex justify-end">
                            <button className={BTN_PRIMARY} onClick={() => setSetupOpen(true)}>
                                Set up TOTP
                            </button>
                        </div>
                    )}

                    {status?.enabled && (
                        <div className="flex justify-end">
                            <button className={BTN_GHOST} onClick={() => setDisableOpen((o) => !o)}>
                                Disable TOTP
                            </button>
                        </div>
                    )}

                    {disableOpen && (
                        <form
                            onSubmit={(e) => {
                                e.preventDefault();
                                void disable();
                            }}
                            className="space-y-4 border-t border-border pt-4"
                        >
                            <Field label="Current verification code">
                                <TextField
                                    type="text"
                                    inputMode="numeric"
                                    value={code}
                                    onChange={(e) => setCode(e.target.value)}
                                    placeholder="123456"
                                    autoComplete="one-time-code"
                                    autoFocus
                                    className="font-mono tracking-[0.3em] text-center"
                                />
                                <p className="text-xs text-text-muted mt-1">Enter a code from your authenticator app to confirm.</p>
                            </Field>
                            {error && <p className="text-xs text-error">{error}</p>}
                            <div className="flex justify-end gap-2">
                                <button type="button" className={BTN_GHOST} onClick={() => setDisableOpen(false)}>
                                    Cancel
                                </button>
                                <button type="submit" className={BTN_PRIMARY} disabled={busy || code.trim().length !== 6}>
                                    {busy ? "Disabling…" : "Disable 2FA"}
                                </button>
                            </div>
                        </form>
                    )}
                </section>
            </div>

            {setupOpen && (
                <TotpSetupModal onClose={() => setSetupOpen(false)} onEnabled={() => void loadStatus()}/>
            )}

            {/* ── Sessions ────────────────────────────────────────────── */}
            <section className="bg-surface border border-border rounded-md p-5 space-y-4">
                <h2 className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted border-b border-border pb-3">
                    Sessions
                </h2>
                <p className="text-sm text-text-dim">
                    Sign out all other sessions across devices. Your current session will remain active.
                </p>
                {sessionsMsg && <p className="text-xs text-healthy">{sessionsMsg}</p>}
                <div className="flex justify-end">
                    <button
                        className={BTN_GHOST}
                        disabled={sessionsBusy}
                        onClick={async () => {
                            setSessionsBusy(true);
                            setSessionsMsg("");
                            const ok = await logoutAll();
                            setSessionsBusy(false);
                            if (ok) setSessionsMsg("All other sessions have been signed out.");
                        }}
                    >
                        {sessionsBusy ? "Signing out…" : "Sign out all other sessions"}
                    </button>
                </div>
            </section>
        </div>
    );
}