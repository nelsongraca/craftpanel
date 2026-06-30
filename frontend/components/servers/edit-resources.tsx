"use client";

import {InfoRow} from "./server-info";
import {EditFieldRow, EditInput, SaveCancelRow} from "./edit-fields";
import type {Server} from "@/lib/types";

interface EditResourcesProps {
    server: Server;
    editing: boolean;
    ramMb: number;
    cpuShares: number;
    itzgTag: string;
    saving: boolean;
    error: string | null;
    onOpen: () => void;
    onSave: () => void;
    onCancel: () => void;
    onChangeRamMb: (v: number) => void;
    onChangeCpuShares: (v: number) => void;
    onChangeItzgTag: (v: string) => void;
}

export function EditResources({
                                  server,
                                  editing,
                                  ramMb,
                                  cpuShares,
                                  itzgTag,
                                  saving,
                                  error,
                                  onOpen,
                                  onSave,
                                  onCancel,
                                  onChangeRamMb,
                                  onChangeCpuShares,
                                  onChangeItzgTag,
                              }: EditResourcesProps) {
    return (
        <div className="bg-surface border border-border rounded p-4">
            <div className="flex items-center justify-between mb-3">
                <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">
                    Resources
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
                    <InfoRow label="RAM" value={`${server.memory_mb} MB`}/>
                    <InfoRow label="CPU Shares" value={server.cpu_shares === 0 ? "Unlimited" : String(server.cpu_shares)}/>
                    <InfoRow label="Image Tag" value={server.itzg_image_tag}/>
                </div>
            ) : (
                <div className="space-y-3">
                    {error && (
                        <p className="text-[12px] text-error">{error}</p>
                    )}
                    <EditFieldRow label="RAM (MB)">
                        <EditInput
                            type="number"
                            value={ramMb}
                            onChange={(e) => onChangeRamMb(Number(e.target.value))}
                            min={512}
                            step={256}
                        />
                    </EditFieldRow>
                    <EditFieldRow label="CPU Shares">
                        <EditInput
                            type="number"
                            value={cpuShares}
                            onChange={(e) => onChangeCpuShares(Number(e.target.value))}
                            min={0}
                        />
                        <p className="text-[12px] text-text-muted mt-1">0 = unlimited</p>
                    </EditFieldRow>
                    <EditFieldRow label="itzg Image Tag">
                        <EditInput
                            value={itzgTag}
                            onChange={(e) => onChangeItzgTag(e.target.value)}
                            placeholder="latest"
                            list="itzg-tags-edit"
                        />
                        <datalist id="itzg-tags-edit">
                            <option value="latest"/>
                            <option value="java21"/>
                            <option value="java21-jdk"/>
                            <option value="java17"/>
                            <option value="java17-jdk"/>
                            <option value="java11"/>
                            <option value="java8"/>
                        </datalist>
                    </EditFieldRow>
                    <p className="text-[12px] text-text-muted">All changes require a restart to take effect.</p>
                    <SaveCancelRow onSave={onSave} onCancel={onCancel} saving={saving}/>
                </div>
            )}
        </div>
    );
}
