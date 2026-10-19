"use client";

import {updateStopCommand} from "@/lib/generated/sdk.gen";
import {useConfigSection} from "@/lib/hooks/useConfigSection";

export function StopCommandSection({
    serverId,
    stopCommand: initialStopCommand,
    placeholder = "stop",
}: {
    serverId: string;
    stopCommand: string;
    placeholder?: string;
}) {
    const {
        draft: stopCmd,
        setDraft: setStopCmd,
        isDirty,
        saving,
        error,
        save,
    } = useConfigSection<string>({
        initial: initialStopCommand,
        persist: (value) => updateStopCommand({path: {id: serverId}, body: {stop_command: value}}),
    });

    return (
        <div className="rounded border border-border">
            <div className="border-b border-border bg-surface-high px-4 py-2.5">
                <p className="font-heading text-xs font-bold tracking-widest text-text-muted uppercase">Stop Command</p>
            </div>
            <div className="flex items-end gap-3 px-4 py-3">
                <div className="max-w-xs flex-1">
                    <input
                        value={stopCmd}
                        onChange={(e) => setStopCmd(e.target.value)}
                        placeholder={placeholder}
                        className="w-full rounded border border-border bg-surface-higher px-2 py-1.5 font-mono text-xs text-text-primary focus:border-accent/50 focus:outline-none"
                    />
                    <p className="mt-1 text-xs text-text-muted">
                        Command sent to stdin on stop / restart. ^C = SIGINT, ^\ = SIGQUIT, or any SIG* name. Leave
                        empty for Docker stop.
                    </p>
                </div>
                {isDirty && (
                    <button
                        onClick={() => void save()}
                        disabled={saving}
                        className="rounded bg-accent px-3 py-1.5 font-heading text-xs font-bold tracking-widest text-on-accent uppercase transition-colors hover:bg-accent-bright disabled:opacity-60"
                    >
                        {saving ? "Saving…" : "Save"}
                    </button>
                )}
            </div>
            {error && <div className="px-4 pb-3 text-xs text-error">{error}</div>}
        </div>
    );
}
