"use client";

import {useCallback, useState} from "react";
import {getProxySettings, updateProxySettings} from "@/lib/generated/sdk.gen";
import {SelectField} from "@/components/ui/form-elements";
import {useConfigSection} from "@/lib/hooks/useConfigSection";
import {isVelocityType} from "@/lib/server-types";

const VELOCITY_FORWARDING_MODES = ["NONE", "LEGACY", "MODERN", "BUNGEEGUARD"];

type ProxySettingsDraft = {
    motd: string;
    maxPlayers: string;
    forwardingMode: string;
};

export function ProxySettingsSection({
    serverId,
    serverType,
}: {
    serverId: string;
    serverType: string;
}) {
    const [forwardingWarnings, setForwardingWarnings] = useState<string[]>([]);

    const isVelocity = isVelocityType(serverType);

    const load = useCallback(async () => {
        const res = await getProxySettings({path: {id: serverId}});
        if (res.error) return {error: res.error as { message?: string }};
        const data = res.data;
        return {
            data: {
                motd: data?.motd ?? "",
                maxPlayers: data?.max_players?.toString() ?? "",
                forwardingMode: data?.forwarding_mode ?? "",
            },
        };
    }, [serverId]);

    const {draft, setDraft, isDirty, loading, saving, error, save, discard} = useConfigSection<ProxySettingsDraft>({
        initial: {motd: "", maxPlayers: "", forwardingMode: ""},
        load,
        persist: (d) => updateProxySettings({
            path: {id: serverId},
            body: {
                motd: d.motd || null,
                max_players: d.maxPlayers ? parseInt(d.maxPlayers, 10) : null,
                forwarding_mode: d.forwardingMode || null,
            },
        }),
    });

    async function handleSave() {
        const res = await save();
        if (!res.error) {
            setForwardingWarnings((res.data as { forwarding_warnings?: string[] } | undefined)?.forwarding_warnings ?? []);
        }
    }

    if (loading) {
        return <div className="px-6 py-10 text-center text-text-muted text-sm">Loading{"\u2026"}</div>;
    }

    return (
        <div className="border border-border rounded">
            <div className="px-4 py-2.5 border-b border-border bg-surface-high">
                <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">
                    Proxy Settings
                </p>
            </div>
            <div className="px-4 py-3 space-y-4">
                <div>
                    <label className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-1 block">
                        MOTD
                    </label>
                    <input
                        value={draft.motd}
                        onChange={(e) => setDraft((p) => ({...p, motd: e.target.value}))}
                        placeholder="A Minecraft Proxy"
                        className="bg-surface-higher border border-border rounded px-2 py-1.5 text-xs font-mono text-text-primary w-full max-w-md focus:border-accent/50 focus:outline-none"
                    />
                    <p className="text-xs text-text-muted mt-1">Message of the Day shown on the server list.</p>
                </div>

                <div>
                    <label className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-1 block">
                        Max Players
                    </label>
                    <input
                        type="number"
                        min={1}
                        value={draft.maxPlayers}
                        onChange={(e) => setDraft((p) => ({...p, maxPlayers: e.target.value}))}
                        placeholder="20"
                        className="bg-surface-higher border border-border rounded px-2 py-1.5 text-xs font-mono text-text-primary w-32 focus:border-accent/50 focus:outline-none"
                    />
                    <p className="text-xs text-text-muted mt-1">Maximum number of concurrent players.</p>
                </div>

                <div>
                    <label className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-1 block">
                        Forwarding Mode
                    </label>
                    {isVelocity ? (
                        <SelectField
                            surface="surface-higher"
                            className="w-48"
                            value={draft.forwardingMode}
                            onChange={(e) => setDraft((p) => ({...p, forwardingMode: e.target.value}))}
                        >
                            <option value="">Default</option>
                            {VELOCITY_FORWARDING_MODES.map((m) => (
                                <option key={m} value={m}>
                                    {m}
                                </option>
                            ))}
                        </SelectField>
                    ) : (
                        <label className="flex items-center gap-2 text-xs font-mono text-text-primary">
                            <input
                                type="checkbox"
                                checked={draft.forwardingMode === "LEGACY"}
                                onChange={(e) => setDraft((p) => ({...p, forwardingMode: e.target.checked ? "LEGACY" : "OFF"}))}
                                className="accent-accent"
                            />
                            IP Forwarding
                        </label>
                    )}
                    <p className="text-xs text-text-muted mt-1">
                        {isVelocity
                            ? "Player info forwarding mode (NONE, LEGACY, MODERN, BUNGEEGUARD)."
                            : "IP forwarding (BungeeGuard-style). On = LEGACY, off = OFF."}
                    </p>
                </div>

                {error && (
                    <div className="text-xs text-error bg-error/10 border border-error/30 rounded px-3 py-2">
                        {error}
                    </div>
                )}

                {forwardingWarnings.length > 0 && (
                    <div className="text-xs text-warning bg-warning/10 border border-warning/30 rounded px-3 py-2 space-y-1">
                        {forwardingWarnings.map((w, i) => (
                            <p key={i}>{w}</p>
                        ))}
                    </div>
                )}

                {isDirty && (
                    <div className="flex items-center gap-2 pt-2 border-t border-border">
                        <span className="text-xs text-text-muted">Unsaved changes</span>
                        <button
                            onClick={discard}
                            className="px-3 py-1.5 rounded text-xs font-heading font-bold uppercase tracking-widest text-text-dim border border-border hover:border-text-muted transition-colors"
                        >
                            Discard
                        </button>
                        <button
                            onClick={handleSave}
                            disabled={saving}
                            className="px-3 py-1.5 rounded text-xs font-heading font-bold uppercase tracking-widest bg-accent text-bg hover:bg-accent-bright transition-colors disabled:opacity-60"
                        >
                            {saving ? "Saving\u2026" : "Save"}
                        </button>
                    </div>
                )}
            </div>
        </div>
    );
}
