"use client";

import {useState} from "react";
import {Pencil, Plus, Trash2} from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import {createNetwork, deleteNetwork, listNetworks, updateNetwork} from "@/lib/generated/sdk.gen";
import type {Network} from "@/lib/types";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import {useResourceList} from "@/lib/hooks/useResourceList";

import {BTN_PRIMARY, BTN_GHOST, Modal, Field, TextField} from "@/components/ui/form-elements";
import {IconActionButton} from "@/components/ui/list-table";
import {SmartList, type SmartListColumn} from "@/components/ui/smart-list";

// ── Columns ───────────────────────────────────────────────────────────────────

const NETWORK_COLUMNS: SmartListColumn<Network>[] = [
    {key: 'name', header: 'Name', render: (n) => <span className="font-medium text-text-primary">{n.name}</span>},
    {key: 'servers', header: 'Servers', render: (n) => (
        <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-heading font-bold bg-surface-higher border border-border text-text-dim">
            {n.server_count}
        </span>
    )},
    {key: 'description', header: 'Description', render: (n) => <span className="text-text-muted truncate max-w-[200px] block">{n.description ?? "-"}</span>},
]

// ── Network form ──────────────────────────────────────────────────────────────

interface NetworkFormState {
    name: string;
    description: string;
}

function NetworkForm({
                         initial,
                         onSubmit,
                         onCancel,
                         submitLabel,
                     }: {
    initial?: Partial<NetworkFormState>;
    onSubmit: (s: NetworkFormState) => Promise<void>;
    onCancel: () => void;
    submitLabel: string;
}) {
    const [form, setForm] = useState<NetworkFormState>({
        name: initial?.name ?? "",
        description: initial?.description ?? "",
    });
    const [error, setError] = useState("");
    const [saving, setSaving] = useState(false);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError("");
        setSaving(true);
        try {
            await onSubmit(form);
        } catch (err: unknown) {
            setError(err instanceof Error ? err.message : "An error occurred");
        } finally {
            setSaving(false);
        }
    }

    return (
        <form onSubmit={handleSubmit} className="space-y-4">
            <Field label="Name" htmlFor="network-name">
                <TextField id="network-name" value={form.name} onChange={(e) => setForm((f) => ({...f, name: e.target.value}))} required/>
            </Field>
            <Field label="Description" htmlFor="network-description">
                <TextField id="network-description" value={form.description} placeholder="Optional" onChange={(e) => setForm((f) => ({...f, description: e.target.value}))}/>
            </Field>

            {error && <p className="text-xs text-error">{error}</p>}
            <div className="flex justify-end gap-2 pt-1">
                <button type="button" className={BTN_GHOST} onClick={onCancel}>Cancel</button>
                <button type="submit" className={BTN_PRIMARY} disabled={saving}>{saving ? "Saving…" : submitLabel}</button>
            </div>
        </form>
    );
}

// ── Main ──────────────────────────────────────────────────────────────────────

export default function NetworksPage() {
    const {user} = useAuth();
    const {data: networks, initialLoad: loading, reload: load} = useResourceList(listNetworks, [], {pollMs: 0});
    const canCreate = hasPermission(user?.permissions ?? [], "server.create");
    const [showCreate, setShowCreate] = useState(false);
    const [editing, setEditing] = useState<Network | null>(null);
    const [deleting, setDeleting] = useState<Network | null>(null);
    const [deleteError, setDeleteError] = useState("");

    async function handleCreate(form: NetworkFormState) {
        const res = await createNetwork({
            body: {
                name: form.name,
                description: form.description || undefined,
            },
        });
        if (res.error) throw new Error((res.error as { message?: string }).message ?? "Failed to create network");
        setShowCreate(false);
        load();
    }

    async function handleEdit(form: NetworkFormState) {
        if (!editing) return;
        const res = await updateNetwork({
            path: {id: editing.id},
            body: {
                name: form.name,
                description: form.description || undefined,
            },
        });
        if (res.error) throw new Error((res.error as { message?: string }).message ?? "Failed to update network");
        setEditing(null);
        load();
    }

    async function handleDelete() {
        if (!deleting) return;
        setDeleteError("");
        const res = await deleteNetwork({path: {id: deleting.id}});
        if (res.error) {
            setDeleteError((res.error as { message?: string }).message ?? "Failed to delete network");
            return;
        }
        setDeleting(null);
        load();
    }

    return (
        <div>
            <PageHeader
                title="Networks"
                subtitle="Manage server networks and proxies"
                action={
                    canCreate ? (
                        <button onClick={() => setShowCreate(true)} className={BTN_PRIMARY + " flex items-center gap-1.5"}>
                            <Plus size={13} strokeWidth={2.5}/>
                            New Network
                        </button>
                    ) : undefined
                }
            />

            <div className="p-6">
                <SmartList
                    items={networks}
                    columns={NETWORK_COLUMNS}
                    keyFor={(n) => n.id}
                    loading={loading}
                    empty="No networks yet. Create one to group servers."
                    actions={(n) => (
                        <>
                            <IconActionButton icon={<Pencil size={13}/>} label="Edit" onClick={() => setEditing(n)}/>
                            <IconActionButton
                                icon={<Trash2 size={13}/>}
                                label={n.server_count > 0 ? "Cannot delete: has member servers" : "Delete"}
                                danger
                                disabled={n.server_count > 0}
                                onClick={() => {
                                    setDeleting(n);
                                    setDeleteError("");
                                }}
                            />
                        </>
                    )}
                />
            </div>

            {showCreate && (
                <Modal title="New Network" onClose={() => setShowCreate(false)}>
                    <NetworkForm onSubmit={handleCreate} onCancel={() => setShowCreate(false)} submitLabel="Create"/>
                </Modal>
            )}

            {editing && (
                <Modal title="Edit Network" onClose={() => setEditing(null)}>
                    <NetworkForm
                        initial={{
                            name: editing.name,
                            description: editing.description ?? "",
                        }}
                        onSubmit={handleEdit}
                        onCancel={() => setEditing(null)}
                        submitLabel="Save"
                    />
                </Modal>
            )}

            {deleting && (
                <Modal title="Delete Network" onClose={() => setDeleting(null)}>
                    <p className="text-sm text-text-dim mb-4">
                        Delete <span className="text-text-primary font-medium">{deleting.name}</span>? This cannot be undone.
                    </p>
                    {deleteError && <p className="text-xs text-error mb-3">{deleteError}</p>}
                    <div className="flex justify-end gap-2">
                        <button className={BTN_GHOST} onClick={() => setDeleting(null)}>Cancel</button>
                        <button
                            className="px-4 py-2 rounded text-xs font-heading font-bold uppercase tracking-wider bg-error text-bg hover:opacity-90 transition-opacity"
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
