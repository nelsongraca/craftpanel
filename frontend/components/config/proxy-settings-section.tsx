"use client";

import {useCallback, useEffect, useState} from "react";
import {getProxySettings, updateProxySettings} from "@/lib/generated/sdk.gen";

const VELOCITY_FORWARDING_MODES = ["NONE", "LEGACY", "MODERN", "BUNGEEGUARD"];

export function ProxySettingsSection({
    serverId,
    serverType,
}: {
    serverId: string;
    serverType: string;
}) {
    const [motd, setMotd] = useState("");
    const [maxPlayers, setMaxPlayers] = useState("");
    const [forwardingMode, setForwardingMode] = useState("");
    const [savedMotd, setSavedMotd] = useState("");
    const [savedMaxPlayers, setSavedMaxPlayers] = useState("");
    const [savedForwardingMode, setSavedForwardingMode] = useState("");
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const isVelocity = serverType === "VELOCITY";

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        const res = await getProxySettings({path: {id: serverId}});
        if (res.error) {
            setError((res.error as { message?: string }).message ?? "Failed to load proxy settings");
            setLoading(false);
            return;
        }
        const data = res.data;
        setMotd(data?.motd ?? "");
        setMaxPlayers(data?.max_players?.toString() ?? "");
        setForwardingMode(data?.forwarding_mode ?? "");
        setSavedMotd(data?.motd ?? "");
        setSavedMaxPlayers(data?.max_players?.toString() ?? "");
        setSavedForwardingMode(data?.forwarding_mode ?? "");
        setLoading(false);
    }, [serverId]);

    useEffect(() => {
        load();
    }, [load]);

    const isDirty = motd !== savedMotd || maxPlayers !== savedMaxPlayers || forwardingMode !== savedForwardingMode;

    async function handleSave() {
        setSaving(true);
        setError(null);
        const body = {
            motd: motd || null,
            max_players: maxPlayers ? parseInt(maxPlayers, 10) : null,
            forwarding_mode: forwardingMode || null,
        };
        const res = await updateProxySettings({path: {id: serverId}, body});
        if (res.error) {
            setError((res.error as { message?: string }).message ?? "Save failed");
        } else {
            await load();
        }
        setSaving(false);
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
                        value={motd}
                        onChange={(e) => setMotd(e.target.value)}
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
                        value={maxPlayers}
                        onChange={(e) => setMaxPlayers(e.target.value)}
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
                        <select
                            value={forwardingMode}
                            onChange={(e) => setForwardingMode(e.target.value)}
                            className="bg-surface-higher border border-border rounded px-2 py-1.5 text-xs font-mono text-text-primary w-48 focus:border-accent/50 focus:outline-none"
                        >
                            <option value="">Default</option>
                            {VELOCITY_FORWARDING_MODES.map((m) => (
                                <option key={m} value={m}>
                                    {m}
                                </option>
                            ))}
                        </select>
                    ) : (
                        <label className="flex items-center gap-2 text-xs font-mono text-text-primary">
                            <input
                                type="checkbox"
                                checked={forwardingMode === "LEGACY"}
                                onChange={(e) => setForwardingMode(e.target.checked ? "LEGACY" : "OFF")}
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

                {isDirty && (
                    <div className="flex items-center gap-2 pt-2 border-t border-border">
                        <span className="text-xs text-text-muted">Unsaved changes</span>
                        <button
                            onClick={() => {
                                setMotd(savedMotd);
                                setMaxPlayers(savedMaxPlayers);
                                setForwardingMode(savedForwardingMode);
                            }}
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