"use client";

import {useCallback, useEffect, useState} from "react";
import {ChevronDown, ChevronUp, Plus, Trash2, X} from "lucide-react";
import {getProxyBackends, listServers, replaceProxyBackends, updateStopCommand} from "@/lib/generated/sdk.gen";
import type {PutProxyBackendsRequest} from "@/lib/types";
import type {ServerResponse} from "@/lib/generated/types.gen";

const PROXY_TYPES = new Set(["VELOCITY", "BUNGEECORD", "WATERFALL"]);

function slugify(name: string): string {
    return name
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-+|-+$/g, "");
}

function AddBackendModal({
                             available,
                             onAdd,
                             onClose,
                         }: {
    available: ServerResponse[];
    onAdd: (server: ServerResponse, backendName: string) => void;
    onClose: () => void;
}) {
    const [selectedId, setSelectedId] = useState(available[0]?.id ?? "");
    const [backendName, setBackendName] = useState(slugify(available[0]?.display_name ?? ""));

    function handleServerChange(id: string) {
        setSelectedId(id);
        const s = available.find((s) => s.id === id);
        if (s) setBackendName(slugify(s.display_name));
    }

    const selected = available.find((s) => s.id === selectedId);
    const valid = backendName.trim().length > 0 && /^[a-z0-9_-]+$/.test(backendName.trim());

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-bg/80">
            <div className="bg-surface border border-border rounded-lg p-6 w-[420px] space-y-4">
                <div className="flex items-center justify-between">
                    <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">
                        Add Backend
                    </p>
                    <button
                        onClick={onClose}
                        className="text-text-muted hover:text-text-primary transition-colors"
                    >
                        <X className="w-4 h-4"/>
                    </button>
                </div>

                <div className="space-y-3">
                    <div>
                        <label className="block text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted mb-1.5">
                            Server
                        </label>
                        <select
                            value={selectedId}
                            onChange={(e) => handleServerChange(e.target.value)}
                            className="w-full bg-surface-high border border-border rounded px-3 py-2 text-[12px] text-text-primary focus:border-accent/50 focus:outline-none"
                        >
                            {available.map((s) => (
                                <option key={s.id} value={s.id}>
                                    {s.display_name}
                                </option>
                            ))}
                        </select>
                    </div>
                    <div>
                        <label className="block text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted mb-1.5">
                            Backend Name
                            <span className="ml-1 normal-case font-normal text-text-muted">
                                (used in proxy config)
                            </span>
                        </label>
                        <input
                            value={backendName}
                            onChange={(e) => setBackendName(e.target.value)}
                            placeholder="e.g. survival"
                            className="w-full bg-surface-high border border-border rounded px-3 py-2 text-[12px] font-mono text-text-primary focus:border-accent/50 focus:outline-none"
                        />
                        {backendName && !valid && (
                            <p className="text-[12px] text-error mt-1">
                                Lowercase letters, numbers, hyphens and underscores only
                            </p>
                        )}
                    </div>
                </div>

                <div className="flex justify-end gap-2 pt-1">
                    <button
                        onClick={onClose}
                        className="px-3 py-1.5 rounded text-[12px] font-heading font-bold uppercase tracking-widest text-text-dim border border-border hover:border-text-muted transition-colors"
                    >
                        Cancel
                    </button>
                    <button
                        onClick={() => selected && onAdd(selected, backendName.trim())}
                        disabled={!valid || !selected}
                        className="px-3 py-1.5 rounded text-[12px] font-heading font-bold uppercase tracking-widest bg-accent text-bg hover:bg-accent-bright transition-colors disabled:opacity-50"
                    >
                        Add
                    </button>
                </div>
            </div>
        </div>
    );
}

type EditableBackend = {
    id?: string;
    backendServerId: string;
    backendName: string;
    order: number;
    displayName: string;
    serverType: string;
    status: string;
};

export function ProxyBackendsSection({
                                         serverId,
                                         networkId,
                                         stopCommand: initialStopCommand,
                                         onOpenGeneralSettings,
                                     }: {
    serverId: string;
    networkId: string | null;
    stopCommand: string;
    onOpenGeneralSettings?: () => void;
}) {
    const [backends, setBackends] = useState<EditableBackend[]>([]);
    const [saved, setSaved] = useState<EditableBackend[]>([]);
    const [networkServers, setNetworkServers] = useState<ServerResponse[]>([]);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [showAddModal, setShowAddModal] = useState(false);

    const [stopCmd, setStopCmd] = useState(initialStopCommand);
    const [savedStopCmd, setSavedStopCmd] = useState(initialStopCommand);
    const [savingStop, setSavingStop] = useState(false);
    const [stopError, setStopError] = useState<string | null>(null);

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        const [backendsRes, serversRes] = await Promise.all([
            getProxyBackends({path: {id: serverId}}),
            listServers(),
        ]);
        if (backendsRes.error) {
            setError((backendsRes.error as { message?: string }).message ?? "Failed to load backends");
            setLoading(false);
            return;
        }
        const raw = backendsRes.data?.backends ?? [];
        const allServers = serversRes.data ?? [];
        const netServers = networkId
            ? allServers.filter((s) => s.network_id === networkId && s.id !== serverId)
            : [];
        const enriched: EditableBackend[] = raw.map((b) => {
            const match = allServers.find((s) => s.id === b.backend_server_id);
            return {
                id: b.id,
                backendServerId: b.backend_server_id,
                backendName: b.backend_name,
                order: b.order,
                displayName: match?.display_name ?? b.backend_server_id,
                serverType: match?.server_type ?? "UNKNOWN",
                status: match?.status ?? "UNKNOWN",
            };
        });
        setBackends(enriched);
        setSaved(enriched);
        setNetworkServers(netServers);
        setLoading(false);
    }, [serverId, networkId]);

    useEffect(() => {
        load();
    }, [load]);

    function moveUp(index: number) {
        if (index === 0) return;
        setBackends((prev) => {
            const next = [...prev];
            [next[index - 1], next[index]] = [next[index], next[index - 1]];
            return next.map((b, i) => ({...b, order: i + 1}));
        });
    }

    function moveDown(index: number) {
        setBackends((prev) => {
            if (index >= prev.length - 1) return prev;
            const next = [...prev];
            [next[index], next[index + 1]] = [next[index + 1], next[index]];
            return next.map((b, i) => ({...b, order: i + 1}));
        });
    }

    function removeBackend(index: number) {
        setBackends((prev) =>
            prev.filter((_, i) => i !== index).map((b, i) => ({...b, order: i + 1})),
        );
    }

    function renameBackend(index: number, name: string) {
        setBackends((prev) => prev.map((b, i) => (i === index ? {...b, backendName: name} : b)));
    }

    function addBackend(server: ServerResponse, backendName: string) {
        setBackends((prev) => [
            ...prev,
            {
                backendServerId: server.id,
                backendName,
                order: prev.length + 1,
                displayName: server.display_name,
                serverType: server.server_type,
                status: server.status,
            },
        ]);
    }

    const isDirty = JSON.stringify(backends) !== JSON.stringify(saved);

    async function handleSave() {
        const names = backends.map((b) => b.backendName.trim());
        if (new Set(names).size !== names.length) {
            setError("Backend names must be unique");
            return;
        }
        setSaving(true);
        setError(null);
        const body: PutProxyBackendsRequest = {
            backends: backends.map((b) => ({
                backend_server_id: b.backendServerId,
                backend_name: b.backendName.trim(),
                order: b.order,
            })),
        };
        const res = await replaceProxyBackends({path: {id: serverId}, body});
        if (res.error) {
            setError((res.error as { message?: string }).message ?? "Save failed");
        } else {
            await load();
        }
        setSaving(false);
    }

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

    if (loading) {
        return <div className="px-6 py-10 text-center text-text-muted text-[13px]">Loading\u2026</div>;
    }

    const addedIds = new Set(backends.map((b) => b.backendServerId));
    const available = networkServers.filter(
        (s) => !PROXY_TYPES.has(s.server_type) && !addedIds.has(s.id),
    );

    return (
        <div className="px-6 py-6 space-y-6">
            {/* Stop Command */}
            <div className="border border-border rounded">
                <div className="px-4 py-2.5 border-b border-border bg-surface-high">
                    <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">
                        Stop Command
                    </p>
                </div>
                <div className="px-4 py-3 flex items-end gap-3">
                    <div className="flex-1 max-w-xs">
                        <input
                            value={stopCmd}
                            onChange={(e) => setStopCmd(e.target.value)}
                            placeholder="end"
                            className="bg-surface-higher border border-border rounded px-2 py-1.5 text-[12px] font-mono text-text-primary w-full focus:border-accent/50 focus:outline-none"
                        />
                        <p className="text-[12px] text-text-muted mt-1">
                            Command sent to stdin on stop / restart. Leave empty to skip.
                        </p>
                    </div>
                    {stopCmd !== savedStopCmd && (
                        <button
                            onClick={handleSaveStopCmd}
                            disabled={savingStop}
                            className="px-3 py-1.5 rounded text-[12px] font-heading font-bold uppercase tracking-widest bg-accent text-bg hover:bg-accent-bright transition-colors disabled:opacity-60"
                        >
                            {savingStop ? "Saving\u2026" : "Save"}
                        </button>
                    )}
                </div>
                {stopError && (
                    <div className="px-4 pb-3 text-[12px] text-error">{stopError}</div>
                )}
            </div>

            <div className="flex items-center justify-between">
                <div>
                    <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted mb-1">
                        Proxy Backends
                    </p>
                    <p className="text-[12px] text-text-dim">
                        Backend servers routed by this proxy in managed mode.
                    </p>
                </div>
                <button
                    onClick={() => setShowAddModal(true)}
                    disabled={available.length === 0}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded text-[12px] font-heading font-bold uppercase tracking-widest bg-accent/10 text-accent border border-accent/30 hover:bg-accent/20 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                >
                    <Plus className="w-3.5 h-3.5"/>
                    Add Backend
                </button>
            </div>

            {error && (
                <div className="text-[12px] text-error bg-error/10 border border-error/30 rounded px-3 py-2">
                    {error}
                </div>
            )}

            {!networkId && (
                <div className="flex items-center justify-between gap-3 text-[12px] text-warning bg-warning/10 border border-warning/30 rounded px-3 py-2">
                    <span>This server is not in a network. Assign it to a network to add backends.</span>
                    {onOpenGeneralSettings && (
                        <button
                            onClick={onOpenGeneralSettings}
                            className="shrink-0 font-heading font-bold uppercase tracking-wider underline hover:no-underline"
                        >
                            Assign Network
                        </button>
                    )}
                </div>
            )}

            {backends.length === 0 ? (
                <div className="border border-dashed border-border rounded py-8 text-center text-text-muted text-[12px]">
                    No backends configured.
                </div>
            ) : (
                <div className="border border-border rounded overflow-hidden">
                    <table className="w-full text-[12px]">
                        <thead>
                        <tr className="border-b border-border bg-surface-high">
                            <th className="px-4 py-2.5 text-left text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted w-8">
                                #
                            </th>
                            <th className="px-4 py-2.5 text-left text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">
                                Server
                            </th>
                            <th className="px-4 py-2.5 text-left text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">
                                Backend Name
                            </th>
                            <th className="px-4 py-2.5 text-left text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">
                                Status
                            </th>
                            <th className="px-4 py-2.5 w-32"></th>
                        </tr>
                        </thead>
                        <tbody>
                        {backends.map((b, i) => (
                            <tr
                                key={b.backendServerId}
                                className="border-b border-border last:border-0 hover:bg-surface-high/50 transition-colors"
                            >
                                <td className="px-4 py-3 text-text-muted font-mono">
                                    {b.order}
                                </td>
                                <td className="px-4 py-3">
                                    <span className="text-text-primary">{b.displayName}</span>
                                    <span className="ml-2 text-[12px] text-text-muted font-mono">
                                            {b.serverType}
                                        </span>
                                </td>
                                <td className="px-4 py-3">
                                    <input
                                        value={b.backendName}
                                        onChange={(e) => renameBackend(i, e.target.value)}
                                        className="bg-surface-higher border border-border rounded px-2 py-1 text-[12px] text-text-primary font-mono w-36 focus:border-accent/50 focus:outline-none"
                                    />
                                </td>
                                <td className="px-4 py-3">
                                        <span
                                            className={`text-[12px] font-heading font-bold uppercase tracking-widest ${
                                                b.status === "HEALTHY"
                                                    ? "text-healthy"
                                                    : b.status === "STOPPED"
                                                        ? "text-text-muted"
                                                        : "text-warning"
                                            }`}
                                        >
                                            {b.status}
                                        </span>
                                </td>
                                <td className="px-4 py-3">
                                    <div className="flex items-center gap-1 justify-end">
                                        <button
                                            onClick={() => moveUp(i)}
                                            disabled={i === 0}
                                            className="p-1 text-text-muted hover:text-text-primary disabled:opacity-30 transition-colors"
                                        >
                                            <ChevronUp className="w-3.5 h-3.5"/>
                                        </button>
                                        <button
                                            onClick={() => moveDown(i)}
                                            disabled={i === backends.length - 1}
                                            className="p-1 text-text-muted hover:text-text-primary disabled:opacity-30 transition-colors"
                                        >
                                            <ChevronDown className="w-3.5 h-3.5"/>
                                        </button>
                                        <button
                                            onClick={() => removeBackend(i)}
                                            className="p-1 text-text-muted hover:text-error transition-colors ml-1"
                                        >
                                            <Trash2 className="w-3.5 h-3.5"/>
                                        </button>
                                    </div>
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>
            )}

            {isDirty && (
                <div className="flex items-center justify-between pt-2 border-t border-border">
                    <span className="text-[12px] text-text-muted">Unsaved changes</span>
                    <div className="flex gap-2">
                        <button
                            onClick={() => setBackends(saved)}
                            className="px-3 py-1.5 rounded text-[12px] font-heading font-bold uppercase tracking-widest text-text-dim border border-border hover:border-text-muted transition-colors"
                        >
                            Discard
                        </button>
                        <button
                            onClick={handleSave}
                            disabled={saving}
                            className="px-3 py-1.5 rounded text-[12px] font-heading font-bold uppercase tracking-widest bg-accent text-bg hover:bg-accent-bright transition-colors disabled:opacity-60"
                        >
                            {saving ? "Saving\u2026" : "Save"}
                        </button>
                    </div>
                </div>
            )}

            {showAddModal && (
                <AddBackendModal
                    available={available}
                    onAdd={(server, name) => {
                        addBackend(server, name);
                        setShowAddModal(false);
                    }}
                    onClose={() => setShowAddModal(false)}
                />
            )}
        </div>
    );
}
