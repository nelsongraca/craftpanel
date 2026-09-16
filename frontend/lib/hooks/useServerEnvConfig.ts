"use client";

import {useCallback, useEffect, useState} from "react";
import {getEnvVars, replaceEnvVars} from "@/lib/generated/sdk.gen";
import type {EnvVarItem} from "@/lib/types";

/**
 * The subset of a config field the editor needs: the env-var key and whether a blank value should
 * be omitted from the save. Structurally satisfied by `FieldDef` from the config schema, so this
 * hook stays free of any `components/` import.
 */
export interface EnvField {
    key: string;
    omitIfEmpty?: boolean;
}

export interface ServerEnvConfig {
    /** Values of the schema-known fields, keyed by env-var key. */
    form: Record<string, string>;
    setField: (key: string, value: string) => void;
    /** Every env var that is not a schema key, in load order. */
    extraVars: EnvVarItem[];
    updateExtra: (i: number, field: "key" | "value", val: string) => void;
    removeExtra: (i: number) => void;
    addExtra: () => void;
    /** True when any known field or extra var differs from what was last loaded/saved. */
    isDirty: boolean;
    /** True when either the current or the last-saved extra vars are non-empty. */
    hasExtraVars: boolean;
    loading: boolean;
    saving: boolean;
    error: string | null;
    save: () => Promise<void>;
    discard: () => void;
}

/**
 * The one owner of a server's env-var editing: load → partition (schema-known vs extra) → dirty
 * tracking → save. `fields` are the schema-known keys; pass an empty list for server types whose
 * config is entirely extra vars (proxy, custom) so nothing is silently dropped.
 *
 * `fields` must be a stable reference (a module-level constant) — it is a load-effect dependency,
 * so an inline array would reload on every render.
 */
export function useServerEnvConfig(serverId: string, fields: readonly EnvField[]): ServerEnvConfig {
    const [form, setForm] = useState<Record<string, string>>({});
    const [savedForm, setSavedForm] = useState<Record<string, string>>({});
    const [extraVars, setExtraVars] = useState<EnvVarItem[]>([]);
    const [savedExtraVars, setSavedExtraVars] = useState<EnvVarItem[]>([]);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        const res = await getEnvVars({path: {id: serverId}});
        if (res.error) {
            setError((res.error as { message?: string }).message ?? "Failed to load env vars");
            setLoading(false);
            return;
        }
        const known = new Set(fields.map((f) => f.key));
        const nextForm: Record<string, string> = {};
        const nextExtra: EnvVarItem[] = [];
        for (const item of res.data?.env_vars ?? []) {
            if (known.has(item.key)) nextForm[item.key] = item.value;
            else nextExtra.push(item);
        }
        setForm(nextForm);
        setSavedForm(nextForm);
        setExtraVars(nextExtra);
        setSavedExtraVars(nextExtra);
        setLoading(false);
    }, [serverId, fields]);

    useEffect(() => {
        void load();
    }, [load]);

    const setField = useCallback((key: string, value: string) => {
        setForm((prev) => ({...prev, [key]: value}));
    }, []);

    const updateExtra = useCallback((i: number, field: "key" | "value", val: string) => {
        setExtraVars((prev) => prev.map((r, idx) => (idx === i ? {...r, [field]: val} : r)));
    }, []);

    const removeExtra = useCallback((i: number) => {
        setExtraVars((prev) => prev.filter((_, idx) => idx !== i));
    }, []);

    const addExtra = useCallback(() => {
        setExtraVars((prev) => [...prev, {key: "", value: ""}]);
    }, []);

    const discard = useCallback(() => {
        setForm(savedForm);
        setExtraVars(savedExtraVars);
    }, [savedForm, savedExtraVars]);

    const save = useCallback(async () => {
        // Known fields first, in schema order; blanks skipped (and omitted outright when the field
        // says so), then the extra vars.
        const known: EnvVarItem[] = [];
        for (const field of fields) {
            const val = form[field.key] ?? "";
            if (field.omitIfEmpty && !val) continue;
            if (val !== "") known.push({key: field.key, value: val});
        }
        const extra = extraVars.filter((r) => r.key.trim().length > 0);
        const keys = known.map((i) => i.key).concat(extra.map((i) => i.key.trim()));
        if (keys.length !== new Set(keys).size) {
            setError("Duplicate env var keys");
            return;
        }
        setSaving(true);
        setError(null);
        const res = await replaceEnvVars({path: {id: serverId}, body: {env_vars: [...known, ...extra]}});
        if (res.error) {
            setError((res.error as { message?: string }).message ?? "Save failed");
        } else {
            await load();
        }
        setSaving(false);
    }, [serverId, fields, form, extraVars, load]);

    const isDirty =
        JSON.stringify(form) !== JSON.stringify(savedForm) ||
        JSON.stringify(extraVars) !== JSON.stringify(savedExtraVars);

    return {
        form,
        setField,
        extraVars,
        updateExtra,
        removeExtra,
        addExtra,
        isDirty,
        hasExtraVars: extraVars.length > 0 || savedExtraVars.length > 0,
        loading,
        saving,
        error,
        save,
        discard,
    };
}
