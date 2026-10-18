"use client";

import {useState} from "react";
import {InfoRow} from "@/components/edit/info-row";
import {EditFieldRow, EditSelect, EditSection} from "@/components/edit/edit-fields";
import {updateServer} from "@/lib/generated/sdk.gen";
import type {Server, ServerType} from "@/lib/types";
import {GAME_SERVER_TYPES, PROXY_TYPES} from "@/lib/server-types";

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
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const stoppable = server.status === "STOPPED" || server.status === "CRASH_LOOPED";
    const changed = serverType !== server.server_type;

    function open() {
        setServerType(server.server_type);
        setError(null);
        setEditing(true);
    }

    async function save() {
        setSaving(true);
        setError(null);
        try {
            const {error: updateErr} = await updateServer({
                path: {id: server.id},
                body: {server_type: serverType},
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
                {changed && (
                    <p className="text-xs text-warning">
                        Type-exclusive settings (custom jar, listen port, config mode, stop command, proxy settings)
                        reset to the new type&apos;s defaults. Installed mods/plugins must match the new type, and the
                        data directory is not moved — existing world files may need manual cleanup.
                    </p>
                )}
            </div>
        </EditSection>
    );
}
