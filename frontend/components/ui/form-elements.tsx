"use client";

import type React from "react";
import {Children, isValidElement} from "react";
import {Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectTrigger, SelectValue} from "@/components/ui/select";

const BTN_PRIMARY = "px-4 py-2 rounded text-xs font-heading font-bold uppercase tracking-wider bg-accent text-bg hover:bg-accent-bright transition-colors";
const BTN_GHOST = "px-4 py-2 rounded text-xs font-heading font-bold uppercase tracking-wider text-text-muted hover:text-text-primary hover:bg-surface-high transition-colors border border-border";

function Field({label, children, htmlFor}: { label: string; children: React.ReactNode; htmlFor?: string }) {
    return (
        <div className="flex flex-col gap-1.5">
            <label htmlFor={htmlFor} className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">{label}</label>
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
    md: "text-sm px-3 py-2",
    sm: "text-xs px-2.5 py-1.5",
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

type OptionElement = React.ReactElement<React.OptionHTMLAttributes<HTMLOptionElement>>;
type OptGroupElement = React.ReactElement<React.OptgroupHTMLAttributes<HTMLOptGroupElement>>;

function isOptionElement(node: unknown): node is OptionElement {
    return isValidElement(node) && node.type === "option";
}

function isOptGroupElement(node: unknown): node is OptGroupElement {
    return isValidElement(node) && node.type === "optgroup";
}

function flattenOptions(children: React.ReactNode): OptionElement[] {
    return Children.toArray(children).flatMap((node) => {
        if (isOptGroupElement(node)) return flattenOptions(node.props.children);
        if (isOptionElement(node)) return [node];
        return [];
    });
}

export function SelectField({fieldSize, surface, className, children, value, onChange, disabled, required, id}: React.SelectHTMLAttributes<HTMLSelectElement> & FieldStyleProps) {
    const options = flattenOptions(children);
    const selected = options.find((opt) => String(opt.props.value) === String(value));
    const groups = Children.toArray(children);
    const hasGroups = groups.some(isOptGroupElement);
    return (
        <Select
            value={value === undefined ? undefined : String(value)}
            onValueChange={(next) => onChange?.({target: {value: next}} as React.ChangeEvent<HTMLSelectElement>)}
            disabled={disabled}
            required={required}
        >
            <SelectTrigger id={id} className={fieldClassName({fieldSize, surface}, className)}>
                <SelectValue>{selected?.props.children ?? selected?.props.value}</SelectValue>
            </SelectTrigger>
            <SelectContent>
                {hasGroups
                    ? groups.map((node, i) =>
                        isOptGroupElement(node) ? (
                            <SelectGroup key={node.props.label ?? i}>
                                <SelectLabel>{node.props.label}</SelectLabel>
                                {flattenOptions(node.props.children).map((opt) => (
                                    <SelectItem key={String(opt.props.value)} value={String(opt.props.value)} disabled={opt.props.disabled}>
                                        {opt.props.children}
                                    </SelectItem>
                                ))}
                            </SelectGroup>
                        ) : isOptionElement(node) ? (
                            <SelectItem key={String(node.props.value)} value={String(node.props.value)} disabled={node.props.disabled}>
                                {node.props.children}
                            </SelectItem>
                        ) : null
                    )
                    : options.map((opt) => (
                        <SelectItem key={String(opt.props.value)} value={String(opt.props.value)} disabled={opt.props.disabled}>
                            {opt.props.children}
                        </SelectItem>
                    ))}
            </SelectContent>
        </Select>
    );
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

export {BTN_PRIMARY, BTN_GHOST, Field};
