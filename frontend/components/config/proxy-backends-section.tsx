"use client";

import {useCallback, useState} from "react";
import {ChevronDown, ChevronUp, Plus, Trash2} from "lucide-react";
import {getProxyBackends, listServers, replaceProxyBackends} from "@/lib/generated/sdk.gen";
import type {PutProxyBackendsRequest} from "@/lib/types";
import type {ServerResponse} from "@/lib/generated/types.gen";
import {Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter} from "@/components/ui/dialog";
import {Empty, EmptyDescription} from "@/components/ui/empty";
import {SelectField} from "@/components/ui/form-elements";
import {useConfigSection} from "@/lib/hooks/useConfigSection";
import {isProxyType} from "@/lib/server-types";

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
    const [open, setOpen] = useState(true);
    const [selectedId, setSelectedId] = useState(available[0]?.id ?? "");
    const [backendName, setBackendName] = useState(slugify(available[0]?.display_name ?? ""));

    function handleServerChange(id: string) {
        setSelectedId(id);
        const s = available.find((s) => s.id === id);
        if (s) setBackendName(slugify(s.display_name));
    }

    function handleClose() {
        setOpen(false);
        onClose();
    }

    const selected = available.find((s) => s.id === selectedId);
    const valid = backendName.trim().length > 0 && /^[a-z0-9_-]+$/.test(backendName.trim());

    return (
        <Dialog open={open} onOpenChange={setOpen}>
            <DialogContent className="w-[420px]">
                <DialogHeader>
                    <DialogTitle>Add Backend</DialogTitle>
                </DialogHeader>

                <div className="space-y-3">
                    <div>
                        <label className="mb-1.5 block font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                            Server
                        </label>
                        <SelectField
                            surface="surface-high"
                            className="w-full"
                            value={selectedId}
                            onChange={(e) => handleServerChange(e.target.value)}
                        >
                            {available.map((s) => (
                                <option key={s.id} value={s.id}>
                                    {s.display_name}
                                </option>
                            ))}
                        </SelectField>
                    </div>
                    <div>
                        <label className="mb-1.5 block font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                            Backend Name
                            <span className="ml-1 font-normal text-text-muted normal-case">(used in proxy config)</span>
                        </label>
                        <input
                            value={backendName}
                            onChange={(e) => setBackendName(e.target.value)}
                            placeholder="e.g. survival"
                            className="w-full rounded border border-border bg-surface-high px-3 py-2 font-mono text-xs text-text-primary focus:border-accent/50 focus:outline-none"
                        />
                        {backendName && !valid && (
                            <p className="mt-1 text-xs text-error">
                                Lowercase letters, numbers, hyphens and underscores only
                            </p>
                        )}
                    </div>
                </div>

                <DialogFooter>
                    <button
                        onClick={handleClose}
                        className="rounded border border-border px-3 py-1.5 font-heading text-xs font-bold tracking-widest text-text-dim uppercase transition-colors hover:border-text-muted"
                    >
                        Cancel
                    </button>
                    <button
                        onClick={() => selected && onAdd(selected, backendName.trim())}
                        disabled={!valid || !selected}
                        className="rounded bg-accent px-3 py-1.5 font-heading text-xs font-bold tracking-widest text-bg uppercase transition-colors hover:bg-accent-bright disabled:opacity-50"
                    >
                        Add
                    </button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
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
    onOpenGeneralSettings,
}: {
    serverId: string;
    networkId: string | null;
    onOpenGeneralSettings?: () => void;
}) {
    const [networkServers, setNetworkServers] = useState<ServerResponse[]>([]);
    const [showAddModal, setShowAddModal] = useState(false);
    const [forwardingWarnings, setForwardingWarnings] = useState<string[]>([]);

    const load = useCallback(async () => {
        const [backendsRes, serversRes] = await Promise.all([getProxyBackends({path: {id: serverId}}), listServers()]);
        const allServers = serversRes.data ?? [];
        setNetworkServers(networkId ? allServers.filter((s) => s.network_id === networkId && s.id !== serverId) : []);
        if (backendsRes.error) {
            return {error: backendsRes.error as {message?: string}};
        }
        const raw = backendsRes.data?.backends ?? [];
        return {
            data: raw.map((b) => {
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
            }),
        };
    }, [serverId, networkId]);

    const {
        draft: backends,
        setDraft: setBackends,
        isDirty,
        loading,
        saving,
        error,
        save,
        discard,
    } = useConfigSection<EditableBackend[]>({
        initial: [],
        load,
        persist: (draft) => {
            const names = draft.map((b) => b.backendName.trim());
            if (new Set(names).size !== names.length) {
                return Promise.resolve({error: {message: "Backend names must be unique"}});
            }
            const body: PutProxyBackendsRequest = {
                backends: draft.map((b) => ({
                    backend_server_id: b.backendServerId,
                    backend_name: b.backendName.trim(),
                    order: b.order,
                })),
            };
            return replaceProxyBackends({path: {id: serverId}, body});
        },
    });

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
        setBackends((prev) => prev.filter((_, i) => i !== index).map((b, i) => ({...b, order: i + 1})));
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

    async function handleSave() {
        const res = await save();
        if (!res.error) {
            setForwardingWarnings(
                (res.data as {forwarding_warnings?: string[]} | undefined)?.forwarding_warnings ?? [],
            );
        }
    }

    if (loading) {
        return <div className="px-4 py-10 text-center text-sm text-text-muted">Loading{"\u2026"}</div>;
    }

    const addedIds = new Set(backends.map((b) => b.backendServerId));
    const available = networkServers.filter((s) => !isProxyType(s.server_type) && !addedIds.has(s.id));

    return (
        <div className="rounded border border-border">
            <div className="flex items-center justify-between border-b border-border bg-surface-high px-4 py-2.5">
                <p className="font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                    Proxy Backends
                </p>
                <button
                    onClick={() => setShowAddModal(true)}
                    disabled={available.length === 0}
                    className="flex items-center gap-1.5 rounded border border-accent/30 bg-accent/10 px-3 py-1.5 font-heading text-xs font-bold tracking-widest text-accent uppercase transition-colors hover:bg-accent/20 disabled:cursor-not-allowed disabled:opacity-40"
                >
                    <Plus className="h-3.5 w-3.5" />
                    Add Backend
                </button>
            </div>
            <div className="space-y-4 px-4 py-3">
                <p className="text-xs text-text-dim">Backend servers routed by this proxy in managed mode.</p>

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

                {!networkId && (
                    <div className="flex items-center justify-between gap-3 rounded border border-warning/30 bg-warning/10 px-3 py-2 text-xs text-warning">
                        <span>This server is not in a network. Assign it to a network to add backends.</span>
                        {onOpenGeneralSettings && (
                            <button
                                onClick={onOpenGeneralSettings}
                                className="shrink-0 font-heading font-bold tracking-wider uppercase underline hover:no-underline"
                            >
                                Assign Network
                            </button>
                        )}
                    </div>
                )}

                {backends.length === 0 ? (
                    <Empty className="rounded border-border py-8 text-xs">
                        <EmptyDescription>No backends configured.</EmptyDescription>
                    </Empty>
                ) : (
                    <div className="overflow-hidden rounded border border-border">
                        <table className="w-full text-xs">
                            <thead>
                                <tr className="border-b border-border bg-surface-high">
                                    <th className="w-8 px-4 py-2.5 text-left font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                                        #
                                    </th>
                                    <th className="px-4 py-2.5 text-left font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                                        Server
                                    </th>
                                    <th className="px-4 py-2.5 text-left font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                                        Backend Name
                                    </th>
                                    <th className="px-4 py-2.5 text-left font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                                        Status
                                    </th>
                                    <th className="w-32 px-4 py-2.5"></th>
                                </tr>
                            </thead>
                            <tbody>
                                {backends.map((b, i) => (
                                    <tr
                                        key={b.backendServerId}
                                        className="border-b border-border transition-colors last:border-0 hover:bg-surface-high/50"
                                    >
                                        <td className="px-4 py-3 font-mono text-text-muted">{b.order}</td>
                                        <td className="px-4 py-3">
                                            <span className="text-text-primary">{b.displayName}</span>
                                            <span className="ml-2 font-mono text-xs text-text-muted">
                                                {b.serverType}
                                            </span>
                                        </td>
                                        <td className="px-4 py-3">
                                            <input
                                                value={b.backendName}
                                                onChange={(e) => renameBackend(i, e.target.value)}
                                                className="w-36 rounded border border-border bg-surface-higher px-2 py-1 font-mono text-xs text-text-primary focus:border-accent/50 focus:outline-none"
                                            />
                                        </td>
                                        <td className="px-4 py-3">
                                            <span
                                                className={`font-heading text-xs font-bold tracking-widest uppercase ${
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
                                            <div className="flex items-center justify-end gap-1">
                                                <button
                                                    onClick={() => moveUp(i)}
                                                    disabled={i === 0}
                                                    className="p-1 text-text-muted transition-colors hover:text-text-primary disabled:opacity-30"
                                                >
                                                    <ChevronUp className="h-3.5 w-3.5" />
                                                </button>
                                                <button
                                                    onClick={() => moveDown(i)}
                                                    disabled={i === backends.length - 1}
                                                    className="p-1 text-text-muted transition-colors hover:text-text-primary disabled:opacity-30"
                                                >
                                                    <ChevronDown className="h-3.5 w-3.5" />
                                                </button>
                                                <button
                                                    onClick={() => removeBackend(i)}
                                                    className="ml-1 p-1 text-text-muted transition-colors hover:text-error"
                                                >
                                                    <Trash2 className="h-3.5 w-3.5" />
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
                    <div className="flex items-center justify-between border-t border-border pt-2">
                        <span className="text-xs text-text-muted">Unsaved changes</span>
                        <div className="flex gap-2">
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
                    </div>
                )}
            </div>

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
