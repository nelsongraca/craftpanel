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
            setError((res.error as { message?: string }).message ?? "Failed to update config mode");
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
                description: "Existing vars are preserved but won't be applied to server.properties until you switch back.",
                onConfirm: () => void applyToggleMode(next),
            });
            return;
        }
        void applyToggleMode(next);
    }

    return (
        <>
            <div className="flex items-center justify-between">
                <div>
                    <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-1">
                        Config Mode
                    </p>
                    <p className="text-xs text-text-dim">
                        {isManual ? manualDescription : managedDescription}
                    </p>
                </div>
                <button
                    onClick={handleToggleMode}
                    disabled={togglingMode}
                    className="px-3 py-1.5 rounded text-xs font-heading font-bold uppercase tracking-widest border border-border text-text-dim hover:border-text-muted transition-colors disabled:opacity-40"
                >
                    {togglingMode ? "Switching…" : isManual ? "Switch to Managed" : "Switch to Manual"}
                </button>
            </div>

            {error && (
                <div className="text-xs text-error bg-error/10 border border-error/30 rounded px-3 py-2">
                    {error}
                </div>
            )}

            {dialog}
        </>
    );
}
