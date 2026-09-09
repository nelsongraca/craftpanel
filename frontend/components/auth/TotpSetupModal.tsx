"use client";

import {useEffect, useState} from "react";
import {authTotpEnable, authTotpSetup} from "@/lib/generated";
import type {TotpSetup} from "@/lib/types";
import {BTN_GHOST, BTN_PRIMARY, Field, Modal, TextField} from "@/components/ui/form-elements";

interface TotpSetupModalProps {
    onClose: () => void;
    onEnabled: () => void;
}

export function TotpSetupModal({onClose, onEnabled}: TotpSetupModalProps) {
    const [setup, setSetup] = useState<TotpSetup | null>(null);
    const [loading, setLoading] = useState(true);
    const [code, setCode] = useState("");
    const [error, setError] = useState("");
    const [busy, setBusy] = useState(false);
    const [enabled, setEnabled] = useState(false);
    const [copied, setCopied] = useState<{ key: string; value: string } | null>(null);

    useEffect(() => {
        let cancelled = false;
        void authTotpSetup().then(({data, error: apiError}) => {
            if (cancelled) return;
            setLoading(false);
            if (apiError) {
                setError(apiError.message ?? "Could not start TOTP setup");
                return;
            }
            setSetup(data ?? null);
        });
        return () => {
            cancelled = true;
        };
    }, []);

    async function enable() {
        if (!setup || code.trim().length === 0) return;
        setError("");
        setBusy(true);
        const {error: apiError} = await authTotpEnable({body: {code: code.trim()}});
        setBusy(false);
        if (apiError) {
            setError(apiError.message ?? "Invalid verification code");
            return;
        }
        setEnabled(true);
        onEnabled();
    }

    function copy(key: string, value: string) {
        navigator.clipboard.writeText(value).then(() => {
            setCopied({key, value});
            setTimeout(() => setCopied(null), 2000);
        });
    }

    return (
        <Modal title="Enable TOTP" onClose={onClose}>
            {loading ? (
                <div className="text-xs text-text-muted">Generating secret…</div>
            ) : error && !setup ? (
                <div className="space-y-4">
                    <p className="text-xs text-error py-1">{error}</p>
                    <div className="flex justify-end">
                        <button className={BTN_PRIMARY} onClick={onClose}>Close</button>
                    </div>
                </div>
            ) : setup && !enabled ? (
                <div className="space-y-5">
                    <div className="flex flex-col items-center gap-3">
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img
                            src={setup.qr_data_uri}
                            alt="TOTP QR code"
                            width={200}
                            height={200}
                            className="rounded border border-border bg-white p-2"
                        />
                        <p className="text-xs text-text-muted text-center">
                            Scan this code with your authenticator app (Google Authenticator, Authy, 1Password, …).
                        </p>
                    </div>

                    <div className="space-y-1.5">
                        <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">
                            Or enter this secret manually
                        </p>
                        <div className="flex items-center gap-2">
                            <code className="flex-1 font-mono text-xs text-text-primary bg-surface-high border border-border rounded px-3 py-2 break-all">
                                {setup.secret}
                            </code>
                            <button className={BTN_GHOST} onClick={() => copy("secret", setup.secret)}>
                                {copied?.key === "secret" ? "Copied!" : "Copy"}
                            </button>
                        </div>
                    </div>

                    <form
                        onSubmit={(e) => {
                            e.preventDefault();
                            void enable();
                        }}
                        className="space-y-4"
                    >
                        <Field label="Verification code">
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
                            <p className="text-xs text-text-muted mt-1">
                                Enter the 6-digit code from your authenticator app to confirm setup.
                            </p>
                        </Field>

                        {error && <p className="text-xs text-error py-1">{error}</p>}

                        <div className="flex justify-end gap-2">
                            <button type="button" className={BTN_GHOST} onClick={onClose}>
                                Cancel
                            </button>
                            <button type="submit" className={BTN_PRIMARY} disabled={busy || code.trim().length !== 6}>
                                {busy ? "Enabling…" : "Enable TOTP"}
                            </button>
                        </div>
                    </form>
                </div>
            ) : (
                <div className="space-y-4">
                    <p className="text-xs text-warning bg-warning/10 border border-warning/30 rounded px-3 py-2">
                        Save these recovery codes in a safe place. Each can be used once to sign in if you
                        lose your authenticator. They won&apos;t be shown again.
                    </p>
                    <div className="grid grid-cols-1 gap-1.5">
                        {setup?.recovery_codes.map((rc) => (
                            <div key={rc} className="flex items-center gap-2">
                                <code className="flex-1 font-mono text-xs text-text-primary bg-surface-high border border-border rounded px-3 py-1.5">
                                    {rc}
                                </code>
                                <button className={BTN_GHOST} onClick={() => copy("rc", setup.recovery_codes.join("\n"))}>
                                    {copied?.key === "rc" ? "Copied!" : "Copy"}
                                </button>
                            </div>
                        ))}
                    </div>
                    <div className="flex justify-end">
                        <button className={BTN_PRIMARY} onClick={onClose}>
                            Done
                        </button>
                    </div>
                </div>
            )}
        </Modal>
    );
}