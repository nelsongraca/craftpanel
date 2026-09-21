"use client";

import {useCallback} from "react";
import {getEnvVars, replaceEnvVars} from "@/lib/generated/sdk.gen";
import type {EnvVarItem} from "@/lib/types";
import {useConfigSection} from "@/lib/hooks/useConfigSection";

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

type EnvDraft = {
    form: Record<string, string>;
    extraVars: EnvVarItem[];
};

/**
 * The one owner of a server's env-var editing: load → partition (schema-known vs extra) → dirty
 * tracking → save. The draft/dirty/save lifecycle itself lives in [useConfigSection]; this hook
 * adds the partition and the duplicate-key validation.
 *
 * `fields` must be a stable reference (a module-level constant) — it is a load-effect dependency,
 * so an inline array would reload on every render.
 */
export function useServerEnvConfig(serverId: string, fields: readonly EnvField[]): ServerEnvConfig {
    const load = useCallback(async () => {
        const res = await getEnvVars({path: {id: serverId}});
        if (res.error) return {error: res.error as { message?: string }};
        const known = new Set(fields.map((f) => f.key));
        const form: Record<string, string> = {};
        const extraVars: EnvVarItem[] = [];
        for (const item of res.data?.env_vars ?? []) {
            if (known.has(item.key)) form[item.key] = item.value;
            else extraVars.push(item);
        }
        return {data: {form, extraVars}};
    }, [serverId, fields]);

    const {draft, saved, setDraft, isDirty, loading, saving, error, save: persistDraft, discard} = useConfigSection<EnvDraft>({
        initial: {form: {}, extraVars: []},
        load,
        persist: (d) => {
            // Known fields first, in schema order; blanks skipped (and omitted outright when the
            // field says so), then the extra vars.
            const known: EnvVarItem[] = [];
            for (const field of fields) {
                const val = d.form[field.key] ?? "";
                if (field.omitIfEmpty && !val) continue;
                if (val !== "") known.push({key: field.key, value: val});
            }
            const extra = d.extraVars.filter((r) => r.key.trim().length > 0);
            const keys = known.map((i) => i.key).concat(extra.map((i) => i.key.trim()));
            if (keys.length !== new Set(keys).size) {
                return Promise.resolve({error: {message: "Duplicate env var keys"}});
            }
            return replaceEnvVars({path: {id: serverId}, body: {env_vars: [...known, ...extra]}});
        },
    });

    const setField = useCallback((key: string, value: string) => {
        setDraft((p) => ({...p, form: {...p.form, [key]: value}}));
    }, [setDraft]);

    const updateExtra = useCallback((i: number, field: "key" | "value", val: string) => {
        setDraft((p) => ({...p, extraVars: p.extraVars.map((r, idx) => (idx === i ? {...r, [field]: val} : r))}));
    }, [setDraft]);

    const removeExtra = useCallback((i: number) => {
        setDraft((p) => ({...p, extraVars: p.extraVars.filter((_, idx) => idx !== i)}));
    }, [setDraft]);

    const addExtra = useCallback(() => {
        setDraft((p) => ({...p, extraVars: [...p.extraVars, {key: "", value: ""}]}));
    }, [setDraft]);

    return {
        form: draft.form,
        setField,
        extraVars: draft.extraVars,
        updateExtra,
        removeExtra,
        addExtra,
        isDirty,
        hasExtraVars: draft.extraVars.length > 0 || saved.extraVars.length > 0,
        loading,
        saving,
        error,
        save: async () => {
            await persistDraft();
        },
        discard,
    };
}
