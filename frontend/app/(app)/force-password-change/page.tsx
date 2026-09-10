"use client";

import {useState} from "react";
import Link from "next/link";
import {KeyRound} from "lucide-react";
import {useAuth} from "@/lib/auth-context";
import {BTN_PRIMARY, Field, TextField} from "@/components/ui/form-elements";

export default function ForcePasswordChange() {
    const {user, forceChangePassword} = useAuth();
    const [password, setPassword] = useState("");
    const [confirm, setConfirm] = useState("");
    const [error, setError] = useState("");
    const [saving, setSaving] = useState(false);
    const [done, setDone] = useState(false);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError("");
        if (password !== confirm) {
            setError("Passwords do not match");
            return;
        }
        if (!password) {
            setError("Password is required");
            return;
        }
        setSaving(true);
        try {
            await forceChangePassword(password);
            setDone(true);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to change password");
        } finally {
            setSaving(false);
        }
    }

    return (
        <div className="min-h-screen flex items-center justify-center bg-bg p-4">
            <div className="w-full max-w-md bg-surface border border-border rounded-md p-8 space-y-6">
                <div className="flex items-center gap-3">
                    <div className="w-10 h-10 rounded-md bg-accent/15 border border-accent/30 flex items-center justify-center">
                        <KeyRound size={18} className="text-accent"/>
                    </div>
                    <div>
                        <h1 className="font-heading font-bold uppercase tracking-widest text-text-primary">
                            Change Required
                        </h1>
                        <p className="text-xs text-text-dim">{user?.username}</p>
                    </div>
                </div>

                <p className="text-sm text-text-dim">
                    An administrator has reset your password. Set a new password before continuing.
                </p>

                {done ? (
                    <div className="space-y-4">
                        <p className="text-sm text-healthy">Your password has been changed. You can now continue.</p>
                        <div className="flex justify-end">
                            <Link href="/" className={BTN_PRIMARY}>Continue</Link>
                        </div>
                    </div>
                ) : (
                    <form onSubmit={handleSubmit} className="space-y-4">
                        <Field label="New Password">
                            <TextField
                                type="password"
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                                required
                                autoFocus
                                autoComplete="new-password"
                            />
                        </Field>
                        <Field label="Confirm New Password">
                            <TextField
                                type="password"
                                value={confirm}
                                onChange={(e) => setConfirm(e.target.value)}
                                required
                                autoComplete="new-password"
                            />
                        </Field>
                        {error && <p className="text-xs text-error">{error}</p>}
                        <div className="flex justify-end">
                            <button type="submit" className={BTN_PRIMARY} disabled={saving}>
                                {saving ? "Saving…" : "Change Password"}
                            </button>
                        </div>
                    </form>
                )}
            </div>
        </div>
    );
}