export type FieldType = "text" | "number" | "toggle" | "select" | "textarea" | "tag-input";

export interface FieldDef {
    key: string;
    label: string;
    type: FieldType;
    options?: string[];
    hint?: string;
    showWhen?: { key: string; value: string } | { key: string; nonEmpty: true };
    serverPropertiesMapped: boolean;
    omitIfEmpty?: boolean;
}

export interface Section {
    title: string;
    fields: FieldDef[];
    collapsible?: boolean;
    defaultOpen?: boolean;
}

export type EditableBackend = {
    id?: string;
    backendServerId: string;
    backendName: string;
    order: number;
    displayName: string;
    serverType: string;
    status: string;
};
