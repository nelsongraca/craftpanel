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
    const {user} = useAuth();
    const [status, setStatus] = useState<TotpStatus | null>(null);
    const [statusError, setStatusError] = useState("");
    const [setupOpen, setSetupOpen] = useState(false);
    const [disableOpen, setDisableOpen] = useState(false);
    const [code, setCode] = useState("");
    const [error, setError] = useState("");
    const [busy, setBusy] = useState(false);

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
                <TotpSetupModal onClose={() => setSetupOpen(false)} onEnabled={() => setStatus(null)}/>
            )}
        </div>
    );
}