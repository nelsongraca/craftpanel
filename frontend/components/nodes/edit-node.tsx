"use client";

import {useState} from "react";
import {EditFieldRow, EditInput, EditSection} from "@/components/edit/edit-fields";
import {InfoRow} from "@/components/edit/info-row";
import {AgentVersion} from "@/components/nodes/agent-version";
import {useHealth} from "@/lib/hooks/useHealth";
import {useNodeEdit} from "@/lib/hooks/useNodeEdit";
import {allocatable, fmtCpuCores, fmtMb, timeAgo} from "@/lib/utils/format";
import type {Node} from "@/lib/types";

/** Inline-editable Node Info card — mirrors the server detail's EditSection pattern. */
export function EditNode({node, onSaved, canEdit = true}: {node: Node; onSaved: () => void; canEdit?: boolean}) {
    const health = useHealth();
    const [editing, setEditing] = useState(false);
    const {draft, setField, reset, saving, error, save} = useNodeEdit(node, onSaved);

    function open() {
        reset();
        setEditing(true);
    }

    async function handleSave() {
        if (await save()) setEditing(false);
    }

    return (
        <EditSection
            label="Node Info"
            editing={editing}
            saving={saving}
            error={error}
            onEdit={open}
            onCancel={() => setEditing(false)}
            onSave={() => void handleSave()}
            canEdit={canEdit}
        >
            <div>
                <InfoRow label="Display Name" value={node.display_name} />
                <InfoRow label="Hostname" value={node.hostname} />
                <InfoRow label="Public IP" value={node.public_ip} />
                <InfoRow label="Private IP" value={node.private_ip} />
                <InfoRow label="Port Range" value={`${node.port_range_start}–${node.port_range_end}`} />
                <InfoRow
                    label="Agent"
                    value={<AgentVersion version={node.agent_version} masterVersion={health?.masterVersion} />}
                />
                <InfoRow label="RAM Total" value={fmtMb(node.total_ram_mb)} />
                <InfoRow label="RAM Reserved" value={fmtMb(node.reserved_ram_mb)} />
                <InfoRow label="RAM Allocatable" value={fmtMb(allocatable(node.total_ram_mb, node.reserved_ram_mb))} />
                <InfoRow label="CPU Total" value={fmtCpuCores(node.total_cpu_millicores)} />
                <InfoRow label="CPU Reserved" value={fmtCpuCores(node.reserved_cpu_millicores)} />
                <InfoRow
                    label="CPU Allocatable"
                    value={fmtCpuCores(allocatable(node.total_cpu_millicores, node.reserved_cpu_millicores))}
                />
                <InfoRow label="Last Seen" value={node.last_seen_at ? timeAgo(node.last_seen_at) : "-"} />
                <InfoRow label="Created" value={new Date(node.created_at).toLocaleDateString()} />
            </div>
            <div className="space-y-3">
                <EditFieldRow label="Display Name">
                    <EditInput
                        value={draft.displayName}
                        onChange={(e) => setField("displayName", e.target.value)}
                        placeholder={node.display_name}
                    />
                </EditFieldRow>
                <EditFieldRow label="Port Range Start">
                    <EditInput
                        type="number"
                        value={draft.portStart}
                        onChange={(e) => setField("portStart", e.target.value)}
                    />
                </EditFieldRow>
                <EditFieldRow label="Port Range End">
                    <EditInput
                        type="number"
                        value={draft.portEnd}
                        onChange={(e) => setField("portEnd", e.target.value)}
                    />
                </EditFieldRow>
            </div>
        </EditSection>
    );
}
