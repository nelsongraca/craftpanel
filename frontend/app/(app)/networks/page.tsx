"use client";

import {useRef, useState} from "react";
import Link from "next/link";
import {Download, Pencil, Plus, Trash2, Upload} from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import {createNetwork, deleteNetwork, exportNetwork, importNetwork, listNetworks, listNodes, updateNetwork} from "@/lib/generated/sdk.gen";
import type {Network, Node} from "@/lib/types";
import type {ServerExportData, NetworkExportData} from "@/lib/generated/types.gen";
import {useAuth} from "@/lib/auth-context";
import {hasPermission, scopedPermissions} from "@/lib/permissions";
import {useResourceList} from "@/lib/hooks/useResourceList";
import {useConfirmDialog} from "@/lib/hooks/useConfirmDialog";

import {BTN_PRIMARY, BTN_GHOST, Field, TextField, SelectField} from "@/components/ui/form-elements";
import {IconActionButton} from "@/components/ui/list-table";
import {SmartList, type SmartListColumn} from "@/components/ui/smart-list";
import {Dialog, DialogContent, DialogHeader, DialogTitle} from "@/components/ui/dialog";

// ── Columns ───────────────────────────────────────────────────────────────────

const NETWORK_COLUMNS: SmartListColumn<Network>[] = [
    {key: 'name', header: 'Name', render: (n) => (
        <Link
            href={`/servers?network=${n.id}`}
            className="font-medium text-text-primary hover:text-accent transition-colors"
        >
            {n.name}
        </Link>
    )},
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
    const canCreate = hasPermission(user?.permissions ?? [], "network.create");
    const [showCreate, setShowCreate] = useState(false);
    const [editing, setEditing] = useState<Network | null>(null);
    const {confirm, dialog} = useConfirmDialog();
    const [showImport, setShowImport] = useState(false);
    const [nodes, setNodes] = useState<Node[]>([]);
    const importFileRef = useRef<HTMLInputElement>(null);
    const [importData, setImportData] = useState<{ name: string; serverCount: number } | null>(null);
    const [importRaw, setImportRaw] = useState<NetworkExportData | null>(null);
    const [importNodeAssignments, setImportNodeAssignments] = useState<Record<string, string>>({});
    const [importError, setImportError] = useState("");
    const [importing, setImporting] = useState(false);

    async function loadNodes() {
        const {data} = await listNodes();
        if (data) setNodes(data);
    }

    async function doExportNetwork(n: Network) {
        const {data, error} = await exportNetwork({path: {id: n.id}});
        if (error || !data) return;
        const blob = new Blob([JSON.stringify(data, null, 2)], {type: "application/json"});
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = `${data.name}.craftpanel.json`;
        a.click();
        URL.revokeObjectURL(url);
    }

    function handleImportFile(e: React.ChangeEvent<HTMLInputElement>) {
        const file = e.target.files?.[0];
        if (!file) return;
        setImportError("");
        setImportData(null);
        setImportRaw(null);
        setImportNodeAssignments({});
        const reader = new FileReader();
        reader.onload = () => {
            try {
                const parsed = JSON.parse(reader.result as string);
                if (!parsed || !parsed.name) {
                    setImportError("Invalid export file: missing network name");
                    return;
                }
                setImportData({name: parsed.name, serverCount: parsed.servers?.length ?? 0});
                setImportRaw(parsed);
                void loadNodes();
            } catch {
                setImportError("Invalid JSON file");
            }
        };
        reader.readAsText(file);
    }

    async function doImport() {
        if (!importRaw) return;
        setImportError("");
        setImporting(true);
        const {error} = await importNetwork({body: {data: importRaw, node_assignments: importNodeAssignments}});
        if (error) {
            setImportError((error as { message?: string }).message ?? "Failed to import network");
            setImporting(false);
            return;
        }
        setImporting(false);
        setShowImport(false);
        setImportData(null);
        setImportRaw(null);
        load();
    }

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

    function requestDelete(network: Network) {
        confirm({
            title: "Delete Network",
            description: `Delete "${network.name}"? This cannot be undone.`,
            destructive: true,
            confirmLabel: "Delete",
            onConfirm: async () => {
                const res = await deleteNetwork({path: {id: network.id}});
                if (res.error) throw new Error((res.error as { message?: string }).message ?? "Failed to delete network");
                load();
            },
        });
    }

    return (
        <div>
            <PageHeader
                title="Networks"
                subtitle="Manage server networks and proxies"
                action={
                    <div className="flex items-center gap-2">
                        {hasPermission(user?.permissions ?? [], "network.create") && (
                            <button onClick={() => setShowImport(true)} className={BTN_GHOST + " flex items-center gap-1.5"}>
                                <Upload size={13} strokeWidth={2.5}/>
                                Import
                            </button>
                        )}
                        {canCreate ? (
                            <button onClick={() => setShowCreate(true)} className={BTN_PRIMARY + " flex items-center gap-1.5"}>
                                <Plus size={13} strokeWidth={2.5}/>
                                New Network
                            </button>
                        ) : undefined}
                    </div>
                }
            />

            <div className="p-6">
                <SmartList
                    items={networks}
                    columns={NETWORK_COLUMNS}
                    keyFor={(n) => n.id}
                    loading={loading}
                    empty="No networks yet. Create one to group servers."
                    actions={(n) => {
                        const perms = scopedPermissions(user?.permissions ?? [], user?.network_permissions ?? {}, n.id);
                        return (
                            <>
                                {hasPermission(perms, "network.view") && (
                                    <IconActionButton icon={<Download size={13}/>} label="Export" onClick={() => void doExportNetwork(n)}/>
                                )}
                                {hasPermission(perms, "network.configure") && (
                                    <IconActionButton icon={<Pencil size={13}/>} label="Edit" onClick={() => setEditing(n)}/>
                                )}
                                {hasPermission(perms, "network.delete") && (
                                    <IconActionButton
                                        icon={<Trash2 size={13}/>}
                                        label={n.server_count > 0 ? "Cannot delete: has member servers" : "Delete"}
                                        danger
                                        disabled={n.server_count > 0}
                                        onClick={() => requestDelete(n)}
                                    />
                                )}
                            </>
                        );
                    }}
                />
            </div>

            {showCreate && (
                <Dialog open onOpenChange={(o) => !o && setShowCreate(false)}>
                    <DialogContent className="sm:max-w-md">
                        <DialogHeader><DialogTitle>New Network</DialogTitle></DialogHeader>
                        <NetworkForm onSubmit={handleCreate} onCancel={() => setShowCreate(false)} submitLabel="Create"/>
                    </DialogContent>
                </Dialog>
            )}

            {editing && (
                <Dialog open onOpenChange={(o) => !o && setEditing(null)}>
                    <DialogContent className="sm:max-w-md">
                        <DialogHeader><DialogTitle>Edit Network</DialogTitle></DialogHeader>
                        <NetworkForm
                            initial={{
                                name: editing.name,
                                description: editing.description ?? "",
                            }}
                            onSubmit={handleEdit}
                            onCancel={() => setEditing(null)}
                            submitLabel="Save"
                        />
                    </DialogContent>
                </Dialog>
            )}

            {dialog}

            {showImport && (
                <Dialog open onOpenChange={(o) => !o && setShowImport(false)}>
                    <DialogContent className="sm:max-w-2xl">
                        <DialogHeader><DialogTitle>Import Network</DialogTitle></DialogHeader>
                    <div className="space-y-4">
                        <input
                            ref={importFileRef}
                            type="file"
                            accept=".json"
                            onChange={handleImportFile}
                            className="block w-full text-xs text-text-muted file:mr-3 file:py-1.5 file:px-3 file:rounded file:border-0 file:text-xs file:font-heading file:font-bold file:uppercase file:tracking-wider file:bg-surface-high file:text-text-primary hover:file:bg-surface-higher"
                        />

                        {importError && <p className="text-xs text-error">{importError}</p>}

                        {importData && (
                            <div className="space-y-4">
                                <p className="text-sm text-text-dim">
                                    Network: <span className="text-text-primary font-medium">{importData.name}</span>
                                    {importData.serverCount > 0 && (
                                        <> — {importData.serverCount} server{importData.serverCount > 1 ? "s" : ""}</>
                                    )}
                                </p>

                                {importRaw?.servers && importRaw.servers.length > 0 && nodes.length > 0 && (
                                    <div className="space-y-2">
                                        <p className="text-xs text-text-muted font-heading font-bold uppercase tracking-wider">Assign Nodes</p>
                                        {importRaw.servers.map((s: ServerExportData) => (
                                            <div key={s.name} className="flex items-center gap-2">
                                                <span className="text-xs text-text-primary w-32 truncate">{s.display_name ?? s.name}</span>
                                                <SelectField
                                                    value={importNodeAssignments[s.name] ?? ""}
                                                    onChange={(e) => setImportNodeAssignments((prev) => ({...prev, [s.name]: e.target.value}))}
                                                    className="flex-1"
                                                >
                                                    <option value="">Select node…</option>
                                                    {nodes.map((node) => (
                                                        <option key={node.id} value={node.id}>{node.display_name}</option>
                                                    ))}
                                                </SelectField>
                                            </div>
                                        ))}
                                    </div>
                                )}

                                {importData.serverCount > 0 && nodes.length === 0 && (
                                    <p className="text-xs text-warning">Loading nodes…</p>
                                )}

                                {importError && <p className="text-xs text-error">{importError}</p>}
                                <div className="flex justify-end gap-2 pt-1">
                                    <button className={BTN_GHOST} onClick={() => setShowImport(false)}>Cancel</button>
                                    <button className={BTN_PRIMARY} disabled={importing || (importRaw?.servers ? importRaw.servers.length > 0 && Object.keys(importNodeAssignments).length < importRaw.servers.length : false)} onClick={doImport}>
                                        {importing ? "Importing…" : "Import"}
                                    </button>
                                </div>
                            </div>
                        )}
                    </div>
                    </DialogContent>
                </Dialog>
            )}
        </div>
    );
}
