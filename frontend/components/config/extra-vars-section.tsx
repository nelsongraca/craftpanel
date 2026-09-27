"use client";

import {Plus, Trash2} from "lucide-react";
import type {EnvVarItem} from "@/lib/types";

export function ExtraVarsSection({
    extraVars,
    onUpdate,
    onRemove,
    onAdd,
}: {
    extraVars: EnvVarItem[];
    onUpdate: (i: number, field: "key" | "value", val: string) => void;
    onRemove: (i: number) => void;
    onAdd: () => void;
}) {
    return (
        <div className="overflow-hidden rounded border border-border">
            <div className="flex items-center justify-between border-b border-border bg-surface-high px-4 py-2.5">
                <p className="font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                    Extra Variables
                </p>
                <button
                    onClick={onAdd}
                    className="flex items-center gap-1 font-heading text-xs font-bold tracking-widest text-text-muted uppercase transition-colors hover:text-text-primary"
                >
                    <Plus className="h-3 w-3" />
                    Add
                </button>
            </div>
            {extraVars.length === 0 ? (
                <div className="px-4 py-3 text-xs text-text-muted">No extra variables.</div>
            ) : (
                <div className="divide-y divide-border text-xs">
                    <div className="hidden border-b border-border sm:flex sm:items-center sm:gap-3 sm:px-4 sm:py-2">
                        <span className="w-5/12 font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                            Key
                        </span>
                        <span className="flex-1 font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                            Value
                        </span>
                        <span className="w-6"></span>
                    </div>
                    {extraVars.map((r, i) => (
                        <div
                            key={i}
                            className="flex flex-col gap-2 p-3 sm:flex-row sm:items-center sm:gap-3 sm:px-4 sm:py-2"
                        >
                            <input
                                value={r.key}
                                onChange={(e) => onUpdate(i, "key", e.target.value)}
                                placeholder="KEY"
                                className="w-full rounded border border-border bg-surface-higher px-2 py-1 font-mono text-xs text-text-primary focus:border-accent/50 focus:outline-none sm:w-5/12"
                            />
                            <textarea
                                value={r.value}
                                onChange={(e) => onUpdate(i, "value", e.target.value)}
                                placeholder="value"
                                rows={2}
                                className="w-full resize-y rounded border border-border bg-surface-higher px-2 py-1 font-mono text-xs text-text-primary focus:border-accent/50 focus:outline-none sm:flex-1"
                            />
                            <button
                                onClick={() => onRemove(i)}
                                className="self-end p-1 text-text-muted transition-colors hover:text-error sm:self-center"
                            >
                                <Trash2 className="h-3.5 w-3.5" />
                            </button>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
