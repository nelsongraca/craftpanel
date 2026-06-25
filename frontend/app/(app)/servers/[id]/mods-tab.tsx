"use client";

import {useCallback, useEffect, useState} from "react";
import {Pin, Plus, RefreshCw, Search, Trash2} from "lucide-react";
import {addMod, deleteMod, listMods, searchMods, updateMod} from "@/lib/generated/sdk.gen";
import type {ModResponse as Mod} from "@/lib/generated/types.gen";

type PinStrategy = "LATEST" | "PINNED" | "BETA" | "ALPHA";

const PIN_LABELS: Record<PinStrategy, string> = {
    LATEST: "Latest stable",
    PINNED: "Pinned version",
    BETA: "Latest beta",
    ALPHA: "Latest alpha",
};

interface ModrinthVersion {
    id: string;
    version_number: string;
    name: string;
    version_type: "release" | "beta" | "alpha";
    date_published: string;
}

async function fetchModrinthVersions(projectId: string): Promise<ModrinthVersion[]> {
    try {
        const res = await fetch(`https://api.modrinth.com/v2/project/${projectId}/version`);
        if (!res.ok) return [];
        return await res.json() as ModrinthVersion[];
    } catch {
        return [];
    }
}

interface ModrinthHit {
    project_id: string;
    title: string;
    description: string;
    author: string;
    downloads: number;
}

const MOD_SERVER_TYPES = new Set(["FABRIC", "FORGE", "NEOFORGE", "QUILT"]);

export function ModsTab({serverId, serverType, mcVersion, onModsChanged}: { serverId: string; serverType: string; mcVersion: string; onModsChanged?: () => void }) {
    const isMod = MOD_SERVER_TYPES.has(serverType.toUpperCase());
    const itemLabel = isMod ? "mod" : "plugin";
    const [mods, setMods] = useState<Mod[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [deleting, setDeleting] = useState<string | null>(null);

    // Search state
    const [searchQuery, setSearchQuery] = useState("");
    const [searchResults, setSearchResults] = useState<ModrinthHit[]>([]);
    const [searching, setSearching] = useState(false);
    const [showSearch, setShowSearch] = useState(false);

    // Add mod state
    const [adding, setAdding] = useState<string | null>(null);
    const [addPinStrategy, setAddPinStrategy] = useState<PinStrategy>("LATEST");
    const [addVersionId, setAddVersionId] = useState("");
    const [addDisplayName, setAddDisplayName] = useState("");
    const [addProjectId, setAddProjectId] = useState("");
    const [addVersions, setAddVersions] = useState<ModrinthVersion[]>([]);
    const [loadingAddVersions, setLoadingAddVersions] = useState(false);

    // Edit state
    const [editingId, setEditingId] = useState<string | null>(null);
    const [editStrategy, setEditStrategy] = useState<PinStrategy>("LATEST");
    const [editVersionId, setEditVersionId] = useState("");
    const [savingEdit, setSavingEdit] = useState(false);
    const [editVersions, setEditVersions] = useState<ModrinthVersion[]>([]);
    const [loadingEditVersions, setLoadingEditVersions] = useState(false);

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        const res = await listMods({path: {id: serverId}});
        if (res.data) setMods(res.data.mods ?? []);
        else setError("Failed to load mods");
        setLoading(false);
    }, [serverId]);

    useEffect(() => {
        load();
    }, [load]);

    async function handleSearch() {
        if (!searchQuery.trim()) return;
        setSearching(true);
        try {
            const {data} = await searchMods({
                path: {id: serverId},
                // ponytail: serverType added to backend; cast until codegen regenerates SearchModsData
                query: {query: searchQuery, limit: 10, serverType, mcVersion} as { query?: string; limit?: number }
            });
            const body = data as { hits?: ModrinthHit[] } | undefined;
            setSearchResults(body?.hits ?? []);
        } catch {
            // ignore network errors silently
        }
        setSearching(false);
    }

    function startAdd(hit: ModrinthHit) {
        setAddProjectId(hit.project_id);
        setAddDisplayName(hit.title);
        setAdding(hit.project_id);
        setAddPinStrategy("LATEST");
        setAddVersionId("");
        setAddVersions([]);
    }

    async function handleAddStrategyChange(strategy: PinStrategy, projectId: string) {
        setAddPinStrategy(strategy);
        setAddVersionId("");
        if (strategy === "PINNED" && addVersions.length === 0) {
            setLoadingAddVersions(true);
            const vs = await fetchModrinthVersions(projectId);
            setAddVersions(vs);
            if (vs.length > 0) setAddVersionId(vs[0].id);
            setLoadingAddVersions(false);
        }
    }

    async function confirmAdd() {
        if (!addProjectId || !addDisplayName) return;
        const res = await addMod({
            path: {id: serverId},
            body: {
                modrinth_project_id: addProjectId,
                display_name: addDisplayName,
                pin_strategy: addPinStrategy,
                pinned_version_id: addPinStrategy === "PINNED" ? addVersionId : undefined,
            },
        });
        if (res.error) {
            setError((res.error as { message?: string })?.message ?? "Failed to add mod");
        } else {
            setAdding(null);
            setShowSearch(false);
            setSearchResults([]);
            setSearchQuery("");
            await load();
            onModsChanged?.();
        }
    }

    async function handleDelete(modId: string) {
        setDeleting(modId);
        const res = await deleteMod({path: {id: serverId, modId}});
        if (res.error) setError((res.error as { message?: string })?.message ?? "Failed to remove mod");
        else {
            setMods((prev) => prev.filter((m) => m.id !== modId));
            onModsChanged?.();
        }
        setDeleting(null);
    }

    async function startEdit(mod: Mod) {
        setEditingId(mod.id!);
        const strategy = (mod.pin_strategy as PinStrategy) ?? "LATEST";
        setEditStrategy(strategy);
        setEditVersionId(mod.pinned_version_id ?? "");
        setEditVersions([]);
        if (strategy === "PINNED" && mod.modrinth_project_id) {
            setLoadingEditVersions(true);
            const vs = await fetchModrinthVersions(mod.modrinth_project_id);
            setEditVersions(vs);
            setLoadingEditVersions(false);
        }
    }

    async function handleEditStrategyChange(strategy: PinStrategy, mod: Mod) {
        setEditStrategy(strategy);
        setEditVersionId("");
        if (strategy === "PINNED" && mod.modrinth_project_id && editVersions.length === 0) {
            setLoadingEditVersions(true);
            const vs = await fetchModrinthVersions(mod.modrinth_project_id);
            setEditVersions(vs);
            if (vs.length > 0) setEditVersionId(vs[0].id);
            setLoadingEditVersions(false);
        }
    }

    async function saveEdit(modId: string) {
        setSavingEdit(true);
        const res = await updateMod({
            path: {id: serverId, modId},
            body: {
                pin_strategy: editStrategy,
                pinned_version_id: editStrategy === "PINNED" ? editVersionId : undefined,
            },
        });
        if (res.error) setError((res.error as { message?: string })?.message ?? "Failed to update mod");
        else {
            setEditingId(null);
            await load();
            onModsChanged?.();
        }
        setSavingEdit(false);
    }

    if (loading) {
        return <div className="text-text-dim text-sm p-4">Loading {itemLabel}s…</div>;
    }

    return (
        <div className="px-6 py-6 space-y-6">
            {error && (
                <div className="text-error text-sm bg-error/10 border border-error/30 rounded px-3 py-2">{error}</div>
            )}

            {/* Header */}
            <div className="flex items-center justify-between">
                <span className="text-sm text-text-dim">{mods.length} {itemLabel}{mods.length !== 1 ? "s" : ""}</span>
                <div className="flex gap-2">
                    <button
                        onClick={load}
                        className="flex items-center gap-1.5 px-3 py-1.5 border border-border rounded text-xs text-text-dim hover:text-text-primary transition-colors"
                    >
                        <RefreshCw className="w-3 h-3"/>
                        Refresh
                    </button>
                    <button
                        onClick={() => setShowSearch(!showSearch)}
                        className="flex items-center gap-1.5 px-3 py-1.5 bg-accent text-bg text-xs rounded hover:bg-accent-bright transition-colors"
                    >
                        <Plus className="w-3 h-3"/>
                        Add {isMod ? "Mod" : "Plugin"}
                    </button>
                </div>
            </div>

            {/* Modrinth search */}
            {showSearch && (
                <div className="bg-surface border border-border rounded-lg p-4 space-y-3">
                    <div className="flex gap-2">
                        <input
                            value={searchQuery}
                            onChange={(e) => setSearchQuery(e.target.value)}
                            onKeyDown={(e) => e.key === "Enter" && handleSearch()}
                            placeholder="Search Modrinth…"
                            className="flex-1 bg-bg border border-border rounded px-3 py-1.5 text-sm text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent"
                        />
                        <button
                            onClick={handleSearch}
                            disabled={searching}
                            className="flex items-center gap-1.5 px-3 py-1.5 bg-accent text-bg text-xs rounded hover:bg-accent-bright transition-colors disabled:opacity-50"
                        >
                            <Search className="w-3 h-3"/>
                            {searching ? "Searching…" : "Search"}
                        </button>
                    </div>

                    {searchResults.length > 0 && (
                        <div className="space-y-2 max-h-64 overflow-y-auto">
                            {searchResults.map((hit) => {
                                const alreadyAdded = mods.some((m) => m.modrinth_project_id === hit.project_id);
                                return (
                                    <div key={hit.project_id} className="flex items-start justify-between gap-3 p-2 rounded border border-border bg-bg">
                                        <div className="min-w-0 flex-1">
                                            <div className="text-sm font-medium text-text-primary truncate">{hit.title}</div>
                                            <div className="text-xs text-text-muted truncate">{hit.description}</div>
                                            <div className="text-xs text-text-muted mt-0.5">by {hit.author} · {hit.downloads.toLocaleString()} downloads</div>
                                        </div>
                                        {alreadyAdded ? (
                                            <span className="text-xs text-text-muted shrink-0 mt-1">Added</span>
                                        ) : adding === hit.project_id ? (
                                            <div className="shrink-0 space-y-2 min-w-48">
                                                <select
                                                    value={addPinStrategy}
                                                    onChange={(e) => void handleAddStrategyChange(e.target.value as PinStrategy, hit.project_id)}
                                                    className="w-full bg-surface border border-border rounded px-2 py-1 text-xs text-text-primary focus:outline-none focus:border-accent"
                                                >
                                                    {(Object.keys(PIN_LABELS) as PinStrategy[]).map((s) => (
                                                        <option key={s} value={s}>{PIN_LABELS[s]}</option>
                                                    ))}
                                                </select>
                                                {addPinStrategy === "PINNED" && (
                                                    loadingAddVersions ? (
                                                        <div className="text-xs text-text-muted">Loading versions…</div>
                                                    ) : addVersions.length > 0 ? (
                                                        <select
                                                            value={addVersionId}
                                                            onChange={(e) => setAddVersionId(e.target.value)}
                                                            className="w-full bg-bg border border-border rounded px-2 py-1 text-xs text-text-primary focus:outline-none focus:border-accent"
                                                        >
                                                            {addVersions.map((v) => (
                                                                <option key={v.id} value={v.id}>
                                                                    {v.version_number} ({v.version_type})
                                                                </option>
                                                            ))}
                                                        </select>
                                                    ) : (
                                                        <input
                                                            value={addVersionId}
                                                            onChange={(e) => setAddVersionId(e.target.value)}
                                                            placeholder="Version ID"
                                                            className="w-full bg-bg border border-border rounded px-2 py-1 text-xs text-text-primary focus:outline-none focus:border-accent"
                                                        />
                                                    )
                                                )}
                                                <div className="flex gap-1">
                                                    <button
                                                        onClick={confirmAdd}
                                                        disabled={addPinStrategy === "PINNED" && !addVersionId}
                                                        className="flex-1 px-2 py-1 bg-accent text-bg text-xs rounded hover:bg-accent-bright transition-colors disabled:opacity-50"
                                                    >
                                                        Add
                                                    </button>
                                                    <button
                                                        onClick={() => setAdding(null)}
                                                        className="px-2 py-1 border border-border text-text-dim text-xs rounded hover:text-text-primary"
                                                    >
                                                        ✕
                                                    </button>
                                                </div>
                                            </div>
                                        ) : (
                                            <button
                                                onClick={() => startAdd(hit)}
                                                className="shrink-0 px-2 py-1 border border-accent text-accent text-xs rounded hover:bg-accent/10 transition-colors"
                                            >
                                                Add
                                            </button>
                                        )}
                                    </div>
                                );
                            })}
                        </div>
                    )}
                </div>
            )}

            {/* Mod list */}
            {mods.length === 0 ? (
                <div className="text-center text-text-muted text-sm py-8 border border-border rounded-lg bg-surface">
                    No {itemLabel}s installed
                </div>
            ) : (
                <div className="space-y-2">
                    {mods.map((mod) => (
                        <div key={mod.id} className="bg-surface border border-border rounded-lg px-4 py-3">
                            <div className="flex items-center justify-between">
                                <div className="min-w-0 flex-1">
                                    <div className="flex items-center gap-2">
                                        <span className="text-sm font-medium text-text-primary truncate">{mod.display_name}</span>
                                        <span className="text-xs text-text-muted font-mono shrink-0">{mod.modrinth_project_id}</span>
                                    </div>
                                    {editingId !== mod.id && (
                                        <div className="text-xs text-text-dim mt-0.5">
                                            {mod.pin_strategy === "PINNED"
                                                ? `Pinned: ${mod.pinned_version_id}`
                                                : PIN_LABELS[mod.pin_strategy as PinStrategy] ?? mod.pin_strategy}
                                        </div>
                                    )}
                                </div>
                                <div className="flex items-center gap-2 shrink-0 ml-3">
                                    {editingId !== mod.id && (
                                        <button
                                            onClick={() => startEdit(mod)}
                                            className="p-1.5 rounded text-text-muted hover:text-text-primary transition-colors"
                                            title="Change pin strategy"
                                        >
                                            <Pin className="w-3.5 h-3.5"/>
                                        </button>
                                    )}
                                    <button
                                        onClick={() => handleDelete(mod.id!)}
                                        disabled={deleting === mod.id}
                                        className="p-1.5 rounded text-text-muted hover:text-error transition-colors disabled:opacity-50"
                                        title="Remove mod"
                                    >
                                        <Trash2 className="w-3.5 h-3.5"/>
                                    </button>
                                </div>
                            </div>

                            {/* Inline edit */}
                            {editingId === mod.id && (
                                <div className="mt-2 flex items-center gap-2 flex-wrap">
                                    <select
                                        value={editStrategy}
                                        onChange={(e) => void handleEditStrategyChange(e.target.value as PinStrategy, mod)}
                                        className="bg-bg border border-border rounded px-2 py-1 text-xs text-text-primary focus:outline-none focus:border-accent"
                                    >
                                        {(Object.keys(PIN_LABELS) as PinStrategy[]).map((s) => (
                                            <option key={s} value={s}>{PIN_LABELS[s]}</option>
                                        ))}
                                    </select>
                                    {editStrategy === "PINNED" && (
                                        loadingEditVersions ? (
                                            <span className="text-xs text-text-muted">Loading versions…</span>
                                        ) : editVersions.length > 0 ? (
                                            <select
                                                value={editVersionId}
                                                onChange={(e) => setEditVersionId(e.target.value)}
                                                className="bg-bg border border-border rounded px-2 py-1 text-xs text-text-primary focus:outline-none focus:border-accent"
                                            >
                                                {editVersions.map((v) => (
                                                    <option key={v.id} value={v.id}>
                                                        {v.version_number} ({v.version_type})
                                                    </option>
                                                ))}
                                            </select>
                                        ) : (
                                            <input
                                                value={editVersionId}
                                                onChange={(e) => setEditVersionId(e.target.value)}
                                                placeholder="Version ID"
                                                className="bg-bg border border-border rounded px-2 py-1 text-xs text-text-primary focus:outline-none focus:border-accent w-36"
                                            />
                                        )
                                    )}
                                    <button
                                        onClick={() => saveEdit(mod.id!)}
                                        disabled={savingEdit || (editStrategy === "PINNED" && !editVersionId)}
                                        className="px-2 py-1 bg-accent text-bg text-xs rounded hover:bg-accent-bright transition-colors disabled:opacity-50"
                                    >
                                        {savingEdit ? "Saving…" : "Save"}
                                    </button>
                                    <button
                                        onClick={() => setEditingId(null)}
                                        className="px-2 py-1 border border-border text-text-dim text-xs rounded hover:text-text-primary"
                                    >
                                        Cancel
                                    </button>
                                </div>
                            )}
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
