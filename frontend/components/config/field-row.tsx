"use client";

import {useState} from "react";
import {Plus} from "lucide-react";
import {Switch} from "@/components/ui/switch";
import {SelectField, TextAreaField, TextField} from "@/components/ui/form-elements";
import {TagChip} from "@/components/ui/tag-chip";
import type {FieldDef} from "./field-types";

function ToggleField({
    fieldKey,
    value,
    onChange,
    form,
    setField,
}: {
    fieldKey: string;
    value: string;
    onChange: (val: string) => void;
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

    return <Switch checked={checked} onCheckedChange={handleChange} />;
}

function TagInput({value, onChange}: {value: string; onChange: (val: string) => void}) {
    const tags = value
        ? value
              .split(",")
              .map((t) => t.trim())
              .filter(Boolean)
        : [];
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
                    <TagChip key={tag} label={tag} onRemove={() => removeTag(tag)} />
                ))}
            </div>
            <div className="flex gap-2">
                <input
                    value={inputVal}
                    onChange={(e) => setInputVal(e.target.value)}
                    onKeyDown={(e) => e.key === "Enter" && (e.preventDefault(), addTag())}
                    placeholder="Add entry…"
                    className="w-48 rounded border border-border bg-surface-higher px-2 py-1 font-mono text-xs text-text-primary focus:border-accent/50 focus:outline-none"
                />
                <button onClick={addTag} className="p-1 text-text-muted transition-colors hover:text-text-primary">
                    <Plus className="h-3.5 w-3.5" />
                </button>
            </div>
        </div>
    );
}

export function FieldRow({
    field,
    value,
    onChange,
    form,
    setField,
}: {
    field: FieldDef;
    value: string;
    onChange: (val: string) => void;
    form: Record<string, string>;
    setField: (key: string, value: string) => void;
}) {
    return (
        <div className="flex flex-col gap-2 px-4 py-3 sm:flex-row sm:items-start sm:gap-4">
            <div className="w-full shrink-0 pt-0.5 sm:w-56">
                <p className="text-xs font-medium text-text-primary">{field.label}</p>
                {field.hint && <p className="mt-0.5 text-xs text-text-muted">{field.hint}</p>}
            </div>
            <div className="w-full flex-1">
                {field.type === "toggle" && (
                    <ToggleField
                        fieldKey={field.key}
                        value={value}
                        onChange={onChange}
                        form={form}
                        setField={setField}
                    />
                )}
                {field.type === "select" && (
                    <SelectField
                        value={value}
                        onChange={(e) => onChange(e.target.value)}
                        surface="surface-higher"
                        fieldSize="sm"
                        className="w-full sm:w-48"
                    >
                        {field.options?.map((opt) => (
                            <option key={opt} value={opt}>
                                {opt}
                            </option>
                        ))}
                    </SelectField>
                )}
                {field.type === "text" && (
                    <TextField
                        type="text"
                        value={value}
                        onChange={(e) => onChange(e.target.value)}
                        surface="surface-higher"
                        fieldSize="sm"
                        className="w-full max-w-sm"
                    />
                )}
                {field.type === "number" && (
                    <TextField
                        type="number"
                        value={value}
                        onChange={(e) => onChange(e.target.value)}
                        surface="surface-higher"
                        fieldSize="sm"
                        className="w-32"
                    />
                )}
                {field.type === "textarea" && (
                    <TextAreaField
                        value={value}
                        onChange={(e) => onChange(e.target.value)}
                        rows={4}
                        surface="surface-higher"
                        fieldSize="sm"
                        className="w-full max-w-lg resize-y"
                    />
                )}
                {field.type === "tag-input" && <TagInput value={value} onChange={onChange} />}
            </div>
        </div>
    );
}
