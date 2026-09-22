"use client";

import {useState} from "react";
import {Lock, Pencil, Plus, Trash2} from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import {createGroup, deleteGroup, listGroups, setGroupPermissions, updateGroup} from "@/lib/generated/sdk.gen";
import type {Group} from "@/lib/types";
import {useResourceList} from "@/lib/hooks/useResourceList";
import {useConfirmDialog} from "@/lib/hooks/useConfirmDialog";
import {BTN_PRIMARY, BTN_GHOST, Field, TextField} from "@/components/ui/form-elements";
import {IconActionButton} from "@/components/ui/list-table";
import {SmartList, type SmartListColumn} from "@/components/ui/smart-list";
import {Dialog, DialogContent, DialogHeader, DialogTitle} from "@/components/ui/dialog";


// ── Permission nodes ───────────────────────────────────────────────────────────

const PERMISSION_GROUPS: { label: string; nodes: string[] }[] = [
    {
        label: "System",
        nodes: ["system.settings", "system.users", "system.nodes", "system.groups", "system.alerts"],
    },
    {
        label: "Server",
        nodes: [
            "server.create", "server.delete", "server.view",
            "server.start", "server.stop", "server.force_stop", "server.restart",
            "server.configure", "server.resources", "server.expires", "server.disable", "server.files",
            "server.dir_override",
            "server.mods", "server.console", "server.export",
            "server.backup", "server.migrate",
        ],
    },
    {
        label: "Network",
        nodes: ["network.view", "network.create", "network.configure", "network.delete"],
    },
];

// ── Columns ───────────────────────────────────────────────────────────────────

const GROUP_COLUMNS: SmartListColumn<Group>[] = [
    {key: 'name', header: 'Name', render: (g) => (
        <div className="flex items-center gap-2">
            {g.is_system && <Lock size={11} className="text-text-muted shrink-0"/>}
            <span className="font-medium text-text-primary">{g.name}</span>
        </div>
    )},
    {key: 'permissions', header: 'Permissions', render: (g) => (
        <div className="flex flex-wrap gap-1">
            {g.permissions.length === 0 ? (
                <span className="text-text-muted">-</span>
            ) : g.permissions.slice(0, 5).map((p) => (
                <span key={p} className="inline-flex items-center px-1.5 py-0.5 rounded text-[9px] font-mono bg-surface-higher border border-border text-text-dim">
                    {p === "*" ? "all" : p}
                </span>
            ))}
            {g.permissions.length > 5 && (
                <span className="text-xs text-text-muted">+{g.permissions.length - 5} more</span>
            )}
        </div>
    )},
]

// ── Group form ────────────────────────────────────────────────────────────────

function GroupForm({
                       initial,
                       initialPermissions,
                       onSubmit,
                       onCancel,
                       submitLabel,
                   }: {
    initial?: { name: string };
    initialPermissions?: string[];
    onSubmit: (name: string, permissions: string[]) => Promise<void>;
    onCancel: () => void;
    submitLabel: string;
}) {
    const [name, setName] = useState(initial?.name ?? "");
    const [permissions, setPermissions] = useState<Set<string>>(new Set(initialPermissions ?? []));
    const [error, setError] = useState("");
    const [saving, setSaving] = useState(false);

    function togglePerm(perm: string) {
        setPermissions((prev) => {
            const next = new Set(prev);
            if (next.has(perm)) next.delete(perm); else next.add(perm);
            return next;
        });
    }

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError("");
        setSaving(true);
        try {
            await onSubmit(name, [...permissions]);
        } catch (err: unknown) {
            setError(err instanceof Error ? err.message : "Failed");
        } finally {
            setSaving(false);
        }
    }

    return (
        <form onSubmit={handleSubmit} className="space-y-5">
            <Field label="Name">
                <TextField value={name} onChange={(e) => setName(e.target.value)} required/>
            </Field>

            <div>
                <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-3">Permissions</p>
                <div className="space-y-4">
                    {PERMISSION_GROUPS.map((group) => (
                        <div key={group.label}>
                            <p className="text-xs font-heading text-text-muted mb-2 uppercase tracking-widest">{group.label}</p>
                            <div className="grid grid-cols-2 gap-1.5">
                                {group.nodes.map((node) => (
                                    <label key={node} className="flex items-center gap-2 cursor-pointer group">
                                        <input
                                            type="checkbox"
                                            checked={permissions.has(node)}
                                            onChange={() => togglePerm(node)}
                                            className="accent-accent shrink-0"
                                        />
                                        <span className="text-xs font-mono text-text-dim group-hover:text-text-primary transition-colors truncate">
                      {node}
                    </span>
                                    </label>
                                ))}
                            </div>
                        </div>
                    ))}
                </div>
            </div>

            {error && <p className="text-xs text-error">{error}</p>}
            <div className="flex justify-end gap-2 pt-1">
                <button type="button" className={BTN_GHOST} onClick={onCancel}>Cancel</button>
                <button type="submit" className={BTN_PRIMARY} disabled={saving}>{saving ? "Saving…" : submitLabel}</button>
            </div>
        </form>
    );
}

// ── Main ──────────────────────────────────────────────────────────────────────

export default function GroupsPage() {
    const {data: groups, initialLoad: loading, reload: load} = useResourceList(listGroups, [], {pollMs: 0});
    const [showCreate, setShowCreate] = useState(false);
    const [editing, setEditing] = useState<Group | null>(null);
    const {confirm, dialog} = useConfirmDialog();

    async function handleCreate(name: string, permissions: string[]) {
        const createRes = await createGroup({body: {name}});
        if (createRes.error) throw new Error((createRes.error as { message?: string }).message ?? "Failed to create group");
        const groupId = createRes.data!.id;
        if (permissions.length > 0) {
            const permRes = await setGroupPermissions({path: {id: groupId}, body: {permissions}});
            if (permRes.error) throw new Error((permRes.error as { message?: string }).message ?? "Failed to set permissions");
        }
        setShowCreate(false);
        load();
    }

    async function handleEdit(name: string, permissions: string[]) {
        if (!editing) return;
        const nameRes = await updateGroup({path: {id: editing.id}, body: {name}});
        if (nameRes.error) throw new Error((nameRes.error as { message?: string }).message ?? "Failed to update group");
        const permRes = await setGroupPermissions({path: {id: editing.id}, body: {permissions}});
        if (permRes.error) throw new Error((permRes.error as { message?: string }).message ?? "Failed to set permissions");
        setEditing(null);
        load();
    }

    function requestDelete(group: Group) {
        confirm({
            title: "Delete Group",
            description: `Delete "${group.name}"? All assignments for this group will be removed.`,
            destructive: true,
            confirmLabel: "Delete",
            onConfirm: async () => {
                const {error} = await deleteGroup({path: {id: group.id}});
                if (error) throw new Error(error.message ?? "Failed to delete group");
                load();
            },
        });
    }

    return (
        <div>
            <PageHeader
                title="Groups"
                subtitle="Manage permission groups"
                action={
                    <button onClick={() => setShowCreate(true)} className={BTN_PRIMARY + " flex items-center gap-1.5"}>
                        <Plus size={13} strokeWidth={2.5}/>
                        New Group
                    </button>
                }
            />

            <div className="p-6">
                <SmartList
                    items={groups}
                    columns={GROUP_COLUMNS}
                    keyFor={(g) => g.id}
                    loading={loading}
                    empty="No groups."
                    actions={(g) => g.is_system ? null : (
                        <>
                            <IconActionButton icon={<Pencil size={13}/>} label="Edit" onClick={() => setEditing(g)}/>
                            <IconActionButton
                                icon={<Trash2 size={13}/>}
                                label="Delete"
                                danger
                                onClick={() => requestDelete(g)}
                            />
                        </>
                    )}
                />
            </div>

            {showCreate && (
                <Dialog open onOpenChange={(o) => !o && setShowCreate(false)}>
                    <DialogContent className="sm:max-w-md">
                        <DialogHeader>
                            <DialogTitle>New Group</DialogTitle>
                        </DialogHeader>
                        <GroupForm onSubmit={handleCreate} onCancel={() => setShowCreate(false)} submitLabel="Create"/>
                    </DialogContent>
                </Dialog>
            )}

            {editing && (
                <Dialog open onOpenChange={(o) => !o && setEditing(null)}>
                    <DialogContent className="sm:max-w-md">
                        <DialogHeader>
                            <DialogTitle>Edit Group</DialogTitle>
                        </DialogHeader>
                        <GroupForm
                            initial={{name: editing.name}}
                            initialPermissions={editing.permissions}
                            onSubmit={handleEdit}
                            onCancel={() => setEditing(null)}
                            submitLabel="Save"
                        />
                    </DialogContent>
                </Dialog>
            )}

            {dialog}
        </div>
    );
}
