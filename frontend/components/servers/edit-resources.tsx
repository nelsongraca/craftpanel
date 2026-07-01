"use client";

import {useState} from "react";
import {InfoRow} from "./server-info";
import {EditFieldRow, EditInput, SaveCancelRow} from "./edit-fields";
import {updateServerResources} from "@/lib/generated/sdk.gen";
import type {Server} from "@/lib/types";

interface EditResourcesProps {
    server: Server;
    onSaved: () => void;
}

export function EditResources({server, onSaved}: EditResourcesProps) {
    const [editing, setEditing] = useState(false);
    const [ramMb, setRamMb] = useState(0);
    const [cpuShares, setCpuShares] = useState(0);
    const [itzgTag, setItzgTag] = useState("");
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    function open() {
        setRamMb(server.memory_mb);
        setCpuShares(server.cpu_shares);
        setItzgTag(server.itzg_image_tag);
        setError(null);
        setEditing(true);
    }

    async function save() {
        setSaving(true);
        setError(null);
        try {
            const {error: resErr} = await updateServerResources({
                path: {id: server.id},
                body: {memory_mb: ramMb, cpu_shares: cpuShares, itzg_image_tag: itzgTag || undefined},
            });
            if (resErr) {
                setError(resErr.message ?? "Failed to save");
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
        <div className="bg-surface border border-border rounded p-4">
            <div className="flex items-center justify-between mb-3">
                <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">
                    Resources
                </p>
                {!editing && (
                    <button
                        onClick={open}
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
                            onChange={(e) => setRamMb(Number(e.target.value))}
                            min={512}
                            step={256}
                        />
                    </EditFieldRow>
                    <EditFieldRow label="CPU Shares">
                        <EditInput
                            type="number"
                            value={cpuShares}
                            onChange={(e) => setCpuShares(Number(e.target.value))}
                            min={0}
                        />
                        <p className="text-[12px] text-text-muted mt-1">0 = unlimited</p>
                    </EditFieldRow>
                    <EditFieldRow label="itzg Image Tag">
                        <EditInput
                            value={itzgTag}
                            onChange={(e) => setItzgTag(e.target.value)}
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
                    <SaveCancelRow onSave={() => void save()} onCancel={() => setEditing(false)} saving={saving}/>
                </div>
            )}
        </div>
    );
}
