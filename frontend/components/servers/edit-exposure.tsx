"use client";

import {InfoRow} from "./server-info";
import {EditFieldRow, EditInput, SaveCancelRow} from "./edit-fields";
import type {Server} from "@/lib/types";

interface EditExposureProps {
    server: Server;
    editing: boolean;
    exposedExternally: boolean;
    publicSubdomain: string;
    customHostname: string;
    saving: boolean;
    error: string | null;
    onOpen: () => void;
    onSave: () => void;
    onCancel: () => void;
    onChangeExposedExternally: (v: boolean) => void;
    onChangePublicSubdomain: (v: string) => void;
    onChangeCustomHostname: (v: string) => void;
}

export function EditExposure({
                                 server,
                                 editing,
                                 exposedExternally,
                                 publicSubdomain,
                                 customHostname,
                                 saving,
                                 error,
                                 onOpen,
                                 onSave,
                                 onCancel,
                                 onChangeExposedExternally,
                                 onChangePublicSubdomain,
                                 onChangeCustomHostname,
                             }: EditExposureProps) {
    return (
        <div className="bg-surface border border-border rounded p-4">
            <div className="flex items-center justify-between mb-3">
                <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">
                    Public Access
                </p>
                {!editing && (
                    <button
                        onClick={onOpen}
                        className="text-[12px] font-heading font-bold uppercase tracking-wider text-text-muted hover:text-accent transition-colors"
                    >
                        Edit
                    </button>
                )}
            </div>

            {!editing ? (
                <div>
                    <InfoRow label="Exposed" value={server.exposed_externally ? "Yes" : "No"}/>
                    <InfoRow label="Public Subdomain" value={server.public_subdomain ?? "\u2014"}/>
                    <InfoRow label="Custom Hostname" value={server.custom_hostname ?? "\u2014"}/>
                    {server.canonical_hostname && <InfoRow label="Canonical" value={server.canonical_hostname}/>}
                </div>
            ) : (
                <div className="space-y-3">
                    {error && (
                        <p className="text-[12px] text-error">{error}</p>
                    )}
                    <EditFieldRow label="Expose Externally">
                        <div className="flex items-center gap-2 pt-1">
                            <input
                                type="checkbox"
                                id="expose-externally"
                                checked={exposedExternally}
                                onChange={(e) => onChangeExposedExternally(e.target.checked)}
                                className="accent-[var(--accent)] w-4 h-4"
                            />
                            <label htmlFor="expose-externally" className="text-[12px] font-mono text-text-primary">
                                Expose via mc-router
                            </label>
                        </div>
                    </EditFieldRow>
                    {exposedExternally && (
                        <>
                            <EditFieldRow label="Public Subdomain">
                                <EditInput
                                    value={publicSubdomain}
                                    onChange={(e) => onChangePublicSubdomain(e.target.value)}
                                    placeholder="myserver"
                                />
                                <p className="text-[12px] text-text-muted mt-1">Subdomain under the platform domain (e.g. myserver.mc.example.com)</p>
                            </EditFieldRow>
                            <EditFieldRow label="Custom Hostname">
                                <EditInput
                                    value={customHostname}
                                    onChange={(e) => onChangeCustomHostname(e.target.value)}
                                    placeholder="play.example.com"
                                />
                                <p className="text-[12px] text-text-muted mt-1">Your own domain (bring-your-own-DNS)</p>
                            </EditFieldRow>
                        </>
                    )}
                    <SaveCancelRow onSave={onSave} onCancel={onCancel} saving={saving}/>
                </div>
            )}
        </div>
    );
}
