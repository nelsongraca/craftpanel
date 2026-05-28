"use client";

import {useCallback, useEffect, useState} from "react";
import {Pin, Plus, RefreshCw, Search, Trash2} from "lucide-react";
import {addMod, deleteMod, listMods, updateMod} from "@/lib/generated/sdk.gen";
import type {IoCraftpanelMasterServiceModResponse as Mod} from "@/lib/generated/types.gen";

type PinStrategy = "LATEST" | "PINNED" | "BETA" | "ALPHA";

const PIN_LABELS: Record<PinStrategy, string> = {
    LATEST: "Latest stable",
    PINNED: "Pinned version",
    BETA: "Latest beta",
    ALPHA: "Latest alpha",
};

interface ModrinthHit {
    project_id: string;
    title: string;
    description: string;
    author: string;
    downloads: number;
}

export function ModsTab({serverId}: { serverId: string }) {
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

    // Edit state
    const [editingId, setEditingId] = useState<string | null>(null);
    const [editStrategy, setEditStrategy] = useState<PinStrategy>("LATEST");
    const [editVersionId, setEditVersionId] = useState("");
    const [savingEdit, setSavingEdit] = useState(false);

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
            const params = new URLSearchParams({query: searchQuery, limit: "10"});
            const res = await fetch(`/api/servers/${serverId}/mods/search?${params}`);
            if (res.ok) {
                const body = (await res.json()) as { hits?: ModrinthHit[] };
                setSearchResults(body.hits ?? []);
            }
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
        }
    }

    async function handleDelete(modId: string) {
        setDeleting(modId);
        const res = await deleteMod({path: {id: serverId, modId}});
        if (res.error) setError((res.error as { message?: string })?.message ?? "Failed to remove mod");
        else setMods((prev) => prev.filter((m) => m.id !== modId));
        setDeleting(null);
    }

    function startEdit(mod: Mod) {
        setEditingId(mod.id!);
        setEditStrategy((mod.pin_strategy as PinStrategy) ?? "LATEST");
        setEditVersionId(mod.pinned_version_id ?? "");
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
        }
        setSavingEdit(false);
    }

    if (loading) {
        return <div className="text-text-dim text-sm p-4">Loading mods…</div>;
    }

    return (
        <div className="space-y-4">
            {error && (
                <div className="text-error text-sm bg-error/10 border border-error/30 rounded px-3 py-2">{error}</div>
            )}

            {/* Header */}
            <div className="flex items-center justify-between">
                <span className="text-sm text-text-dim">{mods.length} mod{mods.length !== 1 ? "s" : ""}</span>
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
                        Add Mod
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
                                            <div className="shrink-0 space-y-2 min-w-40">
                                                <select
                                                    value={addPinStrategy}
                                                    onChange={(e) => setAddPinStrategy(e.target.value as PinStrategy)}
                                                    className="w-full bg-surface border border-border rounded px-2 py-1 text-xs text-text-primary focus:outline-none focus:border-accent"
                                                >
                                                    {(Object.keys(PIN_LABELS) as PinStrategy[]).map((s) => (
                                                        <option key={s} value={s}>{PIN_LABELS[s]}</option>
                                                    ))}
                                                </select>
                                                {addPinStrategy === "PINNED" && (
                                                    <input
                                                        value={addVersionId}
                                                        onChange={(e) => setAddVersionId(e.target.value)}
                                                        placeholder="Version ID"
                                                        className="w-full bg-bg border border-border rounded px-2 py-1 text-xs text-text-primary focus:outline-none focus:border-accent"
                                                    />
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
                    No mods installed
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
                                        onChange={(e) => setEditStrategy(e.target.value as PinStrategy)}
                                        className="bg-bg border border-border rounded px-2 py-1 text-xs text-text-primary focus:outline-none focus:border-accent"
                                    >
                                        {(Object.keys(PIN_LABELS) as PinStrategy[]).map((s) => (
                                            <option key={s} value={s}>{PIN_LABELS[s]}</option>
                                        ))}
                                    </select>
                                    {editStrategy === "PINNED" && (
                                        <input
                                            value={editVersionId}
                                            onChange={(e) => setEditVersionId(e.target.value)}
                                            placeholder="Version ID"
                                            className="bg-bg border border-border rounded px-2 py-1 text-xs text-text-primary focus:outline-none focus:border-accent w-36"
                                        />
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
