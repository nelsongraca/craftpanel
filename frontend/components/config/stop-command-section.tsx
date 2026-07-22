"use client";

import {useState} from "react";
import {updateStopCommand} from "@/lib/generated/sdk.gen";

export function StopCommandSection({
                                        serverId,
                                        stopCommand: initialStopCommand,
                                        placeholder = "stop",
                                    }: {
    serverId: string;
    stopCommand: string;
    placeholder?: string;
}) {
    const [stopCmd, setStopCmd] = useState(initialStopCommand);
    const [savedStopCmd, setSavedStopCmd] = useState(initialStopCommand);
    const [savingStop, setSavingStop] = useState(false);
    const [stopError, setStopError] = useState<string | null>(null);

    async function handleSaveStopCmd() {
        setSavingStop(true);
        setStopError(null);
        const res = await updateStopCommand({path: {id: serverId}, body: {stop_command: stopCmd}});
        if (res.error) {
            setStopError((res.error as { message?: string }).message ?? "Failed to save stop command");
        } else {
            setSavedStopCmd(stopCmd);
        }
        setSavingStop(false);
    }

    return (
        <div className="border border-border rounded">
            <div className="px-4 py-2.5 border-b border-border bg-surface-high">
                <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">
                    Stop Command
                </p>
            </div>
            <div className="px-4 py-3 flex items-end gap-3">
                <div className="flex-1 max-w-xs">
                    <input
                        value={stopCmd}
                        onChange={(e) => setStopCmd(e.target.value)}
                        placeholder={placeholder}
                        className="bg-surface-higher border border-border rounded px-2 py-1.5 text-xs font-mono text-text-primary w-full focus:border-accent/50 focus:outline-none"
                    />
                    <p className="text-xs text-text-muted mt-1">
                        Command sent to stdin on stop / restart. Leave empty to skip.
                    </p>
                </div>
                {stopCmd !== savedStopCmd && (
                    <button
                        onClick={handleSaveStopCmd}
                        disabled={savingStop}
                        className="px-3 py-1.5 rounded text-xs font-heading font-bold uppercase tracking-widest bg-accent text-bg hover:bg-accent-bright transition-colors disabled:opacity-60"
                    >
                        {savingStop ? "Saving…" : "Save"}
                    </button>
                )}
            </div>
            {stopError && (
                <div className="px-4 pb-3 text-xs text-error">{stopError}</div>
            )}
        </div>
    );
}
