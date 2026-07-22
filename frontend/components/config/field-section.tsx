"use client";

import {useState} from "react";
import {ChevronDown, ChevronUp} from "lucide-react";
import {Collapsible, CollapsibleContent, CollapsibleTrigger} from "@/components/ui/collapsible";
import {FieldRow} from "./field-row";
import type {Section} from "./field-types";

export function FieldSection({
                                 section,
                                 form,
                                 setField,
                             }: {
    section: Section;
    form: Record<string, string>;
    setField: (key: string, value: string) => void;
}) {
    const [open, setOpen] = useState(section.defaultOpen ?? true);

    const visibleFields = section.fields.filter((f) => {
        if (!f.showWhen) return true;
        if ("value" in f.showWhen) {
            return (form[f.showWhen.key] ?? "") === f.showWhen.value;
        }
        return (form[f.showWhen.key] ?? "").length > 0;
    });

    if (visibleFields.length === 0) return null;

    const content = (
        <div className="divide-y divide-border">
            {visibleFields.map((field) => (
                <FieldRow
                    key={field.key}
                    field={field}
                    value={form[field.key] ?? ""}
                    onChange={(val) => setField(field.key, val)}
                    form={form}
                    setField={setField}
                />
            ))}
        </div>
    );

    if (section.collapsible) {
        return (
            <Collapsible open={open} onOpenChange={setOpen}>
                <div className="border border-border rounded overflow-hidden">
                    <CollapsibleTrigger className="w-full">
                        <div className="px-4 py-2.5 bg-surface-high flex items-center justify-between cursor-pointer hover:bg-surface-higher transition-colors">
                            <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">
                                {section.title}
                            </p>
                            {open ? (
                                <ChevronUp className="w-3.5 h-3.5 text-text-muted"/>
                            ) : (
                                <ChevronDown className="w-3.5 h-3.5 text-text-muted"/>
                            )}
                        </div>
                    </CollapsibleTrigger>
                    <CollapsibleContent>{content}</CollapsibleContent>
                </div>
            </Collapsible>
        );
    }

    return (
        <div className="border border-border rounded overflow-hidden">
            <div className="px-4 py-2.5 bg-surface-high border-b border-border">
                <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">
                    {section.title}
                </p>
            </div>
            {content}
        </div>
    );
}
