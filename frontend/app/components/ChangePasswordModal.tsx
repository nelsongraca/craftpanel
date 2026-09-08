"use client";

import {useState} from "react";
import {useAuth} from "@/lib/auth-context";
import {BTN_PRIMARY, BTN_GHOST, Modal, Field, TextField} from "@/components/ui/form-elements";

export function ChangePasswordModal({onClose}: { onClose: () => void }) {
    const {changePassword} = useAuth();
    const [oldPassword, setOldPassword] = useState("");
    const [newPassword, setNewPassword] = useState("");
    const [confirmPassword, setConfirmPassword] = useState("");
    const [error, setError] = useState("");
    const [success, setSuccess] = useState(false);
    const [saving, setSaving] = useState(false);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError("");
        if (newPassword !== confirmPassword) {
            setError("Passwords do not match");
            return;
        }
        setSaving(true);
        try {
            await changePassword(oldPassword, newPassword);
            setSuccess(true);
            setOldPassword("");
            setNewPassword("");
            setConfirmPassword("");
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to change password");
        } finally {
            setSaving(false);
        }
    }

    return (
        <Modal title="Change Password" onClose={onClose}>
            {success ? (
                <div className="space-y-4">
                    <p className="text-sm text-text-dim">Your password has been changed. You can continue using the panel, or sign out to test your new password.</p>
                    <div className="flex justify-end gap-2 pt-1">
                        <button className={BTN_PRIMARY} onClick={onClose}>Done</button>
                    </div>
                </div>
            ) : (
                <form onSubmit={handleSubmit} className="space-y-4">
                    <Field label="Current Password">
                        <TextField type="password" value={oldPassword} onChange={(e) => setOldPassword(e.target.value)} required autoComplete="current-password"/>
                    </Field>
                    <Field label="New Password">
                        <TextField type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} required autoComplete="new-password"/>
                    </Field>
                    <Field label="Confirm New Password">
                        <TextField type="password" value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} required autoComplete="new-password"/>
                    </Field>
                    {error && <p className="text-xs text-error">{error}</p>}
                    <div className="flex justify-end gap-2 pt-1">
                        <button type="button" className={BTN_GHOST} onClick={onClose}>Cancel</button>
                        <button type="submit" className={BTN_PRIMARY} disabled={saving}>{saving ? "Saving…" : "Change Password"}</button>
                    </div>
                </form>
            )}
        </Modal>
    );
}
