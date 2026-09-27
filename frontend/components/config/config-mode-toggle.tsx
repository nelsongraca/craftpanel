"use client";

import {useState} from "react";
import {updateConfigMode} from "@/lib/generated/sdk.gen";
import type {ConfigMode} from "@/lib/types";
import {useConfirmDialog} from "@/lib/hooks/useConfirmDialog";

export function ConfigModeToggle({
    serverId,
    configMode,
    onChanged,
    manualDescription,
    managedDescription,
}: {
    serverId: string;
    configMode: string;
    onChanged: (next: string) => void;
    manualDescription: string;
    managedDescription: string;
}) {
    const [togglingMode, setTogglingMode] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const {confirm, dialog} = useConfirmDialog();

    const isManual = configMode === "MANUAL";

    async function applyToggleMode(next: string) {
        setTogglingMode(true);
        setError(null);
        const res = await updateConfigMode({path: {id: serverId}, body: {config_mode: next as ConfigMode}});
        if (res.error) {
            setError((res.error as {message?: string}).message ?? "Failed to update config mode");
        } else {
            onChanged(next);
        }
        setTogglingMode(false);
    }

    function handleToggleMode() {
        const next = isManual ? "MANAGED" : "MANUAL";
        if (!isManual) {
            confirm({
                title: "Disable Managed Env Vars?",
                description:
                    "Existing vars are preserved but won't be applied to server.properties until you switch back.",
                onConfirm: () => void applyToggleMode(next),
            });
            return;
        }
        void applyToggleMode(next);
    }

    return (
        <>
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div>
                    <p className="mb-1 font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                        Config Mode
                    </p>
                    <p className="text-xs text-text-dim">{isManual ? manualDescription : managedDescription}</p>
                </div>
                <button
                    onClick={handleToggleMode}
                    disabled={togglingMode}
                    className="shrink-0 self-start rounded border border-border px-3 py-1.5 font-heading text-xs font-bold tracking-widest text-text-dim uppercase transition-colors hover:border-text-muted disabled:opacity-40 sm:self-auto"
                >
                    {togglingMode ? "Switching…" : isManual ? "Switch to Managed" : "Switch to Manual"}
                </button>
            </div>

            {error && (
                <div className="rounded border border-error/30 bg-error/10 px-3 py-2 text-xs text-error">{error}</div>
            )}

            {dialog}
        </>
    );
}
