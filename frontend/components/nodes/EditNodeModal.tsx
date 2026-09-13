"use client";

import {useState} from "react";
import {X} from "lucide-react";
import {updateNode} from "@/lib/generated/sdk.gen";
import type {Node} from "@/lib/types";

export function EditNodeModal({node, onClose, onSaved}: { node: Node; onClose: () => void; onSaved: () => void }) {
    const [displayName, setDisplayName] = useState(node.display_name);
    const [portStart, setPortStart] = useState(String(node.port_range_start));
    const [portEnd, setPortEnd] = useState(String(node.port_range_end));
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

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
            } else {
                onSaved();
                onClose();
            }
        } catch {
            setError("Failed to save");
        } finally {
            setSaving(false);
        }
    }

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
            <div className="bg-surface-higher border border-border rounded shadow-2xl w-[400px] p-6">
                <div className="flex items-center justify-between mb-5">
                    <p className="text-sm font-heading font-bold uppercase tracking-widest text-text-primary">Edit Node</p>
                    <button onClick={onClose} className="text-text-muted hover:text-text-primary"><X size={14}/></button>
                </div>

                {error && (
                    <div className="mb-4 text-xs text-error bg-error/10 border border-error/30 rounded px-3 py-2">{error}</div>
                )}

                <div className="space-y-4">
                    <label className="block">
                        <span className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">Display Name</span>
                        <input
                            value={displayName}
                            onChange={(e) => setDisplayName(e.target.value)}
                            className="w-full h-8 bg-surface border border-border rounded px-2.5 text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
                        />
                    </label>
                    <div className="grid grid-cols-2 gap-3">
                        <label className="block">
                            <span className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">Port Range Start</span>
                            <input
                                type="number"
                                value={portStart}
                                onChange={(e) => setPortStart(e.target.value)}
                                className="w-full h-8 bg-surface border border-border rounded px-2.5 text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
                            />
                        </label>
                        <label className="block">
                            <span className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">Port Range End</span>
                            <input
                                type="number"
                                value={portEnd}
                                onChange={(e) => setPortEnd(e.target.value)}
                                className="w-full h-8 bg-surface border border-border rounded px-2.5 text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
                            />
                        </label>
                    </div>
                </div>

                <div className="flex justify-end gap-2 mt-6">
                    <button
                        onClick={onClose}
                        className="px-3 py-1.5 text-xs font-heading font-bold uppercase tracking-widest text-text-muted border border-border rounded hover:bg-surface-high transition-colors"
                    >
                        Cancel
                    </button>
                    <button
                        onClick={save}
                        disabled={saving}
                        className="px-3 py-1.5 text-xs font-heading font-bold uppercase tracking-widest bg-accent text-bg rounded hover:bg-accent-bright transition-colors disabled:opacity-40"
                    >
                        {saving ? "Saving\u2026" : "Save"}
                    </button>
                </div>
            </div>
        </div>
    );
}