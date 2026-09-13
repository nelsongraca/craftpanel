"use client";

import type React from "react";
import {Pencil} from "lucide-react";
import {SelectField, TextAreaField, TextField} from "@/components/ui/form-elements";

export function EditInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
    return <TextField {...props} surface="bg" fieldSize="sm"/>;
}

export function EditSelect(props: React.SelectHTMLAttributes<HTMLSelectElement>) {
    return <SelectField {...props} surface="bg" fieldSize="sm" className="w-full"/>;
}

export function EditTextarea(props: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
    return <TextAreaField {...props} surface="bg" fieldSize="sm"/>;
}

export function EditFieldRow({label, children}: { label: string; children: React.ReactNode }) {
    return (
        <div className="space-y-1">
            <p className="text-xs font-heading font-bold uppercase tracking-wider text-text-muted">{label}</p>
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
                className="px-3 py-1 text-xs font-heading font-bold uppercase tracking-wider text-text-muted hover:text-text-primary transition-colors"
            >
                Cancel
            </button>
            <button
                onClick={onSave}
                disabled={saving}
                className="px-3 py-1 rounded bg-accent text-bg text-xs font-heading font-bold uppercase tracking-wider hover:bg-accent-bright transition-colors disabled:opacity-50"
            >
                {saving ? "Saving\u2026" : "Save"}
            </button>
        </div>
    );
}

export function EditSection({
                                label,
                                editing,
                                saving,
                                error,
                                onEdit,
                                onCancel,
                                onSave,
                                children,
                                className,
                            }: {
    label: string;
    editing: boolean;
    saving: boolean;
    error: string | null;
    onEdit: () => void;
    onCancel: () => void;
    onSave: () => void;
    children: [React.ReactNode, React.ReactNode];
    className?: string;
}) {
    return (
        <div className={`bg-surface border border-border rounded p-4${className ? ` ${className}` : ""}`}>
            <div className="flex items-center justify-between mb-3">
                <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">
                    {label}
                </p>
                {!editing && (
                    <button
                        onClick={onEdit}
                        className="text-text-muted hover:text-accent transition-colors"
                        title={`Edit ${label}`}
                    >
                        <Pencil size={14} strokeWidth={2}/>
                    </button>
                )}
            </div>

            {error && editing && (
                <p className="text-xs text-error mb-3">{error}</p>
            )}

            {!editing ? children[0] : (
                <>
                    {children[1]}
                    <SaveCancelRow onSave={onSave} onCancel={onCancel} saving={saving}/>
                </>
            )}
        </div>
    );
}
