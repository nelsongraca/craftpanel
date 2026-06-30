"use client";

import type React from "react";

export function EditInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
    return (
        <input
            {...props}
            className="w-full bg-bg border border-border rounded px-2.5 py-1.5 text-[12px] font-mono text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent transition-colors disabled:opacity-50"
        />
    );
}

export function EditSelect(props: React.SelectHTMLAttributes<HTMLSelectElement>) {
    return (
        <select
            {...props}
            className="w-full bg-bg border border-border rounded px-2.5 py-1.5 text-[12px] font-mono text-text-primary focus:outline-none focus:border-accent transition-colors"
        />
    );
}

export function EditTextarea(props: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
    return (
        <textarea
            {...props}
            rows={2}
            className="w-full bg-bg border border-border rounded px-2.5 py-1.5 text-[12px] font-mono text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent transition-colors resize-none"
        />
    );
}

export function EditFieldRow({label, children}: { label: string; children: React.ReactNode }) {
    return (
        <div className="space-y-1">
            <p className="text-[12px] font-heading font-bold uppercase tracking-wider text-text-muted">{label}</p>
            {children}
        </div>
    );
}

export function SaveCancelRow({
                                  onSave,
                                  onCancel,
                                  saving,
                              }: {
    onSave: () => void;
    onCancel: () => void;
    saving: boolean;
}) {
    return (
        <div className="flex items-center justify-end gap-2 pt-1">
            <button
                onClick={onCancel}
                className="px-3 py-1 text-[12px] font-heading font-bold uppercase tracking-wider text-text-muted hover:text-text-primary transition-colors"
            >
                Cancel
            </button>
            <button
                onClick={onSave}
                disabled={saving}
                className="px-3 py-1 rounded bg-accent text-bg text-[12px] font-heading font-bold uppercase tracking-wider hover:bg-accent-bright transition-colors disabled:opacity-50"
            >
                {saving ? "Saving\u2026" : "Save"}
            </button>
        </div>
    );
}
