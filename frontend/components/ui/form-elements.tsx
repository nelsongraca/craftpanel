"use client";

import type React from "react";
import {X} from "lucide-react";

const BTN_PRIMARY = "px-4 py-2 rounded text-[12px] font-heading font-bold uppercase tracking-wider bg-accent text-bg hover:bg-accent-bright transition-colors";
const BTN_GHOST = "px-4 py-2 rounded text-[12px] font-heading font-bold uppercase tracking-wider text-text-muted hover:text-text-primary hover:bg-surface-high transition-colors border border-border";

function Modal({title, onClose, children}: { title: string; onClose: () => void; children: React.ReactNode }) {
    return (
        <div role="dialog" aria-modal="true" aria-label={title} className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
            <div className="bg-surface border border-border rounded-md w-full max-w-md shadow-2xl max-h-[90vh] overflow-y-auto">
                <div className="flex items-center justify-between px-5 py-4 border-b border-border sticky top-0 bg-surface z-10">
                    <h2 className="text-[13px] font-heading font-bold uppercase tracking-widest text-text-primary">{title}</h2>
                    <button onClick={onClose} className="text-text-muted hover:text-text-primary transition-colors">
                        <X size={16}/>
                    </button>
                </div>
                <div className="p-5">{children}</div>
            </div>
        </div>
    );
}

function Field({label, children}: { label: string; children: React.ReactNode }) {
    return (
        <div className="flex flex-col gap-1.5">
            <label className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">{label}</label>
            {children}
        </div>
    );
}

// ── Field styling ─────────────────────────────────────────────────────────────

type FieldStyleProps = {
    fieldSize?: "sm" | "md";
    surface?: "bg" | "surface" | "surface-high" | "surface-higher";
};

const SIZE_CLASSES: Record<NonNullable<FieldStyleProps["fieldSize"]>, string> = {
    md: "text-[13px] px-3 py-2",
    sm: "text-[12px] px-2.5 py-1.5",
};

const SURFACE_CLASSES: Record<NonNullable<FieldStyleProps["surface"]>, string> = {
    bg: "bg-bg",
    surface: "bg-surface",
    "surface-high": "bg-surface-high",
    "surface-higher": "bg-surface-higher",
};

const FIELD_BASE = "w-full border border-border rounded font-mono text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent transition-colors disabled:opacity-50";

function fieldClassName({fieldSize = "md", surface = "surface-high"}: FieldStyleProps, extra?: string): string {
    return `${FIELD_BASE} ${SIZE_CLASSES[fieldSize]} ${SURFACE_CLASSES[surface]}${extra ? ` ${extra}` : ""}`;
}

export function TextField({fieldSize, surface, className, ...props}: React.InputHTMLAttributes<HTMLInputElement> & FieldStyleProps) {
    return <input {...props} className={fieldClassName({fieldSize, surface}, className)}/>;
}

export function SelectField({fieldSize, surface, className, ...props}: React.SelectHTMLAttributes<HTMLSelectElement> & FieldStyleProps) {
    return <select {...props} className={fieldClassName({fieldSize, surface}, className)}/>;
}

export function TextAreaField({fieldSize, surface, className, rows, ...props}: React.TextareaHTMLAttributes<HTMLTextAreaElement> & FieldStyleProps) {
    return (
        <textarea
            {...props}
            rows={rows ?? (fieldSize === "sm" ? 2 : 3)}
            className={fieldClassName({fieldSize, surface}, `resize-none${className ? ` ${className}` : ""}`)}
        />
    );
}

export {BTN_PRIMARY, BTN_GHOST, Modal, Field};
