"use client";

import {type KeyboardEvent, useState} from "react";
import {X} from "lucide-react";
import {InfoRow} from "./server-info";
import {EditFieldRow, EditInput, EditSection} from "./edit-fields";
import {updateServerExposure} from "@/lib/generated/sdk.gen";
import type {Server} from "@/lib/types";

interface EditExposureProps {
    server: Server;
    onSaved: () => void;
}

// Custom hostnames are stored comma-separated (mc-router's label format); split for display.
function parseHostnames(raw: string | null | undefined): string[] {
    if (!raw) return [];
    return Array.from(new Set(raw.split(",").map((h) => h.trim()).filter(Boolean)));
}

function HostnameChip({hostname, onRemove}: { hostname: string; onRemove?: () => void }) {
    return (
        <span className="inline-flex items-center gap-1 rounded border border-border bg-surface-higher px-1.5 py-0.5 font-mono text-xs text-text-primary">
            {hostname}
            {onRemove && (
                <button
                    type="button"
                    onClick={onRemove}
                    aria-label={`Remove ${hostname}`}
                    className="text-text-muted hover:text-error transition-colors"
                >
                    <X size={11}/>
                </button>
            )}
        </span>
    );
}

export function EditExposure({server, onSaved}: EditExposureProps) {
    const [editing, setEditing] = useState(false);
    const [exposedExternally, setExposedExternally] = useState(false);
    const [publicSubdomain, setPublicSubdomain] = useState("");
    const [customHostnames, setCustomHostnames] = useState<string[]>([]);
    const [hostnameDraft, setHostnameDraft] = useState("");
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const savedHostnames = parseHostnames(server.custom_hostname);

    function open() {
        setExposedExternally(server.exposed_externally);
        setPublicSubdomain(server.public_subdomain ?? "");
        setCustomHostnames(parseHostnames(server.custom_hostname));
        setHostnameDraft("");
        setError(null);
        setEditing(true);
    }

    function addDraft() {
        const value = hostnameDraft.trim();
        if (!value) return;
        setCustomHostnames((prev) => prev.includes(value) ? prev : [...prev, value]);
        setHostnameDraft("");
    }

    function removeHostname(hostname: string) {
        setCustomHostnames((prev) => prev.filter((h) => h !== hostname));
    }

    function onDraftKeyDown(e: KeyboardEvent<HTMLInputElement>) {
        if (e.key === "Enter" || e.key === ",") {
            e.preventDefault();
            addDraft();
        } else if (e.key === "Backspace" && hostnameDraft === "" && customHostnames.length > 0) {
            setCustomHostnames((prev) => prev.slice(0, -1));
        }
    }

    async function save() {
        setSaving(true);
        setError(null);
        // Fold an uncommitted draft in so typing then saving does not drop the last hostname.
        const draft = hostnameDraft.trim();
        const hostnames = draft
            ? Array.from(new Set([...customHostnames, draft]))
            : customHostnames;
        try {
            const {error: expErr} = await updateServerExposure({
                path: {id: server.id},
                body: {
                    exposed_externally: exposedExternally,
                    // Disabling exposure unsets the hostnames; don't resend stale state values.
                    public_subdomain: exposedExternally ? publicSubdomain || null : null,
                    custom_hostname: exposedExternally && hostnames.length > 0 ? hostnames.join(",") : null,
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
                <InfoRow
                    label="Custom Hostnames"
                    value={savedHostnames.length > 0 ? (
                        <span className="flex flex-wrap justify-end gap-1">
                            {savedHostnames.map((h) => <HostnameChip key={h} hostname={h}/>)}
                        </span>
                    ) : "-"}
                />
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
                        <EditFieldRow label="Custom Hostnames">
                            {customHostnames.length > 0 && (
                                <div className="flex flex-wrap gap-1.5">
                                    {customHostnames.map((h) => (
                                        <HostnameChip key={h} hostname={h} onRemove={() => removeHostname(h)}/>
                                    ))}
                                </div>
                            )}
                            <div className="flex items-center gap-2 mt-1.5">
                                <EditInput
                                    value={hostnameDraft}
                                    onChange={(e) => setHostnameDraft(e.target.value)}
                                    onKeyDown={onDraftKeyDown}
                                    onBlur={addDraft}
                                    placeholder="play.example.com"
                                />
                                <button
                                    type="button"
                                    onClick={addDraft}
                                    className="shrink-0 px-3 py-1.5 rounded border border-border text-xs font-heading font-bold uppercase tracking-wider text-text-dim hover:text-text-primary hover:border-accent transition-colors"
                                >
                                    Add
                                </button>
                            </div>
                            <p className="text-xs text-text-muted mt-1">Your own domains (bring-your-own-DNS). Separate multiple with commas or Enter.</p>
                        </EditFieldRow>
                    </>
                )}
            </div>
        </EditSection>
    );
}
