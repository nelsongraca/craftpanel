"use client";

import type React from "react";
import {Pencil} from "lucide-react";
import {SelectField, TextAreaField, TextField} from "@/components/ui/form-elements";

export function EditInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
    return <TextField {...props} surface="bg" fieldSize="sm" />;
}

export function EditSelect(props: React.SelectHTMLAttributes<HTMLSelectElement>) {
    return <SelectField {...props} surface="bg" fieldSize="sm" className="w-full" />;
}

export function EditTextarea(props: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
    return <TextAreaField {...props} surface="bg" fieldSize="sm" />;
}

export function EditFieldRow({label, children}: {label: string; children: React.ReactNode}) {
    return (
        <div className="space-y-1">
            <p className="font-heading text-xs font-bold tracking-wider text-text-muted uppercase">{label}</p>
            {children}
        </div>
    );
}

export function SaveCancelRow({onSave, onCancel, saving}: {onSave: () => void; onCancel: () => void; saving: boolean}) {
    return (
        <div className="flex items-center justify-end gap-2 pt-1">
            <button
                onClick={onCancel}
                className="px-3 py-1 font-heading text-xs font-bold tracking-wider text-text-muted uppercase transition-colors hover:text-text-primary"
            >
                Cancel
            </button>
            <button
                onClick={onSave}
                disabled={saving}
                className="rounded bg-accent px-3 py-1 font-heading text-xs font-bold tracking-wider text-on-accent uppercase transition-colors hover:bg-accent-bright disabled:opacity-50"
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
    canEdit = true,
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
    canEdit?: boolean;
}) {
    return (
        <div className={`rounded border border-border bg-surface p-4${className ? ` ${className}` : ""}`}>
            <div className="mb-3 flex items-center justify-between">
                <p className="font-heading text-xs font-bold tracking-widest text-text-muted uppercase">{label}</p>
                {!editing && canEdit && (
                    <button
                        onClick={onEdit}
                        className="text-text-muted transition-colors hover:text-accent"
                        title={`Edit ${label}`}
                    >
                        <Pencil size={14} strokeWidth={2} />
                    </button>
                )}
            </div>

            {error && editing && <p className="mb-3 text-xs text-error">{error}</p>}

            {!editing ? (
                children[0]
            ) : (
                <>
                    {children[1]}
                    <SaveCancelRow onSave={onSave} onCancel={onCancel} saving={saving} />
                </>
            )}
        </div>
    );
}
