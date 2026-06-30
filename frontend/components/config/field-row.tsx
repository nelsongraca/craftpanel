"use client";

import {useState} from "react";
import {Plus, X} from "lucide-react";
import {Switch} from "@/components/ui/switch";
import type {FieldDef} from "./field-types";

function ToggleField({
                         fieldKey,
                         value,
                         onChange,
                         dimmed,
                         form,
                         setField,
                     }: {
    fieldKey: string;
    value: string;
    onChange: (val: string) => void;
    dimmed: boolean;
    form: Record<string, string>;
    setField: (key: string, value: string) => void;
}) {
    const checked = value === "true";

    function handleChange(next: boolean) {
        onChange(next ? "true" : "false");
        if (next && fieldKey === "USE_AIKAR_FLAGS" && form["USE_MEOWICE_FLAGS"] === "true") {
            setField("USE_MEOWICE_FLAGS", "false");
        }
        if (next && fieldKey === "USE_MEOWICE_FLAGS" && form["USE_AIKAR_FLAGS"] === "true") {
            setField("USE_AIKAR_FLAGS", "false");
        }
    }

    return (
        <Switch
            checked={checked}
            onCheckedChange={handleChange}
            disabled={dimmed}
        />
    );
}

function TagInput({
                      value,
                      onChange,
                      disabled,
                  }: {
    value: string;
    onChange: (val: string) => void;
    disabled: boolean;
}) {
    const tags = value ? value.split(",").map((t) => t.trim()).filter(Boolean) : [];
    const [inputVal, setInputVal] = useState("");

    function addTag() {
        const trimmed = inputVal.trim();
        if (!trimmed || tags.includes(trimmed)) return;
        onChange([...tags, trimmed].join(","));
        setInputVal("");
    }

    function removeTag(tag: string) {
        const next = tags.filter((t) => t !== tag);
        onChange(next.join(","));
    }

    return (
        <div className="space-y-2">
            <div className="flex flex-wrap gap-1">
                {tags.map((tag) => (
                    <span
                        key={tag}
                        className="inline-flex items-center gap-1 bg-surface-higher border border-border rounded px-2 py-0.5 text-[12px] font-mono text-text-primary"
                    >
                        {tag}
                        {!disabled && (
                            <button
                                onClick={() => removeTag(tag)}
                                className="text-text-muted hover:text-error transition-colors"
                            >
                                <X className="w-2.5 h-2.5"/>
                            </button>
                        )}
                    </span>
                ))}
            </div>
            {!disabled && (
                <div className="flex gap-2">
                    <input
                        value={inputVal}
                        onChange={(e) => setInputVal(e.target.value)}
                        onKeyDown={(e) => e.key === "Enter" && (e.preventDefault(), addTag())}
                        placeholder="Add entry…"
                        className="bg-surface-higher border border-border rounded px-2 py-1 text-[12px] font-mono text-text-primary w-48 focus:border-accent/50 focus:outline-none"
                    />
                    <button
                        onClick={addTag}
                        className="p-1 text-text-muted hover:text-text-primary transition-colors"
                    >
                        <Plus className="w-3.5 h-3.5"/>
                    </button>
                </div>
            )}
        </div>
    );
}

export function FieldRow({
                             field,
                             value,
                             onChange,
                             dimmed,
                             form,
                             setField,
                         }: {
    field: FieldDef;
    value: string;
    onChange: (val: string) => void;
    dimmed: boolean;
    form: Record<string, string>;
    setField: (key: string, value: string) => void;
}) {
    const inputCls = `bg-surface-higher border border-border rounded px-2 py-1.5 text-[12px] text-text-primary focus:border-accent/50 focus:outline-none ${dimmed ? "opacity-60" : ""}`;

    return (
        <div className={`px-4 py-3 flex items-start gap-4 ${dimmed ? "opacity-80" : ""}`}>
            <div className="w-56 shrink-0 pt-0.5">
                <p className="text-[12px] text-text-primary font-medium">{field.label}</p>
                {field.hint && (
                    <p className="text-[12px] text-text-muted mt-0.5">{field.hint}</p>
                )}
            </div>
            <div className="flex-1">
                {field.type === "toggle" && (
                    <ToggleField
                        fieldKey={field.key}
                        value={value}
                        onChange={onChange}
                        dimmed={dimmed}
                        form={form}
                        setField={setField}
                    />
                )}
                {field.type === "select" && (
                    <select
                        value={value}
                        onChange={(e) => onChange(e.target.value)}
                        disabled={dimmed}
                        className={`${inputCls} w-48`}
                    >
                        {field.options?.map((opt) => (
                            <option key={opt} value={opt}>
                                {opt}
                            </option>
                        ))}
                    </select>
                )}
                {field.type === "text" && (
                    <input
                        type="text"
                        value={value}
                        onChange={(e) => onChange(e.target.value)}
                        disabled={dimmed}
                        className={`${inputCls} w-full max-w-sm`}
                    />
                )}
                {field.type === "number" && (
                    <input
                        type="number"
                        value={value}
                        onChange={(e) => onChange(e.target.value)}
                        disabled={dimmed}
                        className={`${inputCls} w-32`}
                    />
                )}
                {field.type === "textarea" && (
                    <textarea
                        value={value}
                        onChange={(e) => onChange(e.target.value)}
                        disabled={dimmed}
                        rows={4}
                        className={`${inputCls} w-full max-w-lg font-mono resize-y`}
                    />
                )}
                {field.type === "tag-input" && (
                    <TagInput value={value} onChange={onChange} disabled={dimmed}/>
                )}
            </div>
        </div>
    );
}
