"use client";

import {X} from "lucide-react";
import {useNodeEdit} from "@/lib/hooks/useNodeEdit";
import type {Node} from "@/lib/types";

export function EditNodeModal({node, onClose, onSaved}: {node: Node; onClose: () => void; onSaved: () => void}) {
    const {draft, setField, saving, error, save} = useNodeEdit(node, onSaved);

    async function handleSave() {
        if (await save()) onClose();
    }

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
            <div className="w-[400px] rounded border border-border bg-surface-higher p-6 shadow-2xl">
                <div className="mb-5 flex items-center justify-between">
                    <p className="font-heading text-sm font-bold tracking-widest text-text-primary uppercase">
                        Edit Node
                    </p>
                    <button onClick={onClose} className="text-text-muted hover:text-text-primary">
                        <X size={14} />
                    </button>
                </div>

                {error && (
                    <div className="mb-4 rounded border border-error/30 bg-error/10 px-3 py-2 text-xs text-error">
                        {error}
                    </div>
                )}

                <div className="space-y-4">
                    <label className="block">
                        <span className="mb-1 block font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                            Display Name
                        </span>
                        <input
                            value={draft.displayName}
                            onChange={(e) => setField("displayName", e.target.value)}
                            className="h-8 w-full rounded border border-border bg-surface px-2.5 font-mono text-xs text-text-primary focus:border-accent focus:outline-none"
                        />
                    </label>
                    <div className="grid grid-cols-2 gap-3">
                        <label className="block">
                            <span className="mb-1 block font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                                Port Range Start
                            </span>
                            <input
                                type="number"
                                value={draft.portStart}
                                onChange={(e) => setField("portStart", e.target.value)}
                                className="h-8 w-full rounded border border-border bg-surface px-2.5 font-mono text-xs text-text-primary focus:border-accent focus:outline-none"
                            />
                        </label>
                        <label className="block">
                            <span className="mb-1 block font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                                Port Range End
                            </span>
                            <input
                                type="number"
                                value={draft.portEnd}
                                onChange={(e) => setField("portEnd", e.target.value)}
                                className="h-8 w-full rounded border border-border bg-surface px-2.5 font-mono text-xs text-text-primary focus:border-accent focus:outline-none"
                            />
                        </label>
                    </div>
                </div>

                <div className="mt-6 flex justify-end gap-2">
                    <button
                        onClick={onClose}
                        className="rounded border border-border px-3 py-1.5 font-heading text-xs font-bold tracking-widest text-text-muted uppercase transition-colors hover:bg-surface-high"
                    >
                        Cancel
                    </button>
                    <button
                        onClick={() => void handleSave()}
                        disabled={saving}
                        className="rounded bg-accent px-3 py-1.5 font-heading text-xs font-bold tracking-widest text-on-accent uppercase transition-colors hover:bg-accent-bright disabled:opacity-40"
                    >
                        {saving ? "Saving\u2026" : "Save"}
                    </button>
                </div>
            </div>
        </div>
    );
}
