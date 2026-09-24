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
    proxyProtocol: boolean;
};

export function ProxySettingsSection({serverId, serverType}: {serverId: string; serverType: string}) {
    const [forwardingWarnings, setForwardingWarnings] = useState<string[]>([]);

    const isVelocity = isVelocityType(serverType);

    const load = useCallback(async () => {
        const res = await getProxySettings({path: {id: serverId}});
        if (res.error) return {error: res.error as {message?: string}};
        const data = res.data;
        return {
            data: {
                motd: data?.motd ?? "",
                maxPlayers: data?.max_players?.toString() ?? "",
                forwardingMode: data?.forwarding_mode ?? "",
                proxyProtocol: data?.proxy_protocol ?? false,
            },
        };
    }, [serverId]);

    const {draft, setDraft, isDirty, loading, saving, error, save, discard} = useConfigSection<ProxySettingsDraft>({
        initial: {motd: "", maxPlayers: "", forwardingMode: "", proxyProtocol: false},
        load,
        persist: (d) =>
            updateProxySettings({
                path: {id: serverId},
                body: {
                    motd: d.motd || null,
                    max_players: d.maxPlayers ? parseInt(d.maxPlayers, 10) : null,
                    forwarding_mode: d.forwardingMode || null,
                    proxy_protocol: d.proxyProtocol,
                },
            }),
    });

    async function handleSave() {
        const res = await save();
        if (!res.error) {
            setForwardingWarnings(
                (res.data as {forwarding_warnings?: string[]} | undefined)?.forwarding_warnings ?? [],
            );
        }
    }

    if (loading) {
        return <div className="px-6 py-10 text-center text-sm text-text-muted">Loading{"\u2026"}</div>;
    }

    return (
        <div className="rounded border border-border">
            <div className="border-b border-border bg-surface-high px-4 py-2.5">
                <p className="font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                    Proxy Settings
                </p>
            </div>
            <div className="space-y-4 px-4 py-3">
                <div>
                    <label className="mb-1 block font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                        MOTD
                    </label>
                    <input
                        value={draft.motd}
                        onChange={(e) => setDraft((p) => ({...p, motd: e.target.value}))}
                        placeholder="A Minecraft Proxy"
                        className="w-full max-w-md rounded border border-border bg-surface-higher px-2 py-1.5 font-mono text-xs text-text-primary focus:border-accent/50 focus:outline-none"
                    />
                    <p className="mt-1 text-xs text-text-muted">Message of the Day shown on the server list.</p>
                </div>

                <div>
                    <label className="mb-1 block font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                        Max Players
                    </label>
                    <input
                        type="number"
                        min={1}
                        value={draft.maxPlayers}
                        onChange={(e) => setDraft((p) => ({...p, maxPlayers: e.target.value}))}
                        placeholder="20"
                        className="w-32 rounded border border-border bg-surface-higher px-2 py-1.5 font-mono text-xs text-text-primary focus:border-accent/50 focus:outline-none"
                    />
                    <p className="mt-1 text-xs text-text-muted">Maximum number of concurrent players.</p>
                </div>

                <div>
                    <label className="mb-1 block font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
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
                        <label className="flex items-center gap-2 font-mono text-xs text-text-primary">
                            <input
                                type="checkbox"
                                checked={draft.forwardingMode === "LEGACY"}
                                onChange={(e) =>
                                    setDraft((p) => ({...p, forwardingMode: e.target.checked ? "LEGACY" : "OFF"}))
                                }
                                className="accent-accent"
                            />
                            IP Forwarding
                        </label>
                    )}
                    <p className="mt-1 text-xs text-text-muted">
                        {isVelocity
                            ? "Player info forwarding mode (NONE, LEGACY, MODERN, BUNGEEGUARD)."
                            : "IP forwarding (BungeeGuard-style). On = LEGACY, off = OFF."}
                    </p>
                </div>

                <div>
                    <label className="flex items-center gap-2 font-mono text-xs text-text-primary">
                        <input
                            type="checkbox"
                            checked={draft.proxyProtocol}
                            onChange={(e) => setDraft((p) => ({...p, proxyProtocol: e.target.checked}))}
                            className="accent-accent"
                        />
                        PROXY Protocol
                    </label>
                    <p className="mt-1 text-xs text-text-muted">
                        Receive the HAProxy PROXY protocol from the node&apos;s router to preserve the real client IP.
                        Only enable this when a load balancer that sends the PROXY protocol sits in front of the router
                        — enabling it otherwise can reject direct player connections.
                    </p>
                </div>

                {error && (
                    <div className="rounded border border-error/30 bg-error/10 px-3 py-2 text-xs text-error">
                        {error}
                    </div>
                )}

                {forwardingWarnings.length > 0 && (
                    <div className="space-y-1 rounded border border-warning/30 bg-warning/10 px-3 py-2 text-xs text-warning">
                        {forwardingWarnings.map((w, i) => (
                            <p key={i}>{w}</p>
                        ))}
                    </div>
                )}

                {isDirty && (
                    <div className="flex items-center gap-2 border-t border-border pt-2">
                        <span className="text-xs text-text-muted">Unsaved changes</span>
                        <button
                            onClick={discard}
                            className="rounded border border-border px-3 py-1.5 font-heading text-xs font-bold tracking-widest text-text-dim uppercase transition-colors hover:border-text-muted"
                        >
                            Discard
                        </button>
                        <button
                            onClick={handleSave}
                            disabled={saving}
                            className="rounded bg-accent px-3 py-1.5 font-heading text-xs font-bold tracking-widest text-bg uppercase transition-colors hover:bg-accent-bright disabled:opacity-60"
                        >
                            {saving ? "Saving\u2026" : "Save"}
                        </button>
                    </div>
                )}
            </div>
        </div>
    );
}
