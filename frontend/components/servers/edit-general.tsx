"use client";

import {InfoRow} from "./server-info";
import {EditFieldRow, EditInput, EditSelect, EditTextarea, SaveCancelRow} from "./edit-fields";
import type {Network, Server} from "@/lib/types";

interface EditGeneralProps {
    server: Server;
    networks: Network[];
    mcVersions: string[];
    editing: boolean;
    displayName: string;
    description: string;
    networkId: string;
    mcVersion: string;
    saving: boolean;
    error: string | null;
    onOpen: () => void;
    onSave: () => void;
    onCancel: () => void;
    onChangeName: (v: string) => void;
    onChangeDesc: (v: string) => void;
    onChangeNetwork: (v: string) => void;
    onChangeMcVersion: (v: string) => void;
}

export function EditGeneral({
                                server,
                                networks,
                                mcVersions,
                                editing,
                                displayName,
                                description,
                                networkId,
                                mcVersion,
                                saving,
                                error,
                                onOpen,
                                onSave,
                                onCancel,
                                onChangeName,
                                onChangeDesc,
                                onChangeNetwork,
                                onChangeMcVersion,
                            }: EditGeneralProps) {
    const isProxy = ["VELOCITY", "BUNGEECORD", "WATERFALL"].includes(server.server_type);

    return (
        <div className="bg-surface border border-border rounded p-4">
            <div className="flex items-center justify-between mb-3">
                <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">
                    General Settings
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
                    <InfoRow label="Display Name" value={server.display_name}/>
                    <InfoRow label="Description" value={server.description ?? "\u2014"}/>
                    <InfoRow label="Network" value={networks.find((n) => n.id === server.network_id)?.name ?? "\u2014"}/>
                    {!isProxy && <InfoRow label="MC Version" value={server.mc_version}/>}
                </div>
            ) : (
                <div className="space-y-3">
                    {error && (
                        <p className="text-[12px] text-error">{error}</p>
                    )}
                    <EditFieldRow label="Display Name">
                        <EditInput
                            value={displayName}
                            onChange={(e) => onChangeName(e.target.value)}
                            placeholder={server.display_name}
                        />
                    </EditFieldRow>
                    <EditFieldRow label="Description">
                        <EditTextarea
                            value={description}
                            onChange={(e) => onChangeDesc(e.target.value)}
                            placeholder="Optional description"
                        />
                    </EditFieldRow>
                    <EditFieldRow label="Network">
                        <EditSelect value={networkId} onChange={(e) => onChangeNetwork(e.target.value)}>
                            <option value="">None</option>
                            {networks.map((n) => (
                                <option key={n.id} value={n.id}>{n.name}</option>
                            ))}
                        </EditSelect>
                    </EditFieldRow>
                    {!isProxy && (
                        <EditFieldRow label="Minecraft Version">
                            {mcVersions.length > 0 ? (
                                <EditSelect value={mcVersion} onChange={(e) => onChangeMcVersion(e.target.value)}>
                                    {mcVersions.map((v) => <option key={v} value={v}>{v}</option>)}
                                </EditSelect>
                            ) : (
                                <EditInput
                                    value={mcVersion}
                                    onChange={(e) => onChangeMcVersion(e.target.value)}
                                    placeholder="1.21.4"
                                />
                            )}
                            <p className="text-[12px] text-text-muted mt-1">Requires restart to take effect.</p>
                        </EditFieldRow>
                    )}
                    <SaveCancelRow onSave={onSave} onCancel={onCancel} saving={saving}/>
                </div>
            )}
        </div>
    );
}
