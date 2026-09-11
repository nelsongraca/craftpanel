"use client";

import {useEffect, useState} from "react";
import {InfoRow} from "./server-info";
import {EditFieldRow, EditInput, EditSelect, EditTextarea, SaveCancelRow} from "./edit-fields";
import {McVersionSelect} from "@/components/ui/mc-version";
import {updateServer, listNetworks, updateServerExpiration} from "@/lib/generated/sdk.gen";
import type {Network, Server} from "@/lib/types";
import {hasPermission} from "@/lib/permissions";

interface EditGeneralProps {
    server: Server;
    permissions: string[];
    /** Bump to force the edit form open from outside (e.g. a link on another tab). */
    forceOpenSignal?: number;
    onSaved: () => void;
}

export function EditGeneral({server, permissions, forceOpenSignal, onSaved}: EditGeneralProps) {
    const isProxy = ["VELOCITY", "BUNGEECORD", "WATERFALL"].includes(server.server_type);
    const canSetExpiry = hasPermission(permissions, "server.expires");

    const [editing, setEditing] = useState(false);
    const [displayName, setDisplayName] = useState("");
    const [description, setDescription] = useState("");
    const [networkId, setNetworkId] = useState("");
    const [mcVersion, setMcVersion] = useState("");
    const [expiresAt, setExpiresAt] = useState<string>(""); // datetime-local string
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [networks, setNetworks] = useState<Network[]>([]);

    function open() {
        setDisplayName(server.display_name);
        setDescription(server.description ?? "");
        setNetworkId(server.network_id ?? "");
        setMcVersion(server.mc_version);
        setExpiresAt(server.expires_at ? server.expires_at.slice(0, 16) : "");
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

            onSaved();
            setEditing(false);
        } catch {
            setError("Failed to save");
        } finally {
            setSaving(false);
        }
    }

    return (
        <div className="bg-surface border border-border rounded p-4">
            <div className="flex items-center justify-between mb-3">
                <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">
                    General Settings
                </p>
                {!editing && (
                    <button
                        onClick={open}
                        className="text-xs font-heading font-bold uppercase tracking-wider text-text-muted hover:text-accent transition-colors"
                    >
                        Edit
                    </button>
                )}
            </div>

            {!editing ? (
                <div>
                    <InfoRow label="Display Name" value={server.display_name}/>
                    <InfoRow label="Description" value={server.description ?? "-"}/>
                    <InfoRow label="Network" value={networks.find((n) => n.id === server.network_id)?.name ?? "-"}/>
                    {!isProxy && <InfoRow label="MC Version" value={server.mc_version}/>}
                    <InfoRow
                        label="Expires"
                        value={
                            server.expires_at
                                ? new Date(server.expires_at).toLocaleString()
                                : "Never"
                        }
                    />
                </div>
            ) : (
                <div className="space-y-3">
                    {error && (
                        <p className="text-xs text-error">{error}</p>
                    )}
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
                    {!isProxy && (
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
                    <SaveCancelRow onSave={() => void save()} onCancel={() => setEditing(false)} saving={saving}/>
                </div>
            )}
        </div>
    );
}
