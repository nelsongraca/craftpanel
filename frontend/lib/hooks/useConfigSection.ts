"use client";

import {useCallback, useEffect, useState} from "react";

/** The `{data, error}` envelope shape the generated SDK returns. */
export interface ConfigResult<T> {
    data?: T;
    error?: { message?: string };
}

export interface ConfigSectionOptions<T> {
    /** The draft before any load, and the fallback for a section with no `load`. */
    initial: T;
    /** Loads the persisted value. Must be a stable reference (memoised) — it is an effect dependency. */
    load?: () => Promise<ConfigResult<T>>;
    /** Persists the draft. An `error` sets the section error; a returned `data` is passed back to the caller. */
    persist: (draft: T) => Promise<ConfigResult<unknown>>;
}

export interface ConfigSection<T> {
    draft: T;
    saved: T;
    setDraft: (next: T | ((prev: T) => T)) => void;
    /** True when the draft differs from what was last loaded/saved. */
    isDirty: boolean;
    loading: boolean;
    saving: boolean;
    error: string | null;
    setError: (error: string | null) => void;
    /** Persists the draft, then reloads (when `load` exists) or snapshots it as saved. */
    save: () => Promise<ConfigResult<unknown>>;
    discard: () => void;
}

/**
 * The one owner of a config section's edit lifecycle: load → draft/saved snapshot → dirty tracking →
 * save → discard. Sections keep only their field rendering and the `persist` call; the state machine
 * lives here. A section with no `load` is prop-driven (draft starts at `initial`, and a successful
 * save snapshots the draft).
 */
export function useConfigSection<T>({initial, load, persist}: ConfigSectionOptions<T>): ConfigSection<T> {
    const [draft, setDraft] = useState<T>(initial);
    const [saved, setSaved] = useState<T>(initial);
    const [loading, setLoading] = useState(load != null);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const reload = useCallback(async () => {
        if (!load) return;
        setLoading(true);
        setError(null);
        const res = await load();
        if (res.error) {
            setError(res.error.message ?? "Failed to load");
        } else if (res.data !== undefined) {
            setDraft(res.data);
            setSaved(res.data);
        }
        setLoading(false);
    }, [load]);

    useEffect(() => {
        void reload();
    }, [reload]);

    const save = useCallback(async (): Promise<ConfigResult<unknown>> => {
        setSaving(true);
        setError(null);
        const res = await persist(draft);
        if (res.error) {
            setError(res.error.message ?? "Save failed");
        } else if (load) {
            await reload();
        } else {
            setSaved(draft);
        }
        setSaving(false);
        return res;
    }, [draft, persist, load, reload]);

    const discard = useCallback(() => setDraft(saved), [saved]);

    return {
        draft,
        saved,
        setDraft,
        isDirty: JSON.stringify(draft) !== JSON.stringify(saved),
        loading,
        saving,
        error,
        setError,
        save,
        discard,
    };
}
