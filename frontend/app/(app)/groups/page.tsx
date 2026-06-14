"use client";

import {useCallback, useEffect, useState} from "react";
import {Lock, Pencil, Plus, Trash2, X} from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import {createGroup, deleteGroup, listGroups, setGroupPermissions, updateGroup} from "@/lib/generated/sdk.gen";
import type {Group} from "@/lib/types";
import {tryCall} from "@/lib/api";

const INPUT = "w-full bg-surface-high border border-border rounded px-3 py-2 text-[13px] text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent/60";
const BTN_PRIMARY = "px-4 py-2 rounded text-[12px] font-heading font-bold uppercase tracking-wider bg-accent text-bg hover:bg-accent-bright transition-colors";
const BTN_GHOST = "px-4 py-2 rounded text-[12px] font-heading font-bold uppercase tracking-wider text-text-muted hover:text-text-primary hover:bg-surface-high transition-colors border border-border";

// ── Permission nodes ───────────────────────────────────────────────────────────

const PERMISSION_GROUPS: { label: string; nodes: string[] }[] = [
    {
        label: "System",
        nodes: ["system.settings", "system.users", "system.nodes"],
    },
    {
        label: "Server",
        nodes: [
            "server.create", "server.delete", "server.view",
            "server.start", "server.stop", "server.restart",
            "server.configure", "server.resources", "server.files",
            "server.mods", "server.console", "server.export",
            "server.backup", "server.upgrade", "server.migrate",
        ],
    },
];

// ── Helpers ───────────────────────────────────────────────────────────────────

function Modal({title, onClose, children}: { title: string; onClose: () => void; children: React.ReactNode }) {
    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
            <div className="bg-surface border border-border rounded-md w-full max-w-lg shadow-2xl max-h-[90vh] overflow-y-auto">
                <div className="flex items-center justify-between px-5 py-4 border-b border-border sticky top-0 bg-surface z-10">
                    <h2 className="text-[13px] font-heading font-bold uppercase tracking-widest text-text-primary">{title}</h2>
                    <button onClick={onClose} className="text-text-muted hover:text-text-primary transition-colors">
                        <X size={16}/>
                    </button>
                </div>
                <div className="p-5">{children}</div>
            </div>
        </div>
    );
}

function Field({label, children}: { label: string; children: React.ReactNode }) {
    return (
        <div className="flex flex-col gap-1.5">
            <label className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">{label}</label>
            {children}
        </div>
    );
}

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
                <input className={INPUT} value={name} onChange={(e) => setName(e.target.value)} required/>
            </Field>

            <div>
                <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted mb-3">Permissions</p>
                <div className="space-y-4">
                    {PERMISSION_GROUPS.map((group) => (
                        <div key={group.label}>
                            <p className="text-[10px] font-heading text-text-muted mb-2 uppercase tracking-widest">{group.label}</p>
                            <div className="grid grid-cols-2 gap-1.5">
                                {group.nodes.map((node) => (
                                    <label key={node} className="flex items-center gap-2 cursor-pointer group">
                                        <input
                                            type="checkbox"
                                            checked={permissions.has(node)}
                                            onChange={() => togglePerm(node)}
                                            className="accent-amber-500 shrink-0"
                                        />
                                        <span className="text-[11px] font-mono text-text-dim group-hover:text-text-primary transition-colors truncate">
                      {node}
                    </span>
                                    </label>
                                ))}
                            </div>
                        </div>
                    ))}
                </div>
            </div>

            {error && <p className="text-[12px] text-error">{error}</p>}
            <div className="flex justify-end gap-2 pt-1">
                <button type="button" className={BTN_GHOST} onClick={onCancel}>Cancel</button>
                <button type="submit" className={BTN_PRIMARY} disabled={saving}>{saving ? "Saving…" : submitLabel}</button>
            </div>
        </form>
    );
}

// ── Main ──────────────────────────────────────────────────────────────────────

export default function GroupsPage() {
    const [groups, setGroups] = useState<Group[]>([]);
    const [loading, setLoading] = useState(true);
    const [showCreate, setShowCreate] = useState(false);
    const [editing, setEditing] = useState<Group | null>(null);
    const [deleting, setDeleting] = useState<Group | null>(null);
    const [deleteError, setDeleteError] = useState("");

    const load = useCallback(async () => {
        const res = await listGroups();
        if (res.data) setGroups(res.data);
        setLoading(false);
    }, []);

    useEffect(() => {
        load();
    }, [load]);

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

    async function handleDelete() {
        if (!deleting) return;
        setDeleteError("");
        const res = await tryCall(() => deleteGroup({path: {id: deleting.id}}));
        if (!res.ok) {
            setDeleteError(res.error);
            return;
        }
        setDeleting(null);
        load();
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
                {loading ? (
                    <div className="text-[12px] text-text-muted">Loading…</div>
                ) : groups.length === 0 ? (
                    <div className="border-2 border-dashed border-border rounded-md py-10 text-center text-text-muted text-[13px]">No groups.</div>
                ) : (
                    <div className="bg-surface border border-border rounded-md overflow-hidden">
                        <table className="w-full text-[12px]">
                            <thead>
                            <tr className="border-b border-border">
                                <th className="text-left px-5 py-3 text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">Name</th>
                                <th className="text-left px-4 py-3 text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">Permissions</th>
                                <th className="px-4 py-3"/>
                            </tr>
                            </thead>
                            <tbody>
                            {groups.map((g) => (
                                <tr key={g.id} className="border-b border-border/50 hover:bg-surface-high/40">
                                    <td className="px-5 py-3">
                                        <div className="flex items-center gap-2">
                                            {g.is_system && <span title="System group"><Lock size={11} className="text-text-muted shrink-0"/></span>}
                                            <span className="font-medium text-text-primary">{g.name}</span>
                                        </div>
                                    </td>
                                    <td className="px-4 py-3">
                                        <div className="flex flex-wrap gap-1">
                                            {g.permissions.length === 0 ? (
                                                <span className="text-text-muted">—</span>
                                            ) : g.permissions.slice(0, 5).map((p) => (
                                                <span key={p} className="inline-flex items-center px-1.5 py-0.5 rounded text-[9px] font-mono bg-surface-higher border border-border text-text-dim">
                            {p === "*" ? "all" : p}
                          </span>
                                            ))}
                                            {g.permissions.length > 5 && (
                                                <span className="text-[10px] text-text-muted">+{g.permissions.length - 5} more</span>
                                            )}
                                        </div>
                                    </td>
                                    <td className="px-4 py-3">
                                        {!g.is_system && (
                                            <div className="flex items-center gap-1 justify-end">
                                                <button
                                                    onClick={() => setEditing(g)}
                                                    className="p-1.5 rounded hover:bg-surface-higher text-text-muted hover:text-text-primary transition-colors"
                                                    title="Edit"
                                                >
                                                    <Pencil size={13}/>
                                                </button>
                                                <button
                                                    onClick={() => {
                                                        setDeleting(g);
                                                        setDeleteError("");
                                                    }}
                                                    className="p-1.5 rounded hover:bg-surface-higher text-text-muted hover:text-error transition-colors"
                                                    title="Delete"
                                                >
                                                    <Trash2 size={13}/>
                                                </button>
                                            </div>
                                        )}
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>

            {showCreate && (
                <Modal title="New Group" onClose={() => setShowCreate(false)}>
                    <GroupForm onSubmit={handleCreate} onCancel={() => setShowCreate(false)} submitLabel="Create"/>
                </Modal>
            )}

            {editing && (
                <Modal title="Edit Group" onClose={() => setEditing(null)}>
                    <GroupForm
                        initial={{name: editing.name}}
                        initialPermissions={editing.permissions}
                        onSubmit={handleEdit}
                        onCancel={() => setEditing(null)}
                        submitLabel="Save"
                    />
                </Modal>
            )}

            {deleting && (
                <Modal title="Delete Group" onClose={() => setDeleting(null)}>
                    <p className="text-[13px] text-text-dim mb-4">
                        Delete <span className="text-text-primary font-medium">{deleting.name}</span>? All assignments for this group will be removed.
                    </p>
                    {deleteError && <p className="text-[12px] text-error mb-3">{deleteError}</p>}
                    <div className="flex justify-end gap-2">
                        <button className={BTN_GHOST} onClick={() => setDeleting(null)}>Cancel</button>
                        <button
                            className="px-4 py-2 rounded text-[12px] font-heading font-bold uppercase tracking-wider bg-error text-bg hover:opacity-90 transition-opacity"
                            onClick={handleDelete}
                        >
                            Delete
                        </button>
                    </div>
                </Modal>
            )}
        </div>
    );
}
