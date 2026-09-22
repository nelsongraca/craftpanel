"use client";

import {useState} from "react";
import {InfoRow} from "@/components/edit/info-row";
import {EditFieldRow, EditInput, EditSection} from "@/components/edit/edit-fields";
import {updateServerResources} from "@/lib/generated/sdk.gen";
import type {Server} from "@/lib/types";
import {fmtCpuLimit} from "@/lib/utils/format";

interface EditResourcesProps {
    server: Server;
    onSaved: () => void;
}

export function EditResources({server, onSaved}: EditResourcesProps) {
    const [editing, setEditing] = useState(false);
    const [ramMb, setRamMb] = useState(0);
    const [cpuCores, setCpuCores] = useState(0);
    const [itzgTag, setItzgTag] = useState("");
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    function open() {
        setRamMb(server.memory_mb);
        setCpuCores(server.cpu_limit_millicores / 1000);
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
                body: {
                    memory_mb: ramMb,
                    cpu_limit_millicores: Math.round(cpuCores * 1000),
                    itzg_image_tag: itzgTag || undefined
                },
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
        <EditSection
            label="Resources"
            editing={editing}
            saving={saving}
            error={error}
            onEdit={open}
            onCancel={() => setEditing(false)}
            onSave={() => void save()}
        >
            <div>
                <InfoRow label="RAM" value={`${server.memory_mb} MB`}/>
                <InfoRow label="CPU" value={fmtCpuLimit(server.cpu_limit_millicores)}/>
                <InfoRow label="Image Tag" value={server.itzg_image_tag}/>
            </div>
            <div className="space-y-3">
                <EditFieldRow label="RAM (MB)">
                    <EditInput
                        type="number"
                        value={ramMb}
                        onChange={(e) => setRamMb(Number(e.target.value))}
                        min={64}
                        step={64}
                    />
                </EditFieldRow>
                <EditFieldRow label="CPU Limit (cores)">
                    <EditInput
                        type="number"
                        value={cpuCores}
                        onChange={(e) => setCpuCores(Number(e.target.value))}
                        min={0}
                        step="any"
                    />
                    <p className="text-xs text-text-muted mt-1">Hard CPU cap in cores. 0 = unlimited.</p>
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
                <p className="text-xs text-text-muted">All changes require a restart to take effect.</p>
            </div>
        </EditSection>
    );
}
