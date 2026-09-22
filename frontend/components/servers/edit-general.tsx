"use client";

import {useEffect, useState} from "react";
import {InfoRow} from "@/components/edit/info-row";
import {EditFieldRow, EditInput, EditSelect, EditTextarea, EditSection} from "@/components/edit/edit-fields";
import {McVersionSelect} from "@/components/ui/mc-version";
import {updateServer, listNetworks, updateServerExpiration, setServerDisabled, updateServerDataDir} from "@/lib/generated/sdk.gen";
import type {Network, Server} from "@/lib/types";
import {hasPermission} from "@/lib/permissions";
import {Switch} from "@/components/ui/switch";
import {isCustomType, isPicolimboType} from "@/lib/server-types";

interface EditGeneralProps {
    server: Server;
    permissions: string[];
    /** Bump to force the edit form open from outside (e.g. a link on another tab). */
    forceOpenSignal?: number;
    onSaved: () => void;
}

export function EditGeneral({server, permissions, forceOpenSignal, onSaved}: EditGeneralProps) {
    const isCustom = isCustomType(server.server_type);
    const isPicolimbo = isPicolimboType(server.server_type);
    const canSetExpiry = hasPermission(permissions, "server.expires");
    const canDisable = hasPermission(permissions, "server.disable");
    const canOverrideDir = hasPermission(permissions, "server.dir_override");

    const [editing, setEditing] = useState(false);
    const [displayName, setDisplayName] = useState("");
    const [description, setDescription] = useState("");
    const [networkId, setNetworkId] = useState("");
    const [mcVersion, setMcVersion] = useState("");
    const [expiresAt, setExpiresAt] = useState<string>(""); // datetime-local string
    const [disabled, setDisabled] = useState(false);
    const [dataDirName, setDataDirName] = useState("");
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [networks, setNetworks] = useState<Network[]>([]);

    function open() {
        setDisplayName(server.display_name);
        setDescription(server.description ?? "");
        setNetworkId(server.network_id ?? "");
        setMcVersion(server.mc_version);
        setExpiresAt(server.expires_at ? server.expires_at.slice(0, 16) : "");
        setDisabled(server.disabled ?? false);
        setDataDirName(server.data_dir_name ?? "");
        setError(null);
        setEditing(true);
        if (networks.length === 0) {
            listNetworks().then(({data}) => {
                if (data) setNetworks(data);
            });
        }
    }

    useEffect(() => {
        if (networks.length === 0) {
            listNetworks().then(({data}) => {
                if (data) setNetworks(data);
            });
        }
        if (forceOpenSignal !== undefined) open();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [forceOpenSignal]);

    async function save() {
        setSaving(true);
        setError(null);
        try {
            const body: Record<string, unknown> = {};
            if (displayName !== server.display_name) body.display_name = displayName;
            if (description !== (server.description ?? "")) body.description = description || "";
            if (networkId !== (server.network_id ?? "")) body.network_id = networkId || "";
            if (mcVersion !== server.mc_version) body.mc_version = mcVersion;

            // Update general fields via PATCH /servers/{id}
            const {error: updateErr} = await updateServer({path: {id: server.id}, body: body as Parameters<typeof updateServer>[0]["body"]});
            if (updateErr) {
                setError(updateErr.message ?? "Failed to save");
                return;
            }

            // Update expiration via dedicated endpoint if permission and changed
            if (canSetExpiry) {
                const prev = server.expires_at ? server.expires_at.slice(0, 16) : "";
                if (expiresAt !== prev) {
                    const {error: expireErr} = await updateServerExpiration({
                        path: {id: server.id},
                        body: expiresAt
                            ? {expires_at: new Date(expiresAt).toISOString()}
                            : {expires_at: null}
                    });
                    if (expireErr) {
                        setError(expireErr.message ?? "Failed to save expiration");
                        return;
                    }
                }
            }

            // Update disabled state via dedicated endpoint if permission and changed
            if (canDisable && disabled !== (server.disabled ?? false)) {
                const {error: disableErr} = await setServerDisabled({
                    path: {id: server.id},
                    body: {disabled}
                });
                if (disableErr) {
                    setError(disableErr.message ?? "Failed to update disabled state");
                    return;
                }
            }

            // Data directory override via dedicated endpoint if permission and changed
            if (canOverrideDir) {
                const prev = server.data_dir_name ?? "";
                if (dataDirName.trim() !== prev) {
                    const {error: dirErr} = await updateServerDataDir({
                        path: {id: server.id},
                        body: {data_dir_name: dataDirName.trim() || null}
                    });
                    if (dirErr) {
                        setError(dirErr.message ?? "Failed to update data directory");
                        return;
                    }
                }
            }

            onSaved();
            setEditing(false);
        } catch {
            setError("Failed to save");
        } finally {
            setSaving(false);
        }
    }

    return (
        <EditSection
            label="General Settings"
            editing={editing}
            saving={saving}
            error={error}
            onEdit={open}
            onCancel={() => setEditing(false)}
            onSave={() => void save()}
        >
            <div>
                <InfoRow label="Display Name" value={server.display_name}/>
                <InfoRow label="Description" value={server.description ?? "-"}/>
                <InfoRow label="Network" value={networks.find((n) => n.id === server.network_id)?.name ?? "-"}/>
                {!isCustom && !isPicolimbo && <InfoRow label="MC Version" value={server.mc_version}/>}
                <InfoRow
                    label="Expires"
                    value={
                        server.expires_at
                            ? new Date(server.expires_at).toLocaleString()
                            : "Never"
                    }
                />
                <InfoRow label="Data Directory" value={server.data_dir_name || server.id}/>
            </div>
            <div className="space-y-3">
                <EditFieldRow label="Display Name">
                    <EditInput
                        value={displayName}
                        onChange={(e) => setDisplayName(e.target.value)}
                        placeholder={server.display_name}
                    />
                </EditFieldRow>
                <EditFieldRow label="Description">
                    <EditTextarea
                        value={description}
                        onChange={(e) => setDescription(e.target.value)}
                        placeholder="Optional description"
                    />
                </EditFieldRow>
                <EditFieldRow label="Network">
                    <EditSelect value={networkId} onChange={(e) => setNetworkId(e.target.value)}>
                        <option value="">None</option>
                        {networks.map((n) => (
                            <option key={n.id} value={n.id}>{n.name}</option>
                        ))}
                    </EditSelect>
                </EditFieldRow>
                {canSetExpiry && (
                    <EditFieldRow label="Expires At">
                        <EditInput
                            type="datetime-local"
                            value={expiresAt}
                            onChange={(e) => setExpiresAt(e.target.value)}
                            placeholder="Optional expiration date/time"
                        />
                    </EditFieldRow>
                )}
                {canDisable && (
                    <EditFieldRow label="Disabled">
                        <div className="flex items-center gap-3">
                            <Switch
                                checked={disabled}
                                onCheckedChange={setDisabled}
                            />
                            <span className="text-xs text-text-muted">
                                    {disabled
                                        ? "Server cannot be started until re-enabled. If running, it will be stopped."
                                        : "Server can be started and stopped normally."}
                                </span>
                        </div>
                    </EditFieldRow>
                )}
                {canOverrideDir && (
                    <EditFieldRow label="Data Directory">
                        <EditInput
                            value={dataDirName}
                            onChange={(e) => setDataDirName(e.target.value)}
                            placeholder={server.id}
                        />
                        <p className="text-xs text-text-muted mt-1">
                            Overrides the data directory name (defaults to the server ID). No files are moved —
                            the directory must already hold the data; a running server is flagged restart-pending
                            and picks up the new path on the next start or restart.
                        </p>
                    </EditFieldRow>
                )}
                {!isPicolimbo && (
                    <EditFieldRow label="Minecraft Version">
                        <McVersionSelect
                            value={mcVersion}
                            onChange={setMcVersion}
                            placeholder="1.21.4"
                            fieldSize="sm"
                            surface="bg"
                        />
                        <p className="text-xs text-text-muted mt-1">Requires restart to take effect.</p>
                    </EditFieldRow>
                )}
            </div>
        </EditSection>
    );
}
