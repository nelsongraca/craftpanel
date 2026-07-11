"use client";

import type React from "react";
import {SelectField, TextAreaField, TextField} from "@/components/ui/form-elements";

export function EditInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
    return <TextField {...props} surface="bg" fieldSize="sm"/>;
}

export function EditSelect(props: React.SelectHTMLAttributes<HTMLSelectElement>) {
    return <SelectField {...props} surface="bg" fieldSize="sm"/>;
}

export function EditTextarea(props: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
    return <TextAreaField {...props} surface="bg" fieldSize="sm"/>;
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
