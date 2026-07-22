"use client";

import {useCallback, useEffect, useState} from "react";
import {getEnvVars, replaceEnvVars} from "@/lib/generated/sdk.gen";
import type {EnvVarItem} from "@/lib/types";
import {SECTIONS, SCHEMA_KEYS} from "@/components/config/server-config-schema";
import {FieldSection} from "@/components/config/field-section";
import {ExtraVarsSection} from "@/components/config/extra-vars-section";
import {ProxyBackendsSection} from "@/components/config/proxy-backends-section";
import {ProxySettingsSection} from "@/components/config/proxy-settings-section";
import {StopCommandSection} from "@/components/config/stop-command-section";
import {ConfigModeToggle} from "@/components/config/config-mode-toggle";

const PROXY_TYPES = new Set(["VELOCITY", "BUNGEECORD", "WATERFALL"]);

export function ConfigTab({
                              serverId,
                              serverType,
                              networkId,
                              configMode,
                              stopCommand,
                              onOpenGeneralSettings,
                          }: {
    serverId: string;
    serverType: string;
    networkId: string | null;
    configMode: string;
    stopCommand: string;
    onOpenGeneralSettings?: () => void;
}) {
    if (PROXY_TYPES.has(serverType)) {
        return (
            <ProxyServerConfigSection
                serverId={serverId}
                serverType={serverType}
                networkId={networkId}
                configMode={configMode}
                stopCommand={stopCommand}
                onOpenGeneralSettings={onOpenGeneralSettings}
            />
        );
    }
    return <GameServerConfigSection serverId={serverId} configMode={configMode} stopCommand={stopCommand}/>;
}

function ProxyServerConfigSection({
                                       serverId,
                                       serverType,
                                       networkId,
                                       configMode: initialConfigMode,
                                       stopCommand,
                                       onOpenGeneralSettings,
                                   }: {
    serverId: string;
    serverType: string;
    networkId: string | null;
    configMode: string;
    stopCommand: string;
    onOpenGeneralSettings?: () => void;
}) {
    const [configMode, setConfigMode] = useState(initialConfigMode);
    const isManual = configMode === "MANUAL";

    return (
        <div className="px-6 py-6 space-y-6">
            <ConfigModeToggle
                serverId={serverId}
                configMode={configMode}
                onChanged={setConfigMode}
                manualDescription="Manual mode - edit velocity.toml / config.yml directly in the Files tab."
                managedDescription="Settings below are applied to the proxy config on next start."
            />

            <StopCommandSection serverId={serverId} stopCommand={stopCommand} placeholder="end"/>

            {!isManual && (
                <>
                    <ProxySettingsSection serverId={serverId} serverType={serverType}/>
                    <ProxyBackendsSection serverId={serverId} networkId={networkId} onOpenGeneralSettings={onOpenGeneralSettings}/>
                </>
            )}
        </div>
    );
}

function GameServerConfigSection({
                                     serverId,
                                     configMode: initialConfigMode,
                                     stopCommand: initialStopCommand,
                                 }: {
    serverId: string;
    configMode: string;
    stopCommand: string;
}) {
    const [configMode, setConfigMode] = useState(initialConfigMode);

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
        const items = res.data?.env_vars ?? [];
        const formState: Record<string, string> = {};
        const extra: EnvVarItem[] = [];
        for (const item of items) {
            if (SCHEMA_KEYS.has(item.key)) {
                formState[item.key] = item.value;
            } else {
                extra.push(item);
            }
        }
        setForm(formState);
        setSavedForm(formState);
        setExtraVars(extra);
        setSavedExtraVars(extra);
        setLoading(false);
    }, [serverId]);

    useEffect(() => {
        load();
    }, [load]);

    function setField(key: string, value: string) {
        setForm((prev) => ({...prev, [key]: value}));
    }

    function removeExtra(i: number) {
        setExtraVars((prev) => prev.filter((_, idx) => idx !== i));
    }

    function updateExtra(i: number, field: "key" | "value", val: string) {
        setExtraVars((prev) => prev.map((r, idx) => (idx === i ? {...r, [field]: val} : r)));
    }

    function addExtra() {
        setExtraVars((prev) => [...prev, {key: "", value: ""}]);
    }

    const isFormDirty =
        JSON.stringify(form) !== JSON.stringify(savedForm) ||
        JSON.stringify(extraVars) !== JSON.stringify(savedExtraVars);

    async function handleSave() {
        const envVarItems: EnvVarItem[] = [];
        for (const section of SECTIONS) {
            for (const field of section.fields) {
                const val = form[field.key] ?? "";
                if (field.omitIfEmpty && !val) continue;
                if (val !== "") {
                    envVarItems.push({key: field.key, value: val});
                }
            }
        }
        const validExtra = extraVars.filter((r) => r.key.trim().length > 0);
        const allKeys = envVarItems.map((i) => i.key).concat(validExtra.map((i) => i.key.trim()));
        if (allKeys.length !== new Set(allKeys).size) {
            setError("Duplicate env var keys");
            return;
        }
        setSaving(true);
        setError(null);
        const body = {env_vars: [...envVarItems, ...validExtra]};
        const res = await replaceEnvVars({path: {id: serverId}, body});
        if (res.error) {
            setError((res.error as { message?: string }).message ?? "Save failed");
        } else {
            await load();
        }
        setSaving(false);
    }

    if (loading) {
        return <div className="px-6 py-10 text-center text-text-muted text-sm">Loading{"…"}</div>;
    }

    const isManual = configMode === "MANUAL";

    return (
        <div className="px-6 py-6 space-y-6">
            <ConfigModeToggle
                serverId={serverId}
                configMode={configMode}
                onChanged={setConfigMode}
                manualDescription="Manual mode - edit server.properties directly in the Files tab."
                managedDescription="Env vars below are applied to the container on next start."
            />

            {error && (
                <div className="text-xs text-error bg-error/10 border border-error/30 rounded px-3 py-2">
                    {error}
                </div>
            )}

            {/* Stop Command */}
            <StopCommandSection serverId={serverId} stopCommand={initialStopCommand} placeholder="stop"/>

            {/* Field sections — hidden entirely in Manual mode (mapped to server.properties) */}
            {SECTIONS.map((section) => {
                const isMappedSection = section.fields.some((f) => f.serverPropertiesMapped);
                if (isManual && (isMappedSection || section.title === "JVM Options")) return null;
                return (
                    <FieldSection
                        key={section.title}
                        section={section}
                        form={form}
                        setField={setField}
                    />
                );
            })}

            {/* Extra vars */}
            {(extraVars.length > 0 || savedExtraVars.length > 0) && (
                <ExtraVarsSection
                    extraVars={extraVars}
                    onUpdate={updateExtra}
                    onRemove={removeExtra}
                    onAdd={addExtra}
                />
            )}

            {/* Unsaved changes bar */}
            {isFormDirty && (
                <div className="flex items-center justify-between pt-2 border-t border-border">
                    <span className="text-xs text-text-muted">Unsaved changes</span>
                    <div className="flex gap-2">
                        <button
                            onClick={() => {
                                setForm(savedForm);
                                setExtraVars(savedExtraVars);
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
                            {saving ? "Saving…" : "Save"}
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
}
