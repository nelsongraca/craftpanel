"use client";

import { useCallback, useEffect, useState } from "react";
import { Plus, Trash2, Radio, Server, AlertTriangle, Pencil } from "lucide-react";
import { getServerPorts, addServerExtraPort, deleteServerExtraPort, updateServer } from "@/lib/generated/sdk.gen";
import type { ServerPortsResponse, ServerExtraPortResponse } from "@/lib/generated/types.gen";
import { Badge } from "@/components/ui/badge";
import { useConfirmDialog } from "@/lib/hooks/useConfirmDialog";

export function PortsTab({
    serverId,
    serverType,
    currentContainerPort,
    currentProtocol,
}: {
    serverId: string;
    serverType: string;
    currentContainerPort?: number | null;
    currentProtocol?: string | null;
}) {
    const [portsData, setPortsData] = useState<ServerPortsResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [showAddModal, setShowAddModal] = useState(false);
    const [formName, setFormName] = useState("");
    const [formContainerPort, setFormContainerPort] = useState("");
    const [formProtocol, setFormProtocol] = useState("TCP");
    const [submitting, setSubmitting] = useState(false);
    const [formError, setFormError] = useState<string | null>(null);

    // Primary port editing state
    const [editingPrimary, setEditingPrimary] = useState(false);
    const [editContainerPort, setEditContainerPort] = useState("");
    const [editProtocol, setEditProtocol] = useState("TCP");
    const [savingPrimary, setSavingPrimary] = useState(false);
    const [primaryError, setPrimaryError] = useState<string | null>(null);

    const { confirm, dialog } = useConfirmDialog();

    const fetchPorts = useCallback(async () => {
        try {
            const { data, error } = await getServerPorts({
                path: { id: serverId },
            });
            if (error) {
                setError("Failed to load ports");
            } else if (data) {
                setPortsData(data);
            }
        } catch {
            setError("Failed to load server ports");
        } finally {
            setLoading(false);
        }
    }, [serverId]);

    useEffect(() => {
        void fetchPorts();
    }, [fetchPorts]);

    const handleAddPort = async (e: React.FormEvent) => {
        e.preventDefault();
        setFormError(null);

        const containerPort = parseInt(formContainerPort, 10);
        if (!formName.trim()) {
            setFormError("Port name is required.");
            return;
        }
        if (isNaN(containerPort) || containerPort <= 0 || containerPort > 65535) {
            setFormError("Valid container port (1-65535) is required.");
            return;
        }

        setSubmitting(true);
        try {
            const { data, error } = await addServerExtraPort({
                path: { id: serverId },
                body: {
                    name: formName.trim(),
                    container_port: containerPort,
                    protocol: formProtocol,
                },
            });
            if (error) {
                setFormError("Failed to add extra port.");
            } else if (data) {
                setShowAddModal(false);
                setFormName("");
                setFormContainerPort("");
                setFormProtocol("TCP");
                await fetchPorts();
            }
        } catch {
            setFormError("An unexpected error occurred.");
        } finally {
            setSubmitting(false);
        }
    };

    function openEditPrimary() {
        setEditContainerPort(currentContainerPort != null ? String(currentContainerPort) : "");
        setEditProtocol(currentProtocol || portsData?.primary_port?.protocol || "TCP");
        setPrimaryError(null);
        setEditingPrimary(true);
    }

    async function handleSavePrimary() {
        setPrimaryError(null);
        setSavingPrimary(true);
        try {
            const body: Record<string, unknown> = {};
            const currentVal = currentContainerPort != null ? String(currentContainerPort) : "";
            if (editContainerPort !== currentVal) {
                body.container_listen_port = editContainerPort ? parseInt(editContainerPort, 10) : null;
            }
            if (editProtocol !== (currentProtocol || "TCP")) {
                body.container_protocol = editProtocol;
            }
            if (Object.keys(body).length === 0) {
                setEditingPrimary(false);
                setSavingPrimary(false);
                return;
            }
            const { error: saveErr } = await updateServer({
                path: { id: serverId },
                body: body as Parameters<typeof updateServer>[0]["body"],
            });
            if (saveErr) {
                setPrimaryError(saveErr.message ?? "Failed to save primary port");
            } else {
                setEditingPrimary(false);
                await fetchPorts();
            }
        } catch {
            setPrimaryError("An unexpected error occurred.");
        } finally {
            setSavingPrimary(false);
        }
    }

    const handleDeletePort = (port: ServerExtraPortResponse) => {
        confirm({
            title: `Delete Port "${port.name}"`,
            description: `Are you sure you want to delete host port ${port.host_port} (${port.protocol}) mapped to container port ${port.container_port}?`,
            destructive: true,
            onConfirm: async () => {
                const { error } = await deleteServerExtraPort({
                    path: { id: serverId, portId: port.id },
                });
                if (error) {
                    setError("Failed to delete port");
                } else {
                    await fetchPorts();
                }
            },
        });
    };

    if (loading) {
        return (
            <div className="p-6 space-y-4">
                <div className="h-24 bg-surface-high animate-pulse rounded border border-border" />
                <div className="h-48 bg-surface-high animate-pulse rounded border border-border" />
            </div>
        );
    }

    const primaryPort = portsData?.primary_port;
    const extraPorts = portsData?.extra_ports ?? [];

    return (
        <div className="p-6 space-y-6">
            {dialog}
            {error && (
                <div className="bg-error/10 border border-error/30 text-error rounded px-4 py-3 text-xs">
                    {error}
                </div>
            )}

            {/* Primary Server Port Section */}
            <div className="bg-surface border border-border rounded p-5 space-y-3">
                <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                        <Server size={18} className="text-accent" />
                        <h2 className="text-sm font-heading font-bold uppercase tracking-wider text-text-primary">
                            Primary Server Port
                        </h2>
                    </div>
                    <div className="flex items-center gap-2">
                        <Badge variant="outline" className="font-mono text-xs">
                            {serverType}
                        </Badge>
                        {!editingPrimary && (
                            <button
                                onClick={openEditPrimary}
                                className="text-text-muted hover:text-accent transition-colors"
                                title="Edit primary port"
                            >
                                <Pencil size={14} strokeWidth={2} />
                            </button>
                        )}
                    </div>
                </div>

                {!editingPrimary ? (
                    <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs font-mono pt-2">
                        <div className="bg-surface-high border border-border p-3 rounded">
                            <span className="text-text-muted block font-heading uppercase text-[10px] tracking-wider mb-1">
                                Host Port
                            </span>
                            <span className="text-text-primary font-bold text-base">
                                {primaryPort?.host_port ?? "N/A"}
                            </span>
                        </div>
                        <div className="bg-surface-high border border-border p-3 rounded">
                            <span className="text-text-muted block font-heading uppercase text-[10px] tracking-wider mb-1">
                                Container Internal Port
                            </span>
                            <span className="text-text-primary font-bold text-base">
                                {primaryPort?.container_port ?? "N/A"}
                            </span>
                        </div>
                        <div className="bg-surface-high border border-border p-3 rounded">
                            <span className="text-text-muted block font-heading uppercase text-[10px] tracking-wider mb-1">
                                Protocol
                            </span>
                            <span className="text-accent font-bold text-base">
                                {primaryPort?.protocol ?? "TCP"}
                            </span>
                        </div>
                    </div>
                ) : (
                    <div className="space-y-3 pt-1">
                        {primaryError && (
                            <div className="bg-error/10 border border-error/30 text-error rounded px-3 py-2 text-xs flex items-center gap-2">
                                <AlertTriangle size={14} className="shrink-0" />
                                <span>{primaryError}</span>
                            </div>
                        )}
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                            <div>
                                <label className="block text-text-dim font-heading uppercase tracking-wider mb-1 text-xs">
                                    Container Internal Port
                                </label>
                                <input
                                    type="number"
                                    value={editContainerPort}
                                    onChange={(e) => setEditContainerPort(e.target.value)}
                                    placeholder={serverType.startsWith("VELOCITY") || serverType.startsWith("BUNGEE") || serverType.startsWith("WATERFALL") ? "25577" : "25565"}
                                    className="w-full bg-surface-high border border-border rounded px-3 py-2 text-text-primary font-mono text-xs focus:outline-none focus:border-accent"
                                    min={1}
                                    max={65535}
                                />
                                <p className="text-[11px] text-text-muted mt-1">Leave empty for default.</p>
                            </div>
                            <div>
                                <label className="block text-text-dim font-heading uppercase tracking-wider mb-1 text-xs">
                                    Protocol
                                </label>
                                <select
                                    value={editProtocol}
                                    onChange={(e) => setEditProtocol(e.target.value)}
                                    className="w-full bg-surface-high border border-border rounded px-3 py-2 text-text-primary font-mono text-xs focus:outline-none focus:border-accent"
                                >
                                    <option value="TCP">TCP</option>
                                    <option value="UDP">UDP</option>
                                </select>
                            </div>
                        </div>
                        <div className="flex justify-end gap-2 pt-1">
                            <button
                                onClick={() => setEditingPrimary(false)}
                                className="px-3 py-1.5 rounded border border-border text-text-dim hover:text-text-primary transition-colors font-heading text-xs font-bold uppercase tracking-wider"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={() => void handleSavePrimary()}
                                disabled={savingPrimary}
                                className="px-4 py-1.5 rounded bg-accent text-bg font-heading text-xs font-bold uppercase tracking-wider hover:bg-accent-bright transition-colors disabled:opacity-50"
                            >
                                {savingPrimary ? "Saving..." : "Save"}
                            </button>
                        </div>
                    </div>
                )}
            </div>

            {/* Extra Exposed Ports Section */}
            <div className="bg-surface border border-border rounded p-5 space-y-4">
                <div className="flex items-center justify-between">
                    <div>
                        <h2 className="text-sm font-heading font-bold uppercase tracking-wider text-text-primary">
                            Extra Exposed Ports
                        </h2>
                        <p className="text-xs text-text-muted mt-0.5">
                            Additional ports exposed for plugins (Dynmap, Geyser, Votifier, etc.).
                        </p>
                    </div>
                    <button
                        onClick={() => setShowAddModal(true)}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded bg-accent text-bg font-heading text-xs font-bold uppercase tracking-wider hover:bg-accent-bright transition-colors"
                    >
                        <Plus size={14} strokeWidth={2.5} />
                        Add Extra Port
                    </button>
                </div>

                {extraPorts.length === 0 ? (
                    <div className="text-center py-8 text-text-muted border border-dashed border-border rounded">
                        <Radio size={24} className="mx-auto mb-2 opacity-50" />
                        <p className="text-xs">No extra ports exposed for this server.</p>
                    </div>
                ) : (
                    <div className="overflow-x-auto border border-border rounded">
                        <table className="w-full text-left text-xs font-mono">
                            <thead className="bg-surface-high text-text-muted uppercase text-[10px] tracking-wider border-b border-border font-heading">
                                <tr>
                                    <th className="px-4 py-2.5">Name</th>
                                    <th className="px-4 py-2.5">Container Port</th>
                                    <th className="px-4 py-2.5">Host Port</th>
                                    <th className="px-4 py-2.5">Protocol</th>
                                    <th className="px-4 py-2.5 text-right">Actions</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border text-text-primary">
                                {extraPorts.map((port: ServerExtraPortResponse) => (
                                    <tr key={port.id} className="hover:bg-surface-high/50">
                                        <td className="px-4 py-3 font-heading font-bold text-text-primary">
                                            {port.name}
                                        </td>
                                        <td className="px-4 py-3">{port.container_port}</td>
                                        <td className="px-4 py-3 font-bold text-accent">{port.host_port}</td>
                                        <td className="px-4 py-3">
                                            <Badge variant="outline" className="text-[10px] px-1.5 py-0">
                                                {port.protocol}
                                            </Badge>
                                        </td>
                                        <td className="px-4 py-3 text-right">
                                            <button
                                                onClick={() => handleDeletePort(port)}
                                                className="p-1 text-text-muted hover:text-error transition-colors rounded hover:bg-surface-high"
                                                title="Delete port"
                                            >
                                                <Trash2 size={14} />
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>

            {/* Add Extra Port Modal */}
            {showAddModal && (
                <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4">
                    <div className="bg-surface border border-border rounded-lg max-w-md w-full p-6 space-y-4 shadow-2xl">
                        <div className="flex items-center justify-between border-b border-border pb-3">
                            <h3 className="text-sm font-heading font-bold uppercase tracking-wider text-text-primary">
                                Add Extra Port
                            </h3>
                            <button
                                onClick={() => setShowAddModal(false)}
                                className="text-text-muted hover:text-text-primary text-xs"
                            >
                                ✕
                            </button>
                        </div>

                        {formError && (
                            <div className="bg-error/10 border border-error/30 text-error rounded px-3 py-2 text-xs flex items-center gap-2">
                                <AlertTriangle size={14} className="shrink-0" />
                                <span>{formError}</span>
                            </div>
                        )}

                        <form onSubmit={handleAddPort} className="space-y-4 text-xs">
                            <div>
                                <label className="block text-text-dim font-heading uppercase tracking-wider mb-1">
                                    Name / Purpose
                                </label>
                                <input
                                    type="text"
                                    placeholder="e.g. Dynmap, Geyser, Votifier"
                                    value={formName}
                                    onChange={(e) => setFormName(e.target.value)}
                                    className="w-full bg-surface-high border border-border rounded px-3 py-2 text-text-primary focus:outline-none focus:border-accent"
                                    required
                                />
                            </div>

                            <div>
                                <label className="block text-text-dim font-heading uppercase tracking-wider mb-1">
                                    Container Internal Port
                                </label>
                                <input
                                    type="number"
                                    placeholder="e.g. 8123, 19132"
                                    value={formContainerPort}
                                    onChange={(e) => setFormContainerPort(e.target.value)}
                                    className="w-full bg-surface-high border border-border rounded px-3 py-2 text-text-primary font-mono focus:outline-none focus:border-accent"
                                    required
                                    min={1}
                                    max={65535}
                                />
                            </div>

                            <div>
                                <label className="block text-text-dim font-heading uppercase tracking-wider mb-1">
                                    Protocol
                                </label>
                                <select
                                    value={formProtocol}
                                    onChange={(e) => setFormProtocol(e.target.value)}
                                    className="w-full bg-surface-high border border-border rounded px-3 py-2 text-text-primary font-mono focus:outline-none focus:border-accent"
                                >
                                    <option value="TCP">TCP (Dynmap, BlueMap HTTP, Votifier)</option>
                                    <option value="UDP">UDP (Geyser Bedrock, Query)</option>
                                </select>
                            </div>

                            <p className="text-[11px] text-text-muted">
                                A free host port will be automatically allocated from node port range.
                            </p>

                            <div className="flex justify-end gap-2 pt-2">
                                <button
                                    type="button"
                                    onClick={() => setShowAddModal(false)}
                                    className="px-3 py-1.5 rounded border border-border text-text-dim hover:text-text-primary transition-colors font-heading text-xs font-bold uppercase tracking-wider"
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    disabled={submitting}
                                    className="px-4 py-1.5 rounded bg-accent text-bg font-heading text-xs font-bold uppercase tracking-wider hover:bg-accent-bright transition-colors disabled:opacity-50"
                                >
                                    {submitting ? "Adding..." : "Add Port"}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}
        </div>
    );
}
