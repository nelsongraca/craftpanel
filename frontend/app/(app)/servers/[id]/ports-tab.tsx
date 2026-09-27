"use client";

import {useCallback, useEffect, useState} from "react";
import {Plus, Trash2, Radio, Server, AlertTriangle, Pencil} from "lucide-react";
import {getServerPorts, addServerExtraPort, deleteServerExtraPort, updateServer} from "@/lib/generated/sdk.gen";
import type {ServerPortsResponse, ServerExtraPortResponse} from "@/lib/generated/types.gen";
import {Badge} from "@/components/ui/badge";
import {useConfirmDialog} from "@/lib/hooks/useConfirmDialog";
import {Dialog, DialogContent, DialogHeader, DialogTitle} from "@/components/ui/dialog";
import {Empty, EmptyDescription, EmptyMedia} from "@/components/ui/empty";
import {isProxyType} from "@/lib/server-types";

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

    const {confirm, dialog} = useConfirmDialog();

    const fetchPorts = useCallback(async () => {
        try {
            const {data, error} = await getServerPorts({
                path: {id: serverId},
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
            const {data, error} = await addServerExtraPort({
                path: {id: serverId},
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
            const {error: saveErr} = await updateServer({
                path: {id: serverId},
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
                const {error} = await deleteServerExtraPort({
                    path: {id: serverId, portId: port.id},
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
            <div className="space-y-4 px-4 py-6">
                <div className="h-24 animate-pulse rounded border border-border bg-surface-high" />
                <div className="h-48 animate-pulse rounded border border-border bg-surface-high" />
            </div>
        );
    }

    const primaryPort = portsData?.primary_port;
    const extraPorts = portsData?.extra_ports ?? [];

    return (
        <div className="space-y-6 px-4 py-6">
            {dialog}
            {error && (
                <div className="rounded border border-error/30 bg-error/10 px-4 py-3 text-xs text-error">{error}</div>
            )}

            {/* Primary Server Port Section */}
            <div className="space-y-3 rounded border border-border bg-surface p-5">
                <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                        <Server size={18} className="text-accent" />
                        <h2 className="font-heading text-sm font-bold tracking-wider text-text-primary uppercase">
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
                                className="text-text-muted transition-colors hover:text-accent"
                                title="Edit primary port"
                            >
                                <Pencil size={14} strokeWidth={2} />
                            </button>
                        )}
                    </div>
                </div>

                {!editingPrimary ? (
                    <div className="grid grid-cols-1 gap-4 pt-2 font-mono text-xs md:grid-cols-3">
                        <div className="rounded border border-border bg-surface-high p-3">
                            <span className="mb-1 block font-heading text-[10px] tracking-wider text-text-muted uppercase">
                                Host Port
                            </span>
                            <span className="text-base font-bold text-text-primary">
                                {primaryPort?.host_port ?? "N/A"}
                            </span>
                        </div>
                        <div className="rounded border border-border bg-surface-high p-3">
                            <span className="mb-1 block font-heading text-[10px] tracking-wider text-text-muted uppercase">
                                Container Internal Port
                            </span>
                            <span className="text-base font-bold text-text-primary">
                                {primaryPort?.container_port ?? "N/A"}
                            </span>
                        </div>
                        <div className="rounded border border-border bg-surface-high p-3">
                            <span className="mb-1 block font-heading text-[10px] tracking-wider text-text-muted uppercase">
                                Protocol
                            </span>
                            <span className="text-base font-bold text-accent">{primaryPort?.protocol ?? "TCP"}</span>
                        </div>
                    </div>
                ) : (
                    <div className="space-y-3 pt-1">
                        {primaryError && (
                            <div className="flex items-center gap-2 rounded border border-error/30 bg-error/10 px-3 py-2 text-xs text-error">
                                <AlertTriangle size={14} className="shrink-0" />
                                <span>{primaryError}</span>
                            </div>
                        )}
                        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                            <div>
                                <label className="mb-1 block font-heading text-xs tracking-wider text-text-dim uppercase">
                                    Container Internal Port
                                </label>
                                <input
                                    type="number"
                                    value={editContainerPort}
                                    onChange={(e) => setEditContainerPort(e.target.value)}
                                    placeholder={isProxyType(serverType) ? "25577" : "25565"}
                                    className="w-full rounded border border-border bg-surface-high px-3 py-2 font-mono text-xs text-text-primary focus:border-accent focus:outline-none"
                                    min={1}
                                    max={65535}
                                />
                                <p className="mt-1 text-[11px] text-text-muted">Leave empty for default.</p>
                            </div>
                            <div>
                                <label className="mb-1 block font-heading text-xs tracking-wider text-text-dim uppercase">
                                    Protocol
                                </label>
                                <select
                                    value={editProtocol}
                                    onChange={(e) => setEditProtocol(e.target.value)}
                                    className="w-full rounded border border-border bg-surface-high px-3 py-2 font-mono text-xs text-text-primary focus:border-accent focus:outline-none"
                                >
                                    <option value="TCP">TCP</option>
                                    <option value="UDP">UDP</option>
                                </select>
                            </div>
                        </div>
                        <div className="flex justify-end gap-2 pt-1">
                            <button
                                onClick={() => setEditingPrimary(false)}
                                className="rounded border border-border px-3 py-1.5 font-heading text-xs font-bold tracking-wider text-text-dim uppercase transition-colors hover:text-text-primary"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={() => void handleSavePrimary()}
                                disabled={savingPrimary}
                                className="rounded bg-accent px-4 py-1.5 font-heading text-xs font-bold tracking-wider text-bg uppercase transition-colors hover:bg-accent-bright disabled:opacity-50"
                            >
                                {savingPrimary ? "Saving..." : "Save"}
                            </button>
                        </div>
                    </div>
                )}
            </div>

            {/* Extra Exposed Ports Section */}
            <div className="space-y-4 rounded border border-border bg-surface p-5">
                <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                    <div>
                        <h2 className="font-heading text-sm font-bold tracking-wider text-text-primary uppercase">
                            Extra Exposed Ports
                        </h2>
                        <p className="mt-0.5 text-xs text-text-muted">
                            Additional ports exposed for plugins (Dynmap, Geyser, Votifier, etc.).
                        </p>
                    </div>
                    <button
                        onClick={() => setShowAddModal(true)}
                        className="flex shrink-0 items-center gap-1.5 self-start rounded bg-accent px-3 py-1.5 font-heading text-xs font-bold tracking-wider text-bg uppercase transition-colors hover:bg-accent-bright sm:self-auto"
                    >
                        <Plus size={14} strokeWidth={2.5} />
                        Add Extra Port
                    </button>
                </div>

                {extraPorts.length === 0 ? (
                    <Empty>
                        <EmptyMedia variant="icon">
                            <Radio size={20} />
                        </EmptyMedia>
                        <EmptyDescription>No extra ports exposed for this server.</EmptyDescription>
                    </Empty>
                ) : (
                    <div className="overflow-x-auto rounded border border-border">
                        <table className="w-full text-left font-mono text-xs">
                            <thead className="border-b border-border bg-surface-high font-heading text-[10px] tracking-wider text-text-muted uppercase">
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
                                            <Badge variant="outline" className="px-1.5 py-0 text-[10px]">
                                                {port.protocol}
                                            </Badge>
                                        </td>
                                        <td className="px-4 py-3 text-right">
                                            <button
                                                onClick={() => handleDeletePort(port)}
                                                className="rounded p-1 text-text-muted transition-colors hover:bg-surface-high hover:text-error"
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
                <Dialog open onOpenChange={(o) => !o && setShowAddModal(false)}>
                    <DialogContent className="sm:max-w-md">
                        <DialogHeader>
                            <DialogTitle>Add Extra Port</DialogTitle>
                        </DialogHeader>
                        {formError && (
                            <div className="mb-4 flex items-center gap-2 rounded border border-error/30 bg-error/10 px-3 py-2 text-xs text-error">
                                <AlertTriangle size={14} className="shrink-0" />
                                <span>{formError}</span>
                            </div>
                        )}

                        <form onSubmit={handleAddPort} className="space-y-4 text-xs">
                            <div>
                                <label className="mb-1 block font-heading tracking-wider text-text-dim uppercase">
                                    Name / Purpose
                                </label>
                                <input
                                    type="text"
                                    placeholder="e.g. Dynmap, Geyser, Votifier"
                                    value={formName}
                                    onChange={(e) => setFormName(e.target.value)}
                                    className="w-full rounded border border-border bg-surface-high px-3 py-2 text-text-primary focus:border-accent focus:outline-none"
                                    required
                                />
                            </div>

                            <div>
                                <label className="mb-1 block font-heading tracking-wider text-text-dim uppercase">
                                    Container Internal Port
                                </label>
                                <input
                                    type="number"
                                    placeholder="e.g. 8123, 19132"
                                    value={formContainerPort}
                                    onChange={(e) => setFormContainerPort(e.target.value)}
                                    className="w-full rounded border border-border bg-surface-high px-3 py-2 font-mono text-text-primary focus:border-accent focus:outline-none"
                                    required
                                    min={1}
                                    max={65535}
                                />
                            </div>

                            <div>
                                <label className="mb-1 block font-heading tracking-wider text-text-dim uppercase">
                                    Protocol
                                </label>
                                <select
                                    value={formProtocol}
                                    onChange={(e) => setFormProtocol(e.target.value)}
                                    className="w-full rounded border border-border bg-surface-high px-3 py-2 font-mono text-text-primary focus:border-accent focus:outline-none"
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
                                    className="rounded border border-border px-3 py-1.5 font-heading text-xs font-bold tracking-wider text-text-dim uppercase transition-colors hover:text-text-primary"
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    disabled={submitting}
                                    className="rounded bg-accent px-4 py-1.5 font-heading text-xs font-bold tracking-wider text-bg uppercase transition-colors hover:bg-accent-bright disabled:opacity-50"
                                >
                                    {submitting ? "Adding..." : "Add Port"}
                                </button>
                            </div>
                        </form>
                    </DialogContent>
                </Dialog>
            )}
        </div>
    );
}
