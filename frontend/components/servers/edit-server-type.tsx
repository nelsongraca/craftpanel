"use client";

import {useState} from "react";
import {InfoRow} from "@/components/edit/info-row";
import {EditFieldRow, EditInput, EditSelect, EditSection} from "@/components/edit/edit-fields";
import {Switch} from "@/components/ui/switch";
import {updateServer} from "@/lib/generated/sdk.gen";
import type {Server, ServerType} from "@/lib/types";
import {GAME_SERVER_TYPES, PROXY_TYPES, isCustomType} from "@/lib/server-types";

interface EditServerTypeProps {
    server: Server;
    onSaved: () => void;
}

/**
 * Changes a server's software. A type change swaps what runs under the same data directory, so
 * master requires the server to be stopped. The picker lists every backend `ServerType`; the
 * remaining guardrails (installed mods/plugins, proxy/backend wiring) are enforced server-side and
 * surfaced here as the error message.
 */
export function EditServerType({server, onSaved}: EditServerTypeProps) {
    const [editing, setEditing] = useState(false);
    const [serverType, setServerType] = useState<ServerType>(server.server_type);
    const [customServerJar, setCustomServerJar] = useState("");
    const [disableHealthcheck, setDisableHealthcheck] = useState(false);
    const [forceRedownload, setForceRedownload] = useState(false);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const stoppable = server.status === "STOPPED" || server.status === "CRASH_LOOPED";
    const changed = serverType !== server.server_type;
    const isCustom = isCustomType(server.server_type);

    function open() {
        setServerType(server.server_type);
        setCustomServerJar(server.custom_server_jar ?? "");
        setDisableHealthcheck(server.disable_healthcheck ?? false);
        setForceRedownload(server.force_redownload ?? false);
        setError(null);
        setEditing(true);
    }

    async function save() {
        const targetIsCustom = isCustomType(serverType);
        const jar = customServerJar.trim();
        if (targetIsCustom && !jar) {
            setError("Custom Server Jar is required");
            return;
        }

        setSaving(true);
        setError(null);
        try {
            const body: Record<string, unknown> = {server_type: serverType};
            // Custom-only settings travel with the type change. A non-CUSTOM target omits
            // custom_server_jar so the backend clears it; the two toggles are left untouched.
            if (targetIsCustom) {
                if (jar !== (server.custom_server_jar ?? "")) body.custom_server_jar = jar;
                if (disableHealthcheck !== (server.disable_healthcheck ?? false))
                    body.disable_healthcheck = disableHealthcheck;
                if (forceRedownload !== (server.force_redownload ?? false))
                    body.force_redownload = forceRedownload;
            }

            const {error: updateErr} = await updateServer({
                path: {id: server.id},
                body: body as Parameters<typeof updateServer>[0]["body"],
            });
            if (updateErr) {
                setError(updateErr.message ?? "Failed to change server type");
                return;
            }
            onSaved();
            setEditing(false);
        } catch {
            setError("Failed to change server type");
        } finally {
            setSaving(false);
        }
    }

    return (
        <EditSection
            label="Server Type"
            editing={editing}
            saving={saving}
            error={error}
            onEdit={open}
            onCancel={() => setEditing(false)}
            onSave={() => void save()}
            canEdit={stoppable}
        >
            <div>
                <InfoRow label="Software" value={server.server_type} />
                {isCustom && (
                    <InfoRow
                        label="Custom Server Jar"
                        value={<span className="break-all">{server.custom_server_jar || "Not set"}</span>}
                    />
                )}
                {isCustom && (
                    <InfoRow
                        label="Healthcheck"
                        value={server.disable_healthcheck ? "Disabled" : "Enabled"}
                    />
                )}
                {isCustom && (
                    <InfoRow
                        label="Force Redownload"
                        value={server.force_redownload ? "On" : "Off"}
                    />
                )}
                {!stoppable && <p className="mt-1 text-xs text-text-muted">Stop the server to change its type.</p>}
            </div>
            <div className="space-y-3">
                <EditFieldRow label="Server Type">
                    <EditSelect value={serverType} onChange={(e) => setServerType(e.target.value as ServerType)}>
                        <optgroup label="Game Servers">
                            {GAME_SERVER_TYPES.map((t) => (
                                <option key={t} value={t}>
                                    {t}
                                </option>
                            ))}
                        </optgroup>
                        <optgroup label="Proxies">
                            {PROXY_TYPES.map((t) => (
                                <option key={t} value={t}>
                                    {t}
                                </option>
                            ))}
                        </optgroup>
                    </EditSelect>
                    <p className="mt-1 text-xs text-text-muted">
                        Changes the software the server runs. Requires the server to be stopped.
                    </p>
                </EditFieldRow>
                {isCustomType(serverType) && (
                    <>
                        <EditFieldRow label="Custom Server Jar">
                            <EditInput
                                value={customServerJar}
                                onChange={(e) => setCustomServerJar(e.target.value)}
                                placeholder="/data/server.jar or https://example.com/server.jar"
                                aria-required
                            />
                            <p className="mt-1 text-xs text-text-muted">
                                Absolute path in the data volume or a download URL. Passed to itzg as
                                CUSTOM_SERVER.
                            </p>
                        </EditFieldRow>
                        <EditFieldRow label="Disable healthcheck">
                            <div className="flex items-center gap-3">
                                <Switch
                                    checked={disableHealthcheck}
                                    onCheckedChange={setDisableHealthcheck}
                                    aria-label="Disable healthcheck"
                                />
                                <span className="text-xs text-text-muted">
                                    Skip itzg&apos;s container health check.
                                </span>
                            </div>
                        </EditFieldRow>
                        <EditFieldRow label="Force redownload on start">
                            <div className="flex items-center gap-3">
                                <Switch
                                    checked={forceRedownload}
                                    onCheckedChange={setForceRedownload}
                                    aria-label="Force redownload on start"
                                />
                                <span className="text-xs text-text-muted">
                                    Re-download the jar on every start.
                                </span>
                            </div>
                        </EditFieldRow>
                    </>
                )}
                {changed && (
                    <p className="text-xs text-warning">
                        Type-exclusive settings (custom jar, listen port, config mode, stop command, proxy settings)
                        reset to the new type&apos;s defaults; a jar is required when switching to CUSTOM. Installed
                        mods/plugins must match the new type, and the data directory is not moved — existing world
                        files may need manual cleanup.
                    </p>
                )}
            </div>
        </EditSection>
    );
}
