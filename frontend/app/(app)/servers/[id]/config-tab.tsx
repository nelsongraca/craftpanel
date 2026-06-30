"use client";

import {useCallback, useEffect, useState} from "react";
import {getEnvVars, replaceEnvVars, updateConfigMode, updateStopCommand} from "@/lib/generated/sdk.gen";
import type {EnvVarItem, ConfigMode} from "@/lib/types";
import {ConfirmDialog} from "@/components/ui/confirm-dialog";
import {SECTIONS, SCHEMA_KEYS} from "@/components/config/server-config-schema";
import {FieldSection} from "@/components/config/field-section";
import {ExtraVarsSection} from "@/components/config/extra-vars-section";
import {ProxyBackendsSection} from "@/components/config/proxy-backends-section";

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
        return <ProxyBackendsSection serverId={serverId} networkId={networkId} stopCommand={stopCommand} onOpenGeneralSettings={onOpenGeneralSettings}/>;
    }
    return <GameServerConfigSection serverId={serverId} configMode={configMode} stopCommand={stopCommand}/>;
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
    const [togglingMode, setTogglingMode] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const [stopCmd, setStopCmd] = useState(initialStopCommand);
    const [savedStopCmd, setSavedStopCmd] = useState(initialStopCommand);
    const [savingStop, setSavingStop] = useState(false);
    const [stopError, setStopError] = useState<string | null>(null);

    const [confirmDialog, setConfirmDialog] = useState<{
        title: string;
        description: string;
        destructive?: boolean;
        onConfirm: () => void;
    } | null>(null);

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

    async function applyToggleMode(next: string) {
        setTogglingMode(true);
        setError(null);
        const res = await updateConfigMode({path: {id: serverId}, body: {config_mode: next as ConfigMode}});
        if (res.error) {
            setError((res.error as { message?: string }).message ?? "Failed to update config mode");
        } else {
            setConfigMode(next);
        }
        setTogglingMode(false);
    }

    function handleToggleMode() {
        const next = configMode === "MANAGED" ? "MANUAL" : "MANAGED";
        if (configMode === "MANAGED") {
            setConfirmDialog({
                title: "Disable Managed Env Vars?",
                description: "Existing vars are preserved but won't be applied to server.properties until you switch back.",
                onConfirm: () => void applyToggleMode(next),
            });
            return;
        }
        void applyToggleMode(next);
    }

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

    const isManual = configMode === "MANUAL";

    return (
        <>
            <div className="px-6 py-6 space-y-6">
                {/* Config Mode toggle */}
                <div className="flex items-center justify-between">
                    <div>
                        <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted mb-1">
                            Config Mode
                        </p>
                        <p className="text-[12px] text-text-dim">
                            {isManual
                                ? "Manual mode \u2014 edit server.properties directly in the Files tab."
                                : "Env vars below are applied to the container on next start."}
                        </p>
                    </div>
                    <button
                        onClick={handleToggleMode}
                        disabled={togglingMode}
                        className="px-3 py-1.5 rounded text-[12px] font-heading font-bold uppercase tracking-widest border border-border text-text-dim hover:border-text-muted transition-colors disabled:opacity-40"
                    >
                        {togglingMode ? "Switching\u2026" : isManual ? "Switch to Managed" : "Switch to Manual"}
                    </button>
                </div>

                {error && (
                    <div className="text-[12px] text-error bg-error/10 border border-error/30 rounded px-3 py-2">
                        {error}
                    </div>
                )}

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
                                placeholder="stop"
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

                {/* Field sections */}
                {SECTIONS.map((section) => {
                    const isMappedSection = section.fields.some((f) => f.serverPropertiesMapped);
                    const dimmed = isManual && isMappedSection;
                    return (
                        <FieldSection
                            key={section.title}
                            section={section}
                            form={form}
                            setField={setField}
                            isManual={isManual}
                            dimmed={dimmed}
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
                        <span className="text-[12px] text-text-muted">Unsaved changes</span>
                        <div className="flex gap-2">
                            <button
                                onClick={() => {
                                    setForm(savedForm);
                                    setExtraVars(savedExtraVars);
                                }}
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
            </div>

            <ConfirmDialog
                open={confirmDialog !== null}
                onOpenChange={(open) => !open && setConfirmDialog(null)}
                title={confirmDialog?.title ?? ""}
                description={confirmDialog?.description ?? ""}
                destructive={confirmDialog?.destructive}
                onConfirm={confirmDialog?.onConfirm ?? (() => {
                })}
            />
        </>
    );
}
