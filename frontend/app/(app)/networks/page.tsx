"use client";

import {useState} from "react";
import {Pencil, Plus, Trash2} from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import {createNetwork, deleteNetwork, listNetworks, updateNetwork} from "@/lib/generated/sdk.gen";
import type {Network} from "@/lib/types";
import {useResourceList} from "@/lib/hooks/useResourceList";

import {INPUT, BTN_PRIMARY, BTN_GHOST, Modal, Field} from "@/components/ui/form-elements";

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
            <Field label="Name">
                <input className={INPUT} value={form.name} onChange={(e) => setForm((f) => ({...f, name: e.target.value}))} required/>
            </Field>
            <Field label="Description">
                <input className={INPUT} value={form.description} placeholder="Optional" onChange={(e) => setForm((f) => ({...f, description: e.target.value}))}/>
            </Field>
            {error && <p className="text-[12px] text-error">{error}</p>}
            <div className="flex justify-end gap-2 pt-1">
                <button type="button" className={BTN_GHOST} onClick={onCancel}>Cancel</button>
                <button type="submit" className={BTN_PRIMARY} disabled={saving}>{saving ? "Saving…" : submitLabel}</button>
            </div>
        </form>
    );
}

// ── Main ──────────────────────────────────────────────────────────────────────

export default function NetworksPage() {
    const {data: networks, initialLoad: loading, reload: load} = useResourceList(listNetworks, {pollMs: 0});
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
            body: {name: form.name, description: form.description || undefined},
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
                    <button onClick={() => setShowCreate(true)} className={BTN_PRIMARY + " flex items-center gap-1.5"}>
                        <Plus size={13} strokeWidth={2.5}/>
                        New Network
                    </button>
                }
            />

            <div className="p-6">
                {loading ? (
                    <div className="text-[12px] text-text-muted">Loading…</div>
                ) : networks.length === 0 ? (
                    <div className="border-2 border-dashed border-border rounded-md py-10 text-center text-text-muted text-[13px]">
                        No networks yet. Create one to group servers.
                    </div>
                ) : (
                    <div className="bg-surface border border-border rounded-md overflow-hidden">
                        <table className="hidden md:table w-full text-[12px]">
                            <thead>
                            <tr className="border-b border-border">
                                <th className="text-left px-5 py-3 text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">Name</th>
                                <th className="text-left px-4 py-3 text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">Servers</th>
                                <th className="text-left px-4 py-3 text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">Description</th>
                                <th className="px-4 py-3"/>
                            </tr>
                            </thead>
                            <tbody>
                            {networks.map((n) => (
                                <tr key={n.id} className="border-b border-border/50 hover:bg-surface-high/40">
                                    <td className="px-5 py-3 font-medium text-text-primary">{n.name}</td>
                                    <td className="px-4 py-3">
                      <span className="inline-flex items-center px-2 py-0.5 rounded text-[12px] font-heading font-bold bg-surface-higher border border-border text-text-dim">
                        {n.server_count}
                      </span>
                                    </td>
                                    <td className="px-4 py-3 text-text-muted truncate max-w-[200px]">{n.description ?? "—"}</td>
                                    <td className="px-4 py-3">
                                        <div className="flex items-center gap-1 justify-end">
                                            <button
                                                onClick={() => setEditing(n)}
                                                className="p-1.5 rounded hover:bg-surface-higher text-text-muted hover:text-text-primary transition-colors"
                                                title="Edit"
                                            >
                                                <Pencil size={13}/>
                                            </button>
                                            <button
                                                onClick={() => {
                                                    setDeleting(n);
                                                    setDeleteError("");
                                                }}
                                                disabled={n.server_count > 0}
                                                className="p-1.5 rounded hover:bg-surface-higher text-text-muted hover:text-error transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
                                                title={n.server_count > 0 ? "Cannot delete: has member servers" : "Delete"}
                                            >
                                                <Trash2 size={13}/>
                                            </button>
                                        </div>
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>

                        {/* Mobile card list (< md) */}
                        <div className="md:hidden divide-y divide-border">
                            {networks.map((n) => (
                                <div key={n.id} className="p-3">
                                    <div className="flex items-start justify-between gap-2">
                                        <div className="min-w-0">
                                            <p className="text-[14px] font-medium text-text-primary truncate">{n.name}</p>
                                            <p className="mt-0.5 font-mono text-[12px] text-text-dim">
                                                {n.server_count} server{n.server_count !== 1 ? "s" : ""}
                                            </p>
                                            {n.description && (
                                                <p className="mt-0.5 text-[12px] text-text-muted truncate">{n.description}</p>
                                            )}
                                        </div>
                                        <div className="flex items-center gap-1 shrink-0">
                                            <button
                                                onClick={() => setEditing(n)}
                                                className="p-1.5 rounded hover:bg-surface-higher text-text-muted hover:text-text-primary transition-colors"
                                                title="Edit"
                                            >
                                                <Pencil size={15}/>
                                            </button>
                                            <button
                                                onClick={() => {
                                                    setDeleting(n);
                                                    setDeleteError("");
                                                }}
                                                disabled={n.server_count > 0}
                                                className="p-1.5 rounded hover:bg-surface-higher text-text-muted hover:text-error transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
                                                title={n.server_count > 0 ? "Cannot delete: has member servers" : "Delete"}
                                            >
                                                <Trash2 size={15}/>
                                            </button>
                                        </div>
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                )}
            </div>

            {showCreate && (
                <Modal title="New Network" onClose={() => setShowCreate(false)}>
                    <NetworkForm onSubmit={handleCreate} onCancel={() => setShowCreate(false)} submitLabel="Create"/>
                </Modal>
            )}

            {editing && (
                <Modal title="Edit Network" onClose={() => setEditing(null)}>
                    <NetworkForm
                        initial={{name: editing.name, description: editing.description ?? ""}}
                        onSubmit={handleEdit}
                        onCancel={() => setEditing(null)}
                        submitLabel="Save"
                    />
                </Modal>
            )}

            {deleting && (
                <Modal title="Delete Network" onClose={() => setDeleting(null)}>
                    <p className="text-[13px] text-text-dim mb-4">
                        Delete <span className="text-text-primary font-medium">{deleting.name}</span>? This cannot be undone.
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
