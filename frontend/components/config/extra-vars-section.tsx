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
        <div className="border border-border rounded overflow-hidden">
            <div className="px-4 py-2.5 bg-surface-high border-b border-border flex items-center justify-between">
                <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">
                    Extra Variables
                </p>
                <button
                    onClick={onAdd}
                    className="flex items-center gap-1 text-xs font-heading font-bold uppercase tracking-widest text-text-muted hover:text-text-primary transition-colors"
                >
                    <Plus className="w-3 h-3"/>
                    Add
                </button>
            </div>
            {extraVars.length === 0 ? (
                <div className="px-4 py-3 text-xs text-text-muted">No extra variables.</div>
            ) : (
                <table className="w-full text-xs">
                    <thead>
                    <tr className="border-b border-border">
                        <th className="px-4 py-2 text-left text-xs font-heading font-bold uppercase tracking-widest text-text-muted w-5/12">
                            Key
                        </th>
                        <th className="px-4 py-2 text-left text-xs font-heading font-bold uppercase tracking-widest text-text-muted">
                            Value
                        </th>
                        <th className="px-4 py-2 w-10"></th>
                    </tr>
                    </thead>
                    <tbody>
                    {extraVars.map((r, i) => (
                        <tr key={i} className="border-b border-border last:border-0">
                            <td className="px-4 py-2">
                                <input
                                    value={r.key}
                                    onChange={(e) => onUpdate(i, "key", e.target.value)}
                                    placeholder="KEY"
                                    className="bg-surface-higher border border-border rounded px-2 py-1 text-xs font-mono text-text-primary w-full focus:border-accent/50 focus:outline-none"
                                />
                            </td>
                            <td className="px-4 py-2">
                                <input
                                    value={r.value}
                                    onChange={(e) => onUpdate(i, "value", e.target.value)}
                                    placeholder="value"
                                    className="bg-surface-higher border border-border rounded px-2 py-1 text-xs font-mono text-text-primary w-full focus:border-accent/50 focus:outline-none"
                                />
                            </td>
                            <td className="px-4 py-2">
                                <button
                                    onClick={() => onRemove(i)}
                                    className="p-1 text-text-muted hover:text-error transition-colors"
                                >
                                    <Trash2 className="w-3.5 h-3.5"/>
                                </button>
                            </td>
                        </tr>
                    ))}
                    </tbody>
                </table>
            )}
        </div>
    );
}
