"use client";

import {useState} from "react";
import {EditFieldRow, EditInput, EditSection} from "@/components/servers/edit-fields";
import {InfoRow} from "@/components/servers/server-info";
import {AgentVersion} from "@/components/nodes/agent-version";
import {updateNode} from "@/lib/generated/sdk.gen";
import {useHealth} from "@/lib/hooks/useHealth";
import {fmtCpuCores, fmtMb, timeAgo} from "@/lib/utils/format";
import type {Node} from "@/lib/types";

/** Inline-editable Node Info card — mirrors the server detail's EditSection pattern. */
export function EditNode({node, onSaved, canEdit = true}: { node: Node; onSaved: () => void; canEdit?: boolean }) {
    const health = useHealth();
    const [editing, setEditing] = useState(false);
    const [displayName, setDisplayName] = useState("");
    const [portStart, setPortStart] = useState("");
    const [portEnd, setPortEnd] = useState("");
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    function open() {
        setDisplayName(node.display_name);
        setPortStart(String(node.port_range_start));
        setPortEnd(String(node.port_range_end));
        setError(null);
        setEditing(true);
    }

    async function save() {
        setSaving(true);
        setError(null);
        try {
            const {error: e} = await updateNode({
                path: {id: node.id},
                body: {
                    display_name: displayName || undefined,
                    port_range_start: portStart ? parseInt(portStart) : undefined,
                    port_range_end: portEnd ? parseInt(portEnd) : undefined,
                },
            });
            if (e) {
                setError(e.message ?? "Failed to save");
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
            label="Node Info"
            editing={editing}
            saving={saving}
            error={error}
            onEdit={open}
            onCancel={() => setEditing(false)}
            onSave={() => void save()}
            canEdit={canEdit}
        >
            <div>
                <InfoRow label="Display Name" value={node.display_name}/>
                <InfoRow label="Hostname" value={node.hostname}/>
                <InfoRow label="Public IP" value={node.public_ip}/>
                <InfoRow label="Private IP" value={node.private_ip}/>
                <InfoRow label="Port Range" value={`${node.port_range_start}–${node.port_range_end}`}/>
                <InfoRow label="Agent"
                         value={<AgentVersion version={node.agent_version} masterVersion={health?.masterVersion}/>}/>
                <InfoRow label="RAM Total" value={fmtMb(node.total_ram_mb)}/>
                <InfoRow label="RAM Reserved" value={fmtMb(node.reserved_ram_mb)}/>
                <InfoRow label="CPU Total" value={fmtCpuCores(node.total_cpu_millicores)}/>
                <InfoRow label="CPU Reserved" value={fmtCpuCores(node.reserved_cpu_millicores)}/>
                <InfoRow label="Last Seen" value={node.last_seen_at ? timeAgo(node.last_seen_at) : "-"}/>
                <InfoRow label="Created" value={new Date(node.created_at).toLocaleDateString()}/>
            </div>
            <div className="space-y-3">
                <EditFieldRow label="Display Name">
                    <EditInput
                        value={displayName}
                        onChange={(e) => setDisplayName(e.target.value)}
                        placeholder={node.display_name}
                    />
                </EditFieldRow>
                <EditFieldRow label="Port Range Start">
                    <EditInput
                        type="number"
                        value={portStart}
                        onChange={(e) => setPortStart(e.target.value)}
                    />
                </EditFieldRow>
                <EditFieldRow label="Port Range End">
                    <EditInput
                        type="number"
                        value={portEnd}
                        onChange={(e) => setPortEnd(e.target.value)}
                    />
                </EditFieldRow>
            </div>
        </EditSection>
    );
}
