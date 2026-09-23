"use client";

import {useState} from "react";
import {SECTIONS} from "@/components/config/server-config-schema";
import {FieldSection} from "@/components/config/field-section";
import {ExtraVarsSection} from "@/components/config/extra-vars-section";
import {ProxyBackendsSection} from "@/components/config/proxy-backends-section";
import {ProxySettingsSection} from "@/components/config/proxy-settings-section";
import {StopCommandSection} from "@/components/config/stop-command-section";
import {ConfigModeToggle} from "@/components/config/config-mode-toggle";
import {UnsavedBar} from "@/components/config/unsaved-bar";
import {useServerEnvConfig, type EnvField} from "@/lib/hooks/useServerEnvConfig";
import {isProxyType} from "@/lib/server-types";

// Module-level constants so the hook's load effect is keyed on a stable field signature.
const GAME_FIELDS: readonly EnvField[] = SECTIONS.flatMap((section) => section.fields);
const NO_FIELDS: readonly EnvField[] = [];

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
    if (isProxyType(serverType)) {
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
    if (serverType === "CUSTOM" || serverType === "PICOLIMBO") {
        return <CustomServerConfigSection serverId={serverId} stopCommand={stopCommand} />;
    }
    return <GameServerConfigSection serverId={serverId} configMode={configMode} stopCommand={stopCommand} />;
}

function ConfigLoading() {
    return <div className="px-6 py-10 text-center text-sm text-text-muted">Loading{"\u2026"}</div>;
}

function ConfigError({message}: {message: string | null}) {
    if (!message) return null;
    return <div className="rounded border border-error/30 bg-error/10 px-3 py-2 text-xs text-error">{message}</div>;
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
    const config = useServerEnvConfig(serverId, NO_FIELDS);
    const isManual = configMode === "MANUAL";

    if (config.loading) return <ConfigLoading />;

    return (
        <div className="space-y-6 px-6 py-6">
            <p className="text-xs text-text-muted">
                Configuration changes take effect on the next server start or restart.
            </p>
            <ConfigModeToggle
                serverId={serverId}
                configMode={configMode}
                onChanged={setConfigMode}
                manualDescription="Manual mode - edit velocity.toml / config.yml directly in the Files tab."
                managedDescription="Settings below are applied to the proxy config on next start."
            />

            <ConfigError message={config.error} />

            <StopCommandSection serverId={serverId} stopCommand={stopCommand} placeholder="end" />

            {!isManual && (
                <>
                    <ProxySettingsSection serverId={serverId} serverType={serverType} />
                    <ProxyBackendsSection
                        serverId={serverId}
                        networkId={networkId}
                        onOpenGeneralSettings={onOpenGeneralSettings}
                    />
                </>
            )}

            <ExtraVarsSection
                extraVars={config.extraVars}
                onUpdate={config.updateExtra}
                onRemove={config.removeExtra}
                onAdd={config.addExtra}
            />

            {config.isDirty && (
                <UnsavedBar onDiscard={config.discard} onSave={() => void config.save()} saving={config.saving} />
            )}
        </div>
    );
}

function GameServerConfigSection({
    serverId,
    configMode: initialConfigMode,
    stopCommand,
}: {
    serverId: string;
    configMode: string;
    stopCommand: string;
}) {
    const [configMode, setConfigMode] = useState(initialConfigMode);
    const config = useServerEnvConfig(serverId, GAME_FIELDS);

    if (config.loading) return <ConfigLoading />;

    const isManual = configMode === "MANUAL";

    return (
        <div className="space-y-6 px-6 py-6">
            <ConfigModeToggle
                serverId={serverId}
                configMode={configMode}
                onChanged={setConfigMode}
                manualDescription="Manual mode - edit server.properties directly in the Files tab."
                managedDescription="Env vars below are applied to the container on next start."
            />

            <ConfigError message={config.error} />

            <StopCommandSection serverId={serverId} stopCommand={stopCommand} placeholder="stop" />

            {/* Field sections — server.properties-mapped sections are hidden in Manual mode.
                JVM Options stays visible: it has no server.properties equivalent. */}
            {SECTIONS.map((section) => {
                const isMappedSection = section.fields.some((f) => f.serverPropertiesMapped);
                if (isManual && isMappedSection) return null;
                return (
                    <FieldSection key={section.title} section={section} form={config.form} setField={config.setField} />
                );
            })}

            {config.hasExtraVars && (
                <ExtraVarsSection
                    extraVars={config.extraVars}
                    onUpdate={config.updateExtra}
                    onRemove={config.removeExtra}
                    onAdd={config.addExtra}
                />
            )}

            {config.isDirty && (
                <UnsavedBar onDiscard={config.discard} onSave={() => void config.save()} saving={config.saving} />
            )}
        </div>
    );
}

function CustomServerConfigSection({serverId, stopCommand}: {serverId: string; stopCommand: string}) {
    const config = useServerEnvConfig(serverId, NO_FIELDS);

    if (config.loading) return <ConfigLoading />;

    return (
        <div className="space-y-6 px-6 py-6">
            <p className="text-xs text-text-dim">
                CUSTOM servers run a user-supplied jar as-is. Configuration is manual — the jar is not auto-configured,
                and environment variables below are passed straight to the container.
            </p>

            <ConfigError message={config.error} />

            <StopCommandSection serverId={serverId} stopCommand={stopCommand} placeholder="stop" />

            <ExtraVarsSection
                extraVars={config.extraVars}
                onUpdate={config.updateExtra}
                onRemove={config.removeExtra}
                onAdd={config.addExtra}
            />

            {config.isDirty && (
                <UnsavedBar onDiscard={config.discard} onSave={() => void config.save()} saving={config.saving} />
            )}
        </div>
    );
}
