"use client";

import {useState} from "react";
import {InfoRow} from "./server-info";
import {EditFieldRow, EditInput, EditSection} from "./edit-fields";
import {updateServerExposure} from "@/lib/generated/sdk.gen";
import type {Server} from "@/lib/types";

interface EditExposureProps {
    server: Server;
    onSaved: () => void;
}

export function EditExposure({server, onSaved}: EditExposureProps) {
    const [editing, setEditing] = useState(false);
    const [exposedExternally, setExposedExternally] = useState(false);
    const [publicSubdomain, setPublicSubdomain] = useState("");
    const [customHostname, setCustomHostname] = useState("");
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    function open() {
        setExposedExternally(server.exposed_externally);
        setPublicSubdomain(server.public_subdomain ?? "");
        setCustomHostname(server.custom_hostname ?? "");
        setError(null);
        setEditing(true);
    }

    async function save() {
        setSaving(true);
        setError(null);
        try {
            const {error: expErr} = await updateServerExposure({
                path: {id: server.id},
                body: {
                    exposed_externally: exposedExternally,
                    public_subdomain: publicSubdomain || null,
                    custom_hostname: customHostname || null,
                },
            });
            if (expErr) {
                setError(expErr.message ?? "Failed to save");
                return;
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
            label="Public Access"
            editing={editing}
            saving={saving}
            error={error}
            onEdit={open}
            onCancel={() => setEditing(false)}
            onSave={() => void save()}
        >
            <div>
                <InfoRow label="Exposed" value={server.exposed_externally ? "Yes" : "No"}/>
                <InfoRow label="Public Subdomain" value={server.public_subdomain ?? "-"}/>
                <InfoRow label="Custom Hostname" value={server.custom_hostname ?? "-"}/>
                {server.canonical_hostname && <InfoRow label="Canonical" value={server.canonical_hostname}/>}
            </div>
            <div className="space-y-3">
                <EditFieldRow label="Expose Externally">
                    <div className="flex items-center gap-2 pt-1">
                        <input
                            type="checkbox"
                            id="expose-externally"
                            checked={exposedExternally}
                            onChange={(e) => setExposedExternally(e.target.checked)}
                            className="accent-[var(--accent)] w-4 h-4"
                        />
                        <label htmlFor="expose-externally" className="text-xs font-mono text-text-primary">
                            Expose via mc-router
                        </label>
                    </div>
                </EditFieldRow>
                {exposedExternally && (
                    <>
                        <EditFieldRow label="Public Subdomain">
                            <EditInput
                                value={publicSubdomain}
                                onChange={(e) => setPublicSubdomain(e.target.value)}
                                placeholder="myserver"
                            />
                            <p className="text-xs text-text-muted mt-1">Subdomain under the platform domain (e.g. myserver.mc.example.com)</p>
                        </EditFieldRow>
                        <EditFieldRow label="Custom Hostname">
                            <EditInput
                                value={customHostname}
                                onChange={(e) => setCustomHostname(e.target.value)}
                                placeholder="play.example.com"
                            />
                            <p className="text-xs text-text-muted mt-1">Your own domain (bring-your-own-DNS)</p>
                        </EditFieldRow>
                    </>
                )}
            </div>
        </EditSection>
    );
}
