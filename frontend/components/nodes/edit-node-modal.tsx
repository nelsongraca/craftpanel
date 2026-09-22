"use client";

import {X} from "lucide-react";
import {useNodeEdit} from "@/lib/hooks/useNodeEdit";
import type {Node} from "@/lib/types";

export function EditNodeModal({node, onClose, onSaved}: { node: Node; onClose: () => void; onSaved: () => void }) {
    const {draft, setField, saving, error, save} = useNodeEdit(node, onSaved);

    async function handleSave() {
        if (await save()) onClose();
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
                            value={draft.displayName}
                            onChange={(e) => setField("displayName", e.target.value)}
                            className="w-full h-8 bg-surface border border-border rounded px-2.5 text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
                        />
                    </label>
                    <div className="grid grid-cols-2 gap-3">
                        <label className="block">
                            <span className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">Port Range Start</span>
                            <input
                                type="number"
                                value={draft.portStart}
                                onChange={(e) => setField("portStart", e.target.value)}
                                className="w-full h-8 bg-surface border border-border rounded px-2.5 text-xs font-mono text-text-primary focus:outline-none focus:border-accent"
                            />
                        </label>
                        <label className="block">
                            <span className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">Port Range End</span>
                            <input
                                type="number"
                                value={draft.portEnd}
                                onChange={(e) => setField("portEnd", e.target.value)}
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
                        onClick={() => void handleSave()}
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
