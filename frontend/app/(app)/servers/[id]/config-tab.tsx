"use client";

import { useCallback, useEffect, useState } from "react";
import { ChevronDown, ChevronUp, Plus, Trash2, X } from "lucide-react";
import {
    getEnvVars,
    getProxyBackends,
    listServers,
    replaceEnvVars,
    replaceProxyBackends,
    updateConfigMode,
    updateStopCommand,
} from "@/lib/generated/sdk.gen";
import type { EnvVarItem, ProxyBackend, PutEnvVarsRequest, PutProxyBackendsRequest } from "@/lib/types";
import type { IoCraftpanelMasterServiceServerResponse as ServerResponse } from "@/lib/generated/types.gen";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Switch } from "@/components/ui/switch";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";

const PROXY_TYPES = new Set(["VELOCITY", "BUNGEECORD", "WATERFALL"]);

// ---------------------------------------------------------------------------
// Field schema
// ---------------------------------------------------------------------------

type FieldType = "text" | "number" | "toggle" | "select" | "textarea" | "tag-input";

interface FieldDef {
    key: string;
    label: string;
    type: FieldType;
    options?: string[];
    hint?: string;
    showWhen?: { key: string; value: string } | { key: string; nonEmpty: true };
    serverPropertiesMapped: boolean;
    omitIfEmpty?: boolean;
}

interface Section {
    title: string;
    fields: FieldDef[];
    collapsible?: boolean;
}

const SECTIONS: Section[] = [
    {
        title: "Gameplay",
        fields: [
            { key: "DIFFICULTY", label: "Difficulty", type: "select", options: ["peaceful", "easy", "normal", "hard"], serverPropertiesMapped: true },
            { key: "MODE", label: "Default Game Mode", type: "select", options: ["survival", "creative", "adventure", "spectator"], serverPropertiesMapped: true },
            { key: "HARDCORE", label: "Hardcore Mode", type: "toggle", serverPropertiesMapped: true },
            { key: "PVP", label: "PvP", type: "toggle", serverPropertiesMapped: true },
            { key: "ALLOW_NETHER", label: "Allow Nether", type: "toggle", serverPropertiesMapped: true },
            { key: "FORCE_GAMEMODE", label: "Force Game Mode on Join", type: "toggle", serverPropertiesMapped: true },
            { key: "SPAWN_ANIMALS", label: "Spawn Animals", type: "toggle", serverPropertiesMapped: true },
            { key: "SPAWN_MONSTERS", label: "Spawn Monsters", type: "toggle", serverPropertiesMapped: true },
            { key: "SPAWN_NPCS", label: "Spawn Villagers", type: "toggle", serverPropertiesMapped: true },
            { key: "SPAWN_PROTECTION", label: "Spawn Protection Radius", type: "number", serverPropertiesMapped: true },
            { key: "ALLOW_FLIGHT", label: "Allow Flight", type: "toggle", serverPropertiesMapped: true },
        ],
    },
    {
        title: "World",
        fields: [
            { key: "SEED", label: "World Seed", type: "text", hint: "Leave blank for a random seed. Changing after creation has no effect on the existing world.", serverPropertiesMapped: true, omitIfEmpty: true },
            { key: "LEVEL", label: "World Save Name", type: "text", serverPropertiesMapped: true },
            { key: "LEVEL_TYPE", label: "World Type", type: "select", options: ["DEFAULT", "FLAT", "AMPLIFIED", "BIOMESOP", "LARGEBIOMES", "CUSTOMIZED", "SUNSETSKYLANDS"], serverPropertiesMapped: true },
            { key: "GENERATOR_SETTINGS", label: "Generator Settings", type: "textarea", hint: "Only used when World Type is FLAT.", serverPropertiesMapped: true, omitIfEmpty: true, showWhen: { key: "LEVEL_TYPE", value: "FLAT" } },
            { key: "GENERATE_STRUCTURES", label: "Generate Structures", type: "toggle", serverPropertiesMapped: true },
            { key: "MAX_WORLD_SIZE", label: "Max World Size", type: "number", serverPropertiesMapped: true },
            { key: "MAX_BUILD_HEIGHT", label: "Max Build Height", type: "number", serverPropertiesMapped: true, omitIfEmpty: true },
        ],
    },
    {
        title: "Players & Access",
        fields: [
            { key: "MOTD", label: "Message of the Day", type: "text", hint: "Leave blank to let itzg generate its own MOTD.", serverPropertiesMapped: true, omitIfEmpty: true },
            { key: "MAX_PLAYERS", label: "Max Players", type: "number", serverPropertiesMapped: true },
            { key: "ONLINE_MODE", label: "Online Mode", type: "toggle", hint: "Disabling bypasses Mojang authentication.", serverPropertiesMapped: true },
            { key: "ENABLE_WHITELIST", label: "Enable Whitelist", type: "toggle", serverPropertiesMapped: true },
            { key: "WHITELIST", label: "Whitelisted Players", type: "tag-input", hint: "Comma-separated usernames or UUIDs.", serverPropertiesMapped: true, omitIfEmpty: true },
            { key: "OPS", label: "Operator Players", type: "tag-input", hint: "Comma-separated usernames or UUIDs.", serverPropertiesMapped: true, omitIfEmpty: true },
            { key: "PLAYER_IDLE_TIMEOUT", label: "Player Idle Timeout (min)", type: "number", hint: "0 = disabled.", serverPropertiesMapped: true },
            { key: "ENFORCE_SECURE_PROFILE", label: "Enforce Secure Chat", type: "toggle", hint: "1.19+", serverPropertiesMapped: true },
            { key: "PREVENT_PROXY_CONNECTIONS", label: "Prevent Proxy Connections", type: "toggle", serverPropertiesMapped: true },
        ],
    },
    {
        title: "Performance",
        fields: [
            { key: "VIEW_DISTANCE", label: "View Distance (chunks)", type: "number", serverPropertiesMapped: true },
            { key: "SIMULATION_DISTANCE", label: "Simulation Distance (chunks)", type: "number", serverPropertiesMapped: true },
            { key: "MAX_TICK_TIME", label: "Max Tick Time (ms)", type: "number", hint: "-1 to disable watchdog.", serverPropertiesMapped: true },
            { key: "NETWORK_COMPRESSION_THRESHOLD", label: "Compression Threshold (bytes)", type: "number", hint: "-1 to disable.", serverPropertiesMapped: true },
            { key: "SYNC_CHUNK_WRITES", label: "Sync Chunk Writes", type: "toggle", serverPropertiesMapped: true },
        ],
    },
    {
        title: "Resource Pack",
        fields: [
            { key: "RESOURCE_PACK", label: "Resource Pack URL", type: "text", serverPropertiesMapped: true, omitIfEmpty: true },
            { key: "RESOURCE_PACK_SHA1", label: "Resource Pack SHA1", type: "text", serverPropertiesMapped: true, omitIfEmpty: true },
            { key: "RESOURCE_PACK_ENFORCE", label: "Enforce Resource Pack", type: "toggle", serverPropertiesMapped: true, omitIfEmpty: true, showWhen: { key: "RESOURCE_PACK", nonEmpty: true } },
        ],
    },
    {
        title: "Advanced",
        collapsible: true,
        fields: [
            { key: "ENABLE_COMMAND_BLOCK", label: "Enable Command Blocks", type: "toggle", serverPropertiesMapped: true },
            { key: "OP_PERMISSION_LEVEL", label: "Op Permission Level", type: "number", hint: "1–4", serverPropertiesMapped: true },
            { key: "FUNCTION_PERMISSION_LEVEL", label: "Function Permission Level", type: "number", serverPropertiesMapped: true },
            { key: "BROADCAST_CONSOLE_TO_OPS", label: "Broadcast Console to Ops", type: "toggle", serverPropertiesMapped: true },
            { key: "ICON", label: "Server Icon URL", type: "text", hint: "Downloaded and scaled by itzg on start.", serverPropertiesMapped: true, omitIfEmpty: true },
            { key: "TZ", label: "Timezone", type: "text", hint: "e.g. Europe/London", serverPropertiesMapped: true },
            { key: "CUSTOM_SERVER_PROPERTIES", label: "Custom Server Properties", type: "textarea", hint: "Newline-delimited key=value pairs for plugin-specific properties.", serverPropertiesMapped: true, omitIfEmpty: true },
        ],
    },
    {
        title: "JVM Options",
        fields: [
            { key: "USE_AIKAR_FLAGS", label: "Aikar's GC Flags", type: "toggle", hint: "Recommended for production.", serverPropertiesMapped: false },
            { key: "USE_MEOWICE_FLAGS", label: "MeowIce JVM Flags", type: "toggle", hint: "Aikar's flags updated for Java 21+. Mutually exclusive with Aikar's flags.", serverPropertiesMapped: false },
            { key: "JVM_OPTS", label: "Extra JVM Arguments", type: "text", hint: "Space-delimited raw JVM flags.", serverPropertiesMapped: false, omitIfEmpty: true },
            { key: "JVM_XX_OPTS", label: "Extra JVM -XX Arguments", type: "text", hint: "Space-delimited -XX options only.", serverPropertiesMapped: false, omitIfEmpty: true },
        ],
    },
];

const SCHEMA_KEYS = new Set(SECTIONS.flatMap((s) => s.fields.map((f) => f.key)));

// ---------------------------------------------------------------------------
// Public component
// ---------------------------------------------------------------------------

export function ConfigTab({
    serverId,
    serverType,
    networkId,
    configMode,
    stopCommand,
}: {
    serverId: string;
    serverType: string;
    networkId: string | null;
    configMode: string;
    stopCommand: string;
}) {
    if (PROXY_TYPES.has(serverType)) {
        return <ProxyBackendsSection serverId={serverId} networkId={networkId} stopCommand={stopCommand} />;
    }
    return <GameServerConfigSection serverId={serverId} configMode={configMode} stopCommand={stopCommand} />;
}

// ---------------------------------------------------------------------------
// Game server config
// ---------------------------------------------------------------------------

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

    // Form state: env var key → value
    const [form, setForm] = useState<Record<string, string>>({});
    const [savedForm, setSavedForm] = useState<Record<string, string>>({});
    const [extraVars, setExtraVars] = useState<EnvVarItem[]>([]);
    const [savedExtraVars, setSavedExtraVars] = useState<EnvVarItem[]>([]);

    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [togglingMode, setTogglingMode] = useState(false);
    const [error, setError] = useState<string | null>(null);

    // Stop command state (independent save)
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
        const res = await getEnvVars({ path: { id: serverId } });
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
        const res = await updateConfigMode({ path: { id: serverId }, body: { config_mode: next } });
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
        setForm((prev) => ({ ...prev, [key]: value }));
    }

    function removeExtra(i: number) {
        setExtraVars((prev) => prev.filter((_, idx) => idx !== i));
    }

    function updateExtra(i: number, field: "key" | "value", val: string) {
        setExtraVars((prev) => prev.map((r, idx) => (idx === i ? { ...r, [field]: val } : r)));
    }

    function addExtra() {
        setExtraVars((prev) => [...prev, { key: "", value: "" }]);
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
                    envVarItems.push({ key: field.key, value: val });
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
        const body: PutEnvVarsRequest = { env_vars: [...envVarItems, ...validExtra] };
        const res = await replaceEnvVars({ path: { id: serverId }, body });
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
        const res = await updateStopCommand({ path: { id: serverId }, body: { stop_command: stopCmd } });
        if (res.error) {
            setStopError((res.error as { message?: string }).message ?? "Failed to save stop command");
        } else {
            setSavedStopCmd(stopCmd);
        }
        setSavingStop(false);
    }

    if (loading) {
        return <div className="px-6 py-10 text-center text-text-muted text-[13px]">Loading…</div>;
    }

    const isManual = configMode === "MANUAL";

    return (
        <>
            <div className="px-6 py-6 space-y-6">
                {/* Config Mode toggle */}
                <div className="flex items-center justify-between">
                    <div>
                        <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted mb-1">
                            Config Mode
                        </p>
                        <p className="text-[12px] text-text-dim">
                            {isManual
                                ? "Manual mode — edit server.properties directly in the Files tab."
                                : "Env vars below are applied to the container on next start."}
                        </p>
                    </div>
                    <button
                        onClick={handleToggleMode}
                        disabled={togglingMode}
                        className="px-3 py-1.5 rounded text-[11px] font-heading font-bold uppercase tracking-widest border border-border text-text-dim hover:border-text-muted transition-colors disabled:opacity-40"
                    >
                        {togglingMode ? "Switching…" : isManual ? "Switch to Managed" : "Switch to Manual"}
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
                        <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">
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
                            <p className="text-[11px] text-text-muted mt-1">
                                Command sent to stdin on stop / restart. Leave empty to skip.
                            </p>
                        </div>
                        {stopCmd !== savedStopCmd && (
                            <button
                                onClick={handleSaveStopCmd}
                                disabled={savingStop}
                                className="px-3 py-1.5 rounded text-[11px] font-heading font-bold uppercase tracking-widest bg-accent text-bg hover:bg-accent-bright transition-colors disabled:opacity-60"
                            >
                                {savingStop ? "Saving…" : "Save"}
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

                {/* Extra (unknown) vars */}
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
                        <span className="text-[11px] text-text-muted">Unsaved changes</span>
                        <div className="flex gap-2">
                            <button
                                onClick={() => {
                                    setForm(savedForm);
                                    setExtraVars(savedExtraVars);
                                }}
                                className="px-3 py-1.5 rounded text-[11px] font-heading font-bold uppercase tracking-widest text-text-dim border border-border hover:border-text-muted transition-colors"
                            >
                                Discard
                            </button>
                            <button
                                onClick={handleSave}
                                disabled={saving}
                                className="px-3 py-1.5 rounded text-[11px] font-heading font-bold uppercase tracking-widest bg-accent text-bg hover:bg-accent-bright transition-colors disabled:opacity-60"
                            >
                                {saving ? "Saving…" : "Save"}
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
                onConfirm={confirmDialog?.onConfirm ?? (() => {})}
            />
        </>
    );
}

// ---------------------------------------------------------------------------
// FieldSection
// ---------------------------------------------------------------------------

function FieldSection({
    section,
    form,
    setField,
    isManual,
    dimmed,
}: {
    section: Section;
    form: Record<string, string>;
    setField: (key: string, value: string) => void;
    isManual: boolean;
    dimmed: boolean;
}) {
    const [open, setOpen] = useState(false);

    const visibleFields = section.fields.filter((f) => {
        if (!f.showWhen) return true;
        if ("value" in f.showWhen) {
            return (form[f.showWhen.key] ?? "") === f.showWhen.value;
        }
        return (form[f.showWhen.key] ?? "").length > 0;
    });

    if (visibleFields.length === 0) return null;

    const content = (
        <div className="divide-y divide-border">
            {dimmed && (
                <div className="px-4 py-2 bg-warning/5 border-b border-warning/20 text-[11px] text-warning">
                    Manual mode active — these fields will not be applied to server.properties until you switch back to Managed.
                </div>
            )}
            {visibleFields.map((field) => (
                <FieldRow
                    key={field.key}
                    field={field}
                    value={form[field.key] ?? ""}
                    onChange={(val) => setField(field.key, val)}
                    dimmed={dimmed && field.serverPropertiesMapped}
                    form={form}
                    setField={setField}
                />
            ))}
        </div>
    );

    if (section.collapsible) {
        return (
            <Collapsible open={open} onOpenChange={setOpen}>
                <div className="border border-border rounded overflow-hidden">
                    <CollapsibleTrigger className="w-full">
                        <div className="px-4 py-2.5 bg-surface-high flex items-center justify-between cursor-pointer hover:bg-surface-higher transition-colors">
                            <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">
                                {section.title}
                            </p>
                            {open ? (
                                <ChevronUp className="w-3.5 h-3.5 text-text-muted" />
                            ) : (
                                <ChevronDown className="w-3.5 h-3.5 text-text-muted" />
                            )}
                        </div>
                    </CollapsibleTrigger>
                    <CollapsibleContent>{content}</CollapsibleContent>
                </div>
            </Collapsible>
        );
    }

    return (
        <div className="border border-border rounded overflow-hidden">
            <div className="px-4 py-2.5 bg-surface-high border-b border-border">
                <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">
                    {section.title}
                </p>
            </div>
            {content}
        </div>
    );
}

// ---------------------------------------------------------------------------
// FieldRow
// ---------------------------------------------------------------------------

function FieldRow({
    field,
    value,
    onChange,
    dimmed,
    form,
    setField,
}: {
    field: FieldDef;
    value: string;
    onChange: (val: string) => void;
    dimmed: boolean;
    form: Record<string, string>;
    setField: (key: string, value: string) => void;
}) {
    const inputCls = `bg-surface-higher border border-border rounded px-2 py-1.5 text-[12px] text-text-primary focus:border-accent/50 focus:outline-none ${dimmed ? "opacity-60" : ""}`;

    return (
        <div className={`px-4 py-3 flex items-start gap-4 ${dimmed ? "opacity-80" : ""}`}>
            <div className="w-56 shrink-0 pt-0.5">
                <p className="text-[12px] text-text-primary font-medium">{field.label}</p>
                {field.hint && (
                    <p className="text-[11px] text-text-muted mt-0.5">{field.hint}</p>
                )}
            </div>
            <div className="flex-1">
                {field.type === "toggle" && (
                    <ToggleField
                        fieldKey={field.key}
                        value={value}
                        onChange={onChange}
                        dimmed={dimmed}
                        form={form}
                        setField={setField}
                    />
                )}
                {field.type === "select" && (
                    <select
                        value={value}
                        onChange={(e) => onChange(e.target.value)}
                        disabled={dimmed}
                        className={`${inputCls} w-48`}
                    >
                        {field.options?.map((opt) => (
                            <option key={opt} value={opt}>
                                {opt}
                            </option>
                        ))}
                    </select>
                )}
                {field.type === "text" && (
                    <input
                        type="text"
                        value={value}
                        onChange={(e) => onChange(e.target.value)}
                        disabled={dimmed}
                        className={`${inputCls} w-full max-w-sm`}
                    />
                )}
                {field.type === "number" && (
                    <input
                        type="number"
                        value={value}
                        onChange={(e) => onChange(e.target.value)}
                        disabled={dimmed}
                        className={`${inputCls} w-32`}
                    />
                )}
                {field.type === "textarea" && (
                    <textarea
                        value={value}
                        onChange={(e) => onChange(e.target.value)}
                        disabled={dimmed}
                        rows={4}
                        className={`${inputCls} w-full max-w-lg font-mono resize-y`}
                    />
                )}
                {field.type === "tag-input" && (
                    <TagInput value={value} onChange={onChange} disabled={dimmed} />
                )}
            </div>
        </div>
    );
}

// ---------------------------------------------------------------------------
// Toggle field — handles mutual exclusion for Aikar/MeowIce
// ---------------------------------------------------------------------------

function ToggleField({
    fieldKey,
    value,
    onChange,
    dimmed,
    form,
    setField,
}: {
    fieldKey: string;
    value: string;
    onChange: (val: string) => void;
    dimmed: boolean;
    form: Record<string, string>;
    setField: (key: string, value: string) => void;
}) {
    const checked = value === "true";

    function handleChange(next: boolean) {
        onChange(next ? "true" : "false");
        // Mutual exclusion: Aikar ↔ MeowIce
        if (next && fieldKey === "USE_AIKAR_FLAGS" && form["USE_MEOWICE_FLAGS"] === "true") {
            setField("USE_MEOWICE_FLAGS", "false");
        }
        if (next && fieldKey === "USE_MEOWICE_FLAGS" && form["USE_AIKAR_FLAGS"] === "true") {
            setField("USE_AIKAR_FLAGS", "false");
        }
    }

    return (
        <Switch
            checked={checked}
            onCheckedChange={handleChange}
            disabled={dimmed}
        />
    );
}

// ---------------------------------------------------------------------------
// Tag input
// ---------------------------------------------------------------------------

function TagInput({
    value,
    onChange,
    disabled,
}: {
    value: string;
    onChange: (val: string) => void;
    disabled: boolean;
}) {
    const tags = value ? value.split(",").map((t) => t.trim()).filter(Boolean) : [];
    const [inputVal, setInputVal] = useState("");

    function addTag() {
        const trimmed = inputVal.trim();
        if (!trimmed || tags.includes(trimmed)) return;
        onChange([...tags, trimmed].join(","));
        setInputVal("");
    }

    function removeTag(tag: string) {
        const next = tags.filter((t) => t !== tag);
        onChange(next.join(","));
    }

    return (
        <div className="space-y-2">
            <div className="flex flex-wrap gap-1">
                {tags.map((tag) => (
                    <span
                        key={tag}
                        className="inline-flex items-center gap-1 bg-surface-higher border border-border rounded px-2 py-0.5 text-[11px] font-mono text-text-primary"
                    >
                        {tag}
                        {!disabled && (
                            <button
                                onClick={() => removeTag(tag)}
                                className="text-text-muted hover:text-error transition-colors"
                            >
                                <X className="w-2.5 h-2.5" />
                            </button>
                        )}
                    </span>
                ))}
            </div>
            {!disabled && (
                <div className="flex gap-2">
                    <input
                        value={inputVal}
                        onChange={(e) => setInputVal(e.target.value)}
                        onKeyDown={(e) => e.key === "Enter" && (e.preventDefault(), addTag())}
                        placeholder="Add entry…"
                        className="bg-surface-higher border border-border rounded px-2 py-1 text-[12px] font-mono text-text-primary w-48 focus:border-accent/50 focus:outline-none"
                    />
                    <button
                        onClick={addTag}
                        className="p-1 text-text-muted hover:text-text-primary transition-colors"
                    >
                        <Plus className="w-3.5 h-3.5" />
                    </button>
                </div>
            )}
        </div>
    );
}

// ---------------------------------------------------------------------------
// Extra (unknown) vars section
// ---------------------------------------------------------------------------

function ExtraVarsSection({
    extraVars,
    onUpdate,
    onRemove,
    onAdd,
}: {
    extraVars: EnvVarItem[];
    onUpdate: (i: number, field: "key" | "value", val: string) => void;
    onRemove: (i: number) => void;
    onAdd: () => void;
}) {
    return (
        <div className="border border-border rounded overflow-hidden">
            <div className="px-4 py-2.5 bg-surface-high border-b border-border flex items-center justify-between">
                <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">
                    Extra Variables
                </p>
                <button
                    onClick={onAdd}
                    className="flex items-center gap-1 text-[11px] font-heading font-bold uppercase tracking-widest text-text-muted hover:text-text-primary transition-colors"
                >
                    <Plus className="w-3 h-3" />
                    Add
                </button>
            </div>
            {extraVars.length === 0 ? (
                <div className="px-4 py-3 text-[12px] text-text-muted">No extra variables.</div>
            ) : (
                <table className="w-full text-[12px]">
                    <thead>
                        <tr className="border-b border-border">
                            <th className="px-4 py-2 text-left text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted w-5/12">
                                Key
                            </th>
                            <th className="px-4 py-2 text-left text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">
                                Value
                            </th>
                            <th className="px-4 py-2 w-10"></th>
                        </tr>
                    </thead>
                    <tbody>
                        {extraVars.map((r, i) => (
                            <tr key={i} className="border-b border-border last:border-0">
                                <td className="px-4 py-2">
                                    <input
                                        value={r.key}
                                        onChange={(e) => onUpdate(i, "key", e.target.value)}
                                        placeholder="KEY"
                                        className="bg-surface-higher border border-border rounded px-2 py-1 text-[12px] font-mono text-text-primary w-full focus:border-accent/50 focus:outline-none"
                                    />
                                </td>
                                <td className="px-4 py-2">
                                    <input
                                        value={r.value}
                                        onChange={(e) => onUpdate(i, "value", e.target.value)}
                                        placeholder="value"
                                        className="bg-surface-higher border border-border rounded px-2 py-1 text-[12px] font-mono text-text-primary w-full focus:border-accent/50 focus:outline-none"
                                    />
                                </td>
                                <td className="px-4 py-2">
                                    <button
                                        onClick={() => onRemove(i)}
                                        className="p-1 text-text-muted hover:text-error transition-colors"
                                    >
                                        <Trash2 className="w-3.5 h-3.5" />
                                    </button>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
        </div>
    );
}

// ---------------------------------------------------------------------------
// Proxy backends section
// ---------------------------------------------------------------------------

type EditableBackend = {
    id?: string;
    backendServerId: string;
    backendName: string;
    order: number;
    displayName: string;
    serverType: string;
    status: string;
};

function ProxyBackendsSection({
    serverId,
    networkId,
    stopCommand: initialStopCommand,
}: {
    serverId: string;
    networkId: string | null;
    stopCommand: string;
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
            getProxyBackends({ path: { id: serverId } }),
            listServers(),
        ]);
        if (backendsRes.error) {
            setError((backendsRes.error as { message?: string }).message ?? "Failed to load backends");
            setLoading(false);
            return;
        }
        const raw: ProxyBackend[] = backendsRes.data?.backends ?? [];
        const allServers: ServerResponse[] = serversRes.data ?? [];
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
            return next.map((b, i) => ({ ...b, order: i + 1 }));
        });
    }

    function moveDown(index: number) {
        setBackends((prev) => {
            if (index >= prev.length - 1) return prev;
            const next = [...prev];
            [next[index], next[index + 1]] = [next[index + 1], next[index]];
            return next.map((b, i) => ({ ...b, order: i + 1 }));
        });
    }

    function removeBackend(index: number) {
        setBackends((prev) =>
            prev.filter((_, i) => i !== index).map((b, i) => ({ ...b, order: i + 1 })),
        );
    }

    function renameBackend(index: number, name: string) {
        setBackends((prev) => prev.map((b, i) => (i === index ? { ...b, backendName: name } : b)));
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
        const res = await replaceProxyBackends({ path: { id: serverId }, body });
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
        const res = await updateStopCommand({ path: { id: serverId }, body: { stop_command: stopCmd } });
        if (res.error) {
            setStopError((res.error as { message?: string }).message ?? "Failed to save stop command");
        } else {
            setSavedStopCmd(stopCmd);
        }
        setSavingStop(false);
    }

    if (loading) {
        return <div className="px-6 py-10 text-center text-text-muted text-[13px]">Loading…</div>;
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
                    <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">
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
                        <p className="text-[11px] text-text-muted mt-1">
                            Command sent to stdin on stop / restart. Leave empty to skip.
                        </p>
                    </div>
                    {stopCmd !== savedStopCmd && (
                        <button
                            onClick={handleSaveStopCmd}
                            disabled={savingStop}
                            className="px-3 py-1.5 rounded text-[11px] font-heading font-bold uppercase tracking-widest bg-accent text-bg hover:bg-accent-bright transition-colors disabled:opacity-60"
                        >
                            {savingStop ? "Saving…" : "Save"}
                        </button>
                    )}
                </div>
                {stopError && (
                    <div className="px-4 pb-3 text-[12px] text-error">{stopError}</div>
                )}
            </div>

            <div className="flex items-center justify-between">
                <div>
                    <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted mb-1">
                        Proxy Backends
                    </p>
                    <p className="text-[12px] text-text-dim">
                        Backend servers routed by this proxy in managed mode.
                    </p>
                </div>
                <button
                    onClick={() => setShowAddModal(true)}
                    disabled={available.length === 0}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded text-[11px] font-heading font-bold uppercase tracking-widest bg-accent/10 text-accent border border-accent/30 hover:bg-accent/20 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                >
                    <Plus className="w-3.5 h-3.5" />
                    Add Backend
                </button>
            </div>

            {error && (
                <div className="text-[12px] text-error bg-error/10 border border-error/30 rounded px-3 py-2">
                    {error}
                </div>
            )}

            {!networkId && (
                <div className="text-[12px] text-warning bg-warning/10 border border-warning/30 rounded px-3 py-2">
                    This server is not in a network. Assign it to a network to add backends.
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
                                <th className="px-4 py-2.5 text-left text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted w-8">
                                    #
                                </th>
                                <th className="px-4 py-2.5 text-left text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">
                                    Server
                                </th>
                                <th className="px-4 py-2.5 text-left text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">
                                    Backend Name
                                </th>
                                <th className="px-4 py-2.5 text-left text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">
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
                                        <span className="ml-2 text-[10px] text-text-muted font-mono">
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
                                            className={`text-[10px] font-heading font-bold uppercase tracking-widest ${
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
                                                <ChevronUp className="w-3.5 h-3.5" />
                                            </button>
                                            <button
                                                onClick={() => moveDown(i)}
                                                disabled={i === backends.length - 1}
                                                className="p-1 text-text-muted hover:text-text-primary disabled:opacity-30 transition-colors"
                                            >
                                                <ChevronDown className="w-3.5 h-3.5" />
                                            </button>
                                            <button
                                                onClick={() => removeBackend(i)}
                                                className="p-1 text-text-muted hover:text-error transition-colors ml-1"
                                            >
                                                <Trash2 className="w-3.5 h-3.5" />
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
                    <span className="text-[11px] text-text-muted">Unsaved changes</span>
                    <div className="flex gap-2">
                        <button
                            onClick={() => setBackends(saved)}
                            className="px-3 py-1.5 rounded text-[11px] font-heading font-bold uppercase tracking-widest text-text-dim border border-border hover:border-text-muted transition-colors"
                        >
                            Discard
                        </button>
                        <button
                            onClick={handleSave}
                            disabled={saving}
                            className="px-3 py-1.5 rounded text-[11px] font-heading font-bold uppercase tracking-widest bg-accent text-bg hover:bg-accent-bright transition-colors disabled:opacity-60"
                        >
                            {saving ? "Saving…" : "Save"}
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

// ---------------------------------------------------------------------------
// AddBackendModal
// ---------------------------------------------------------------------------

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
                    <p className="text-[11px] font-heading font-bold uppercase tracking-widest text-text-muted">
                        Add Backend
                    </p>
                    <button
                        onClick={onClose}
                        className="text-text-muted hover:text-text-primary transition-colors"
                    >
                        <X className="w-4 h-4" />
                    </button>
                </div>

                <div className="space-y-3">
                    <div>
                        <label className="block text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted mb-1.5">
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
                        <label className="block text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted mb-1.5">
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
                            <p className="text-[11px] text-error mt-1">
                                Lowercase letters, numbers, hyphens and underscores only
                            </p>
                        )}
                    </div>
                </div>

                <div className="flex justify-end gap-2 pt-1">
                    <button
                        onClick={onClose}
                        className="px-3 py-1.5 rounded text-[11px] font-heading font-bold uppercase tracking-widest text-text-dim border border-border hover:border-text-muted transition-colors"
                    >
                        Cancel
                    </button>
                    <button
                        onClick={() => selected && onAdd(selected, backendName.trim())}
                        disabled={!valid || !selected}
                        className="px-3 py-1.5 rounded text-[11px] font-heading font-bold uppercase tracking-widest bg-accent text-bg hover:bg-accent-bright transition-colors disabled:opacity-50"
                    >
                        Add
                    </button>
                </div>
            </div>
        </div>
    );
}

function slugify(name: string): string {
    return name
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-+|-+$/g, "");
}
