"use client";

import {useCallback, useEffect, useMemo, useState} from "react";
import {AlertTriangle, Check, GitCompare, Pin, Plus, RefreshCw, Search, Trash2, X} from "lucide-react";
import {addMod, checkModCompatibility, deleteMod, listMods, searchMods, updateMod} from "@/lib/generated/sdk.gen";
import type {ModResponse as Mod} from "@/lib/generated/types.gen";
import {SelectField} from "@/components/ui/form-elements";
import {Switch} from "@/components/ui/switch";
import {McVersionSelect} from "@/components/ui/mc-version";
import {Empty, EmptyDescription} from "@/components/ui/empty";
import {isModLoaderType, modrinthKind} from "@/lib/server-types";

type PinStrategy = "LATEST" | "PINNED" | "BETA" | "ALPHA";

interface SuggestedPinChange {
    pin_strategy: PinStrategy;
    pinned_version_id?: string | null;
    version_number?: string | null;
}

interface ModCompatibilityResult {
    modrinth_project_id: string;
    display_name: string;
    compatible: boolean;
    latest_compatible_version_id: string | null;
    latest_compatible_version_number: string | null;
    suggested_changes: SuggestedPinChange[];
}

interface CompatibilityCheckResponse {
    target_version: string;
    results: ModCompatibilityResult[];
}

const PIN_LABELS: Record<PinStrategy, string> = {
    LATEST: "Latest stable",
    PINNED: "Pinned version",
    BETA: "Latest beta",
    ALPHA: "Latest alpha",
};

function suggestionLabel(change: SuggestedPinChange): string {
    if (change.pin_strategy === "PINNED") {
        return change.version_number ? `Re-pin to ${change.version_number}` : "Re-pin to a compatible version";
    }
    return `Change pin to ${PIN_LABELS[change.pin_strategy]}`;
}

interface ModrinthVersion {
    id: string;
    version_number: string;
    name: string;
    version_type: "release" | "beta" | "alpha";
    date_published: string;
}

interface ModrinthVersionsResult {
    ok: boolean;
    versions: ModrinthVersion[];
}

async function fetchModrinthVersions(
    projectId: string,
    serverType: string,
    mcVersion: string,
): Promise<ModrinthVersionsResult> {
    try {
        const params = new URLSearchParams();
        // Mod loaders (Fabric/Forge/NeoForge/Quilt) map directly to a loader filter;
        // proxies expose plugins filtered by game version only. Modrinth loader facets are lowercase.
        if (isModLoaderType(serverType)) params.set("loaders", `["${serverType.toLowerCase()}"]`);
        if (mcVersion) params.set("game_versions", `["${mcVersion}"]`);
        const query = params.toString();
        const res = await fetch(`https://api.modrinth.com/v2/project/${projectId}/version${query ? `?${query}` : ""}`);
        if (!res.ok) return {ok: false, versions: []};
        return {ok: true, versions: (await res.json()) as ModrinthVersion[]};
    } catch {
        return {ok: false, versions: []};
    }
}

const ALLOWED_VERSION_TYPES: Record<Exclude<PinStrategy, "PINNED">, string[]> = {
    LATEST: ["release"],
    BETA: ["release", "beta"],
    ALPHA: ["release", "beta", "alpha"],
};

/** Whether a version matching [strategy]'s release-type rule exists for the target, mirroring itzg. */
function channelHasVersion(strategy: PinStrategy, versions: ModrinthVersion[]): boolean {
    if (strategy === "PINNED") return versions.length > 0;
    return versions.some((v) => ALLOWED_VERSION_TYPES[strategy].includes(v.version_type));
}

/** The most stable release channel with any version for the target, or null when none exist. */
function bestAvailableChannel(versions: ModrinthVersion[]): PinStrategy | null {
    if (versions.some((v) => v.version_type === "release")) return "LATEST";
    if (versions.some((v) => v.version_type === "beta")) return "BETA";
    if (versions.some((v) => v.version_type === "alpha")) return "ALPHA";
    return null;
}

interface ModrinthHit {
    project_id: string;
    slug: string;
    title: string;
    description: string;
    author: string;
    downloads: number;
}

export function ModsTab({
    serverId,
    serverType,
    mcVersion,
    onModsChanged,
}: {
    serverId: string;
    serverType: string;
    mcVersion: string;
    onModsChanged?: () => void;
}) {
    const isMod = isModLoaderType(serverType);
    const itemLabel = isMod ? "mod" : "plugin";
    const [mods, setMods] = useState<Mod[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [deleting, setDeleting] = useState<string | null>(null);
    const [toggling, setToggling] = useState<string | null>(null);

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
    const [addVersionsOk, setAddVersionsOk] = useState(false);
    const [loadingAddVersions, setLoadingAddVersions] = useState(false);
    const [addError, setAddError] = useState<string | null>(null);

    // Edit state
    const [editingId, setEditingId] = useState<string | null>(null);
    const [editStrategy, setEditStrategy] = useState<PinStrategy>("LATEST");
    const [editVersionId, setEditVersionId] = useState("");
    const [savingEdit, setSavingEdit] = useState(false);
    const [editVersions, setEditVersions] = useState<ModrinthVersion[]>([]);
    const [loadingEditVersions, setLoadingEditVersions] = useState(false);

    // Compatibility check state
    const [showCompatCheck, setShowCompatCheck] = useState(false);
    const [compatTargetVersion, setCompatTargetVersion] = useState(mcVersion);
    const [compatChecking, setCompatChecking] = useState(false);
    const [compatResults, setCompatResults] = useState<CompatibilityCheckResponse | null>(null);
    const [compatError, setCompatError] = useState<string | null>(null);
    const [compatApplying, setCompatApplying] = useState<string | null>(null);

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

    // Enabled mods first (alphabetical), disabled ones collected at the bottom.
    const sortedMods = useMemo(
        () =>
            [...mods].sort(
                (a, b) =>
                    Number(b.enabled !== false) - Number(a.enabled !== false) ||
                    a.display_name.localeCompare(b.display_name),
            ),
        [mods],
    );

    // Pre-select the newest version when switching to PINNED — including when the version list
    // resolves after the switch (the fetch is kicked off by startAdd).
    useEffect(() => {
        if (adding && addPinStrategy === "PINNED" && !addVersionId && addVersions.length > 0) {
            setAddVersionId(addVersions[0].id);
        }
    }, [adding, addPinStrategy, addVersionId, addVersions]);

    async function handleSearch() {
        if (!searchQuery.trim()) return;
        setSearching(true);
        try {
            const {data} = await searchMods({
                path: {id: serverId},
                // ponytail: serverType added to backend; cast until codegen regenerates SearchModsData
                query: {query: searchQuery, limit: 10, serverType, mcVersion} as {query?: string; limit?: number},
            });
            const body = data as {hits?: ModrinthHit[]} | undefined;
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
        setAddVersionsOk(false);
        setAddError(null);
        void loadAddVersions(hit.project_id);
    }

    async function loadAddVersions(projectId: string) {
        setLoadingAddVersions(true);
        const {ok, versions} = await fetchModrinthVersions(projectId, serverType, mcVersion);
        setAddVersions(versions);
        setAddVersionsOk(ok);
        setLoadingAddVersions(false);
    }

    function handleAddStrategyChange(strategy: PinStrategy) {
        setAddPinStrategy(strategy);
        setAddVersionId("");
    }

    async function confirmAdd() {
        if (!addProjectId || !addDisplayName) return;
        setAddError(null);
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
            setAddError((res.error as {message?: string})?.message ?? "Failed to add mod");
        } else {
            setAdding(null);
            setAddError(null);
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
        if (res.error) setError((res.error as {message?: string})?.message ?? "Failed to remove mod");
        else {
            setMods((prev) => prev.filter((m) => m.id !== modId));
            onModsChanged?.();
        }
        setDeleting(null);
    }

    async function handleToggle(mod: Mod) {
        if (!mod.id) return;
        const nextEnabled = mod.enabled === false;
        setToggling(mod.id);
        const res = await updateMod({path: {id: serverId, modId: mod.id}, body: {enabled: nextEnabled}});
        if (res.error) setError((res.error as {message?: string})?.message ?? "Failed to update mod");
        else {
            setMods((prev) => prev.map((m) => (m.id === mod.id ? {...m, enabled: nextEnabled} : m)));
            onModsChanged?.();
        }
        setToggling(null);
    }

    async function startEdit(mod: Mod) {
        setEditingId(mod.id!);
        const strategy = (mod.pin_strategy as PinStrategy) ?? "LATEST";
        setEditStrategy(strategy);
        setEditVersionId(mod.pinned_version_id ?? "");
        setEditVersions([]);
        if (strategy === "PINNED" && mod.modrinth_project_id) {
            setLoadingEditVersions(true);
            const {versions} = await fetchModrinthVersions(mod.modrinth_project_id, serverType, mcVersion);
            setEditVersions(versions);
            setLoadingEditVersions(false);
        }
    }

    async function handleEditStrategyChange(strategy: PinStrategy, mod: Mod) {
        setEditStrategy(strategy);
        setEditVersionId("");
        if (strategy === "PINNED" && mod.modrinth_project_id && editVersions.length === 0) {
            setLoadingEditVersions(true);
            const {versions} = await fetchModrinthVersions(mod.modrinth_project_id, serverType, mcVersion);
            setEditVersions(versions);
            if (versions.length > 0) setEditVersionId(versions[0].id);
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
        if (res.error) setError((res.error as {message?: string})?.message ?? "Failed to update mod");
        else {
            setEditingId(null);
            await load();
            onModsChanged?.();
        }
        setSavingEdit(false);
    }

    async function handleCompatCheck() {
        if (!compatTargetVersion.trim()) return;
        setCompatChecking(true);
        setCompatError(null);
        setCompatResults(null);
        const res = await checkModCompatibility({
            path: {id: serverId},
            query: {target_version: compatTargetVersion.trim()},
        });
        if (res.error) setCompatError((res.error as {message?: string})?.message ?? "Failed to check compatibility");
        else if (res.data) setCompatResults(res.data as CompatibilityCheckResponse);
        else setCompatError("Failed to check compatibility");
        setCompatChecking(false);
    }

    function dismissCompatCheck() {
        setShowCompatCheck(false);
        setCompatResults(null);
        setCompatError(null);
    }

    async function applyCompatChange(result: ModCompatibilityResult, change: SuggestedPinChange) {
        const mod = mods.find((m) => m.modrinth_project_id === result.modrinth_project_id);
        if (!mod?.id) return;
        setCompatApplying(result.modrinth_project_id);
        setCompatError(null);
        const res = await updateMod({
            path: {id: serverId, modId: mod.id},
            body: {
                pin_strategy: change.pin_strategy,
                pinned_version_id:
                    change.pin_strategy === "PINNED" ? (change.pinned_version_id ?? undefined) : undefined,
            },
        });
        if (res.error) {
            setCompatError((res.error as {message?: string})?.message ?? "Failed to update mod");
        } else {
            await load();
            onModsChanged?.();
            await handleCompatCheck();
        }
        setCompatApplying(null);
    }

    if (loading) {
        return <div className="p-4 text-sm text-text-dim">Loading {itemLabel}s…</div>;
    }

    return (
        <div className="space-y-6 px-4 py-6">
            {error && (
                <div className="rounded border border-error/30 bg-error/10 px-3 py-2 text-sm text-error">{error}</div>
            )}

            {/* Header */}
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <span className="text-sm text-text-dim">
                    {mods.length} {itemLabel}
                    {mods.length !== 1 ? "s" : ""}
                </span>
                <div className="flex flex-wrap gap-2">
                    <button
                        onClick={load}
                        className="flex shrink-0 items-center gap-1.5 rounded border border-border px-3 py-1.5 text-xs whitespace-nowrap text-text-dim transition-colors hover:text-text-primary"
                    >
                        <RefreshCw className="h-3 w-3" />
                        Refresh
                    </button>
                    {mods.length > 0 && (
                        <button
                            onClick={() => setShowCompatCheck(!showCompatCheck)}
                            className="flex shrink-0 items-center gap-1.5 rounded border border-border px-3 py-1.5 text-xs whitespace-nowrap text-text-dim transition-colors hover:text-text-primary"
                        >
                            <GitCompare className="h-3 w-3" />
                            Check Version
                        </button>
                    )}
                    <button
                        onClick={() => setShowSearch(!showSearch)}
                        className="flex shrink-0 items-center gap-1.5 rounded bg-accent px-3 py-1.5 text-xs whitespace-nowrap text-bg transition-colors hover:bg-accent-bright"
                    >
                        <Plus className="h-3 w-3" />
                        Add {isMod ? "Mod" : "Plugin"}
                    </button>
                </div>
            </div>

            {/* Compatibility check */}
            {showCompatCheck && (
                <div className="space-y-3 rounded-lg border border-border bg-surface p-4">
                    <div className="flex items-center gap-2">
                        <div className="min-w-0 flex-1">
                            <McVersionSelect
                                value={compatTargetVersion}
                                onChange={setCompatTargetVersion}
                                placeholder="Target MC version (e.g. 1.22)"
                                fieldSize="sm"
                                surface="bg"
                            />
                        </div>
                        <button
                            onClick={handleCompatCheck}
                            disabled={compatChecking || !compatTargetVersion.trim()}
                            className="flex items-center gap-1.5 rounded bg-accent px-3 py-1.5 text-xs text-bg transition-colors hover:bg-accent-bright disabled:opacity-50"
                        >
                            <Search className="h-3 w-3" />
                            {compatChecking ? "Checking…" : "Check"}
                        </button>
                        <button
                            onClick={dismissCompatCheck}
                            className="flex items-center gap-1.5 rounded border border-border px-3 py-1.5 text-xs text-text-dim transition-colors hover:text-text-primary"
                        >
                            <X className="h-3 w-3" />
                            Cancel
                        </button>
                    </div>

                    {compatError && (
                        <div className="rounded border border-error/30 bg-error/10 px-3 py-2 text-sm text-error">
                            {compatError}
                        </div>
                    )}

                    {compatResults && (
                        <div className="space-y-2">
                            {(() => {
                                const total = compatResults.results.length;
                                const compatibleCount = compatResults.results.filter((r) => r.compatible).length;
                                const changeCount = compatResults.results.filter(
                                    (r) => !r.compatible && (r.suggested_changes?.length ?? 0) > 0,
                                ).length;
                                const incompatibleCount = total - compatibleCount - changeCount;
                                const summaryClass =
                                    incompatibleCount > 0
                                        ? "text-error"
                                        : changeCount > 0
                                          ? "text-warning"
                                          : "text-healthy";
                                return (
                                    <div className={`text-sm ${summaryClass}`}>
                                        {compatibleCount}/{total} {itemLabel}
                                        {total !== 1 ? "s" : ""} compatible with {compatResults.target_version}
                                        {changeCount > 0 && (
                                            <span className="text-warning">
                                                {" "}
                                                · {changeCount} need{changeCount === 1 ? "s" : ""} a pin change
                                            </span>
                                        )}
                                        {incompatibleCount > 0 && (
                                            <span className="text-error"> · {incompatibleCount} incompatible</span>
                                        )}
                                    </div>
                                );
                            })()}
                            <div className="max-h-64 space-y-2 overflow-y-auto">
                                {compatResults.results.map((r) => {
                                    const suggestions = r.suggested_changes ?? [];
                                    const primary = suggestions[0];
                                    const alternative = suggestions[1];
                                    const needsChange = !r.compatible && primary !== undefined;
                                    const applying = compatApplying === r.modrinth_project_id;
                                    return (
                                        <div
                                            key={r.modrinth_project_id}
                                            className="flex items-center justify-between gap-3 rounded border border-border bg-bg p-2"
                                        >
                                            <div className="flex min-w-0 items-center gap-2">
                                                {r.compatible ? (
                                                    <Check className="h-3.5 w-3.5 shrink-0 text-healthy" />
                                                ) : needsChange ? (
                                                    <AlertTriangle className="h-3.5 w-3.5 shrink-0 text-warning" />
                                                ) : (
                                                    <X className="h-3.5 w-3.5 shrink-0 text-error" />
                                                )}
                                                <span className="truncate text-sm text-text-primary">
                                                    {r.display_name}
                                                </span>
                                            </div>
                                            <div className="flex shrink-0 items-center gap-2">
                                                {r.compatible ? (
                                                    <span className="text-xs text-healthy">
                                                        Compatible with {compatResults.target_version}
                                                    </span>
                                                ) : needsChange && primary ? (
                                                    <>
                                                        <span className="text-xs text-warning">
                                                            {suggestionLabel(primary)}
                                                        </span>
                                                        <button
                                                            onClick={() => applyCompatChange(r, primary)}
                                                            disabled={applying}
                                                            className="rounded border border-warning/40 px-2 py-0.5 text-[11px] text-warning transition-colors hover:bg-warning/10 disabled:opacity-50"
                                                        >
                                                            {applying ? "Applying…" : "Apply"}
                                                        </button>
                                                        {alternative && (
                                                            <button
                                                                onClick={() => applyCompatChange(r, alternative)}
                                                                disabled={applying}
                                                                className="rounded border border-border px-2 py-0.5 text-[11px] text-text-dim transition-colors hover:text-text-primary disabled:opacity-50"
                                                            >
                                                                Switch to {PIN_LABELS[alternative.pin_strategy]}
                                                            </button>
                                                        )}
                                                    </>
                                                ) : (
                                                    <span className="text-xs text-error">Not compatible</span>
                                                )}
                                            </div>
                                        </div>
                                    );
                                })}
                            </div>
                        </div>
                    )}
                </div>
            )}

            {/* Modrinth search */}
            {showSearch && (
                <div className="space-y-3 rounded-lg border border-border bg-surface p-4">
                    <div className="flex gap-2">
                        <input
                            value={searchQuery}
                            onChange={(e) => setSearchQuery(e.target.value)}
                            onKeyDown={(e) => e.key === "Enter" && handleSearch()}
                            placeholder="Search Modrinth…"
                            className="flex-1 rounded border border-border bg-bg px-3 py-1.5 text-sm text-text-primary placeholder:text-text-muted focus:border-accent focus:outline-none"
                        />
                        <button
                            onClick={handleSearch}
                            disabled={searching}
                            className="flex items-center gap-1.5 rounded bg-accent px-3 py-1.5 text-xs text-bg transition-colors hover:bg-accent-bright disabled:opacity-50"
                        >
                            <Search className="h-3 w-3" />
                            {searching ? "Searching…" : "Search"}
                        </button>
                    </div>

                    {searchResults.length > 0 && (
                        <div className="max-h-64 space-y-2 overflow-y-auto">
                            {searchResults.map((hit) => {
                                const alreadyAdded = mods.some((m) => m.modrinth_project_id === hit.project_id);
                                // Warn before the round-trip when the selected channel has no compatible
                                // version but another one does (e.g. only an alpha build exists).
                                const bestChannel = bestAvailableChannel(addVersions);
                                const showChannelHint =
                                    addPinStrategy !== "PINNED" &&
                                    addVersionsOk &&
                                    addVersions.length > 0 &&
                                    !channelHasVersion(addPinStrategy, addVersions);
                                return (
                                    <div
                                        key={hit.project_id}
                                        className="flex items-start justify-between gap-3 rounded border border-border bg-bg p-2"
                                    >
                                        <div className="min-w-0 flex-1">
                                            <a
                                                href={`https://modrinth.com/${modrinthKind(serverType)}/${hit.slug}`}
                                                target="_blank"
                                                rel="noopener noreferrer"
                                                className="block truncate text-sm font-medium text-text-primary hover:text-accent hover:underline"
                                            >
                                                {hit.title}
                                            </a>
                                            <a
                                                href={`https://modrinth.com/${modrinthKind(serverType)}/${hit.slug}`}
                                                target="_blank"
                                                rel="noopener noreferrer"
                                                className="block truncate text-xs text-text-muted hover:text-accent"
                                            >
                                                {hit.description}
                                            </a>
                                            <div className="mt-0.5 text-xs text-text-muted">
                                                by {hit.author} · {hit.downloads.toLocaleString()} downloads
                                            </div>
                                        </div>
                                        {alreadyAdded ? (
                                            <span className="mt-1 shrink-0 text-xs text-text-muted">Added</span>
                                        ) : adding === hit.project_id ? (
                                            <div className="min-w-48 shrink-0 space-y-2">
                                                <SelectField
                                                    surface="surface"
                                                    fieldSize="sm"
                                                    className="w-full"
                                                    value={addPinStrategy}
                                                    onChange={(e) =>
                                                        handleAddStrategyChange(e.target.value as PinStrategy)
                                                    }
                                                >
                                                    {(Object.keys(PIN_LABELS) as PinStrategy[]).map((s) => (
                                                        <option key={s} value={s}>
                                                            {PIN_LABELS[s]}
                                                        </option>
                                                    ))}
                                                </SelectField>
                                                {addPinStrategy === "PINNED" &&
                                                    (loadingAddVersions ? (
                                                        <div className="text-xs text-text-muted">Loading versions…</div>
                                                    ) : addVersions.length > 0 ? (
                                                        <SelectField
                                                            surface="bg"
                                                            fieldSize="sm"
                                                            className="w-full"
                                                            value={addVersionId}
                                                            onChange={(e) => setAddVersionId(e.target.value)}
                                                        >
                                                            {addVersions.map((v) => (
                                                                <option key={v.id} value={v.id}>
                                                                    {v.version_number} ({v.version_type})
                                                                </option>
                                                            ))}
                                                        </SelectField>
                                                    ) : (
                                                        <input
                                                            value={addVersionId}
                                                            onChange={(e) => setAddVersionId(e.target.value)}
                                                            placeholder="Version ID or number"
                                                            className="w-full rounded border border-border bg-bg px-2 py-1 text-xs text-text-primary focus:border-accent focus:outline-none"
                                                        />
                                                    ))}
                                                {showChannelHint && (
                                                    <p className="text-xs text-warning">
                                                        No {PIN_LABELS[addPinStrategy]} version for{" "}
                                                        {mcVersion || "this version"}.
                                                        {bestChannel && bestChannel !== addPinStrategy && (
                                                            <>
                                                                {" "}
                                                                <button
                                                                    type="button"
                                                                    onClick={() => handleAddStrategyChange(bestChannel)}
                                                                    className="underline hover:no-underline"
                                                                >
                                                                    Switch to {PIN_LABELS[bestChannel]}?
                                                                </button>
                                                            </>
                                                        )}
                                                    </p>
                                                )}
                                                <div className="flex gap-1">
                                                    <button
                                                        onClick={confirmAdd}
                                                        disabled={addPinStrategy === "PINNED" && !addVersionId}
                                                        className="flex-1 rounded bg-accent px-2 py-1 text-xs text-bg transition-colors hover:bg-accent-bright disabled:opacity-50"
                                                    >
                                                        Add
                                                    </button>
                                                    <button
                                                        onClick={() => {
                                                            setAdding(null);
                                                            setAddError(null);
                                                        }}
                                                        className="rounded border border-border px-2 py-1 text-xs text-text-dim hover:text-text-primary"
                                                    >
                                                        ✕
                                                    </button>
                                                </div>
                                                {addError && <p className="text-xs text-error">{addError}</p>}
                                            </div>
                                        ) : (
                                            <button
                                                onClick={() => startAdd(hit)}
                                                className="shrink-0 rounded border border-accent px-2 py-1 text-xs text-accent transition-colors hover:bg-accent/10"
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
                <Empty>
                    <EmptyDescription>No {itemLabel}s installed</EmptyDescription>
                </Empty>
            ) : (
                <div className="space-y-2">
                    {sortedMods.map((mod) => (
                        <div
                            key={mod.id}
                            className={`rounded-lg border border-border bg-surface px-4 py-3 ${
                                mod.enabled === false ? "opacity-60" : ""
                            }`}
                        >
                            <div className="flex items-center justify-between">
                                <div className="min-w-0 flex-1">
                                    <div className="flex items-center gap-2">
                                        <a
                                            href={`https://modrinth.com/${modrinthKind(serverType)}/${mod.modrinth_project_id}`}
                                            target="_blank"
                                            rel="noopener noreferrer"
                                            className="truncate text-sm font-medium text-text-primary hover:text-accent hover:underline"
                                        >
                                            {mod.display_name}
                                        </a>
                                        {mod.enabled === false && (
                                            <span className="shrink-0 rounded bg-surface-higher px-1.5 py-0.5 text-xs text-text-muted">
                                                Disabled
                                            </span>
                                        )}
                                        <span className="shrink-0 font-mono text-xs text-text-muted">
                                            {mod.modrinth_project_id}
                                        </span>
                                    </div>
                                    {editingId !== mod.id && (
                                        <div className="mt-0.5 text-xs text-text-dim">
                                            {mod.pin_strategy === "PINNED"
                                                ? `Pinned: ${mod.pinned_version_id}`
                                                : (PIN_LABELS[mod.pin_strategy as PinStrategy] ?? mod.pin_strategy)}
                                        </div>
                                    )}
                                </div>
                                <div className="ml-3 flex shrink-0 items-center gap-2">
                                    <Switch
                                        checked={mod.enabled !== false}
                                        onCheckedChange={() => void handleToggle(mod)}
                                        disabled={toggling === mod.id}
                                        aria-label={`${mod.enabled === false ? "Enable" : "Disable"} ${mod.display_name}`}
                                    />
                                    {editingId !== mod.id && (
                                        <button
                                            onClick={() => startEdit(mod)}
                                            className="rounded p-1.5 text-text-muted transition-colors hover:text-text-primary"
                                            title="Change pin strategy"
                                        >
                                            <Pin className="h-3.5 w-3.5" />
                                        </button>
                                    )}
                                    <button
                                        onClick={() => handleDelete(mod.id!)}
                                        disabled={deleting === mod.id}
                                        className="rounded p-1.5 text-text-muted transition-colors hover:text-error disabled:opacity-50"
                                        title="Remove mod"
                                    >
                                        <Trash2 className="h-3.5 w-3.5" />
                                    </button>
                                </div>
                            </div>

                            {/* Inline edit */}
                            {editingId === mod.id && (
                                <div className="mt-2 flex flex-wrap items-center gap-2">
                                    <SelectField
                                        surface="bg"
                                        fieldSize="sm"
                                        value={editStrategy}
                                        onChange={(e) =>
                                            void handleEditStrategyChange(e.target.value as PinStrategy, mod)
                                        }
                                    >
                                        {(Object.keys(PIN_LABELS) as PinStrategy[]).map((s) => (
                                            <option key={s} value={s}>
                                                {PIN_LABELS[s]}
                                            </option>
                                        ))}
                                    </SelectField>
                                    {editStrategy === "PINNED" &&
                                        (loadingEditVersions ? (
                                            <span className="text-xs text-text-muted">Loading versions…</span>
                                        ) : editVersions.length > 0 ? (
                                            <SelectField
                                                surface="bg"
                                                fieldSize="sm"
                                                value={editVersionId}
                                                onChange={(e) => setEditVersionId(e.target.value)}
                                            >
                                                {editVersions.map((v) => (
                                                    <option key={v.id} value={v.id}>
                                                        {v.version_number} ({v.version_type})
                                                    </option>
                                                ))}
                                            </SelectField>
                                        ) : (
                                            <input
                                                value={editVersionId}
                                                onChange={(e) => setEditVersionId(e.target.value)}
                                                placeholder="Version ID or number"
                                                className="w-36 rounded border border-border bg-bg px-2 py-1 text-xs text-text-primary focus:border-accent focus:outline-none"
                                            />
                                        ))}
                                    <button
                                        onClick={() => saveEdit(mod.id!)}
                                        disabled={savingEdit || (editStrategy === "PINNED" && !editVersionId)}
                                        className="rounded bg-accent px-2 py-1 text-xs text-bg transition-colors hover:bg-accent-bright disabled:opacity-50"
                                    >
                                        {savingEdit ? "Saving…" : "Save"}
                                    </button>
                                    <button
                                        onClick={() => setEditingId(null)}
                                        className="rounded border border-border px-2 py-1 text-xs text-text-dim hover:text-text-primary"
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
