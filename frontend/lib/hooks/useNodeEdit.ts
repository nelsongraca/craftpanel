"use client";

import {useState} from "react";
import {updateNode} from "@/lib/generated/sdk.gen";
import type {Node} from "@/lib/types";

export interface NodeEditDraft {
    displayName: string;
    portStart: string;
    portEnd: string;
}

function fromNode(node: Node): NodeEditDraft {
    return {
        displayName: node.display_name,
        portStart: String(node.port_range_start),
        portEnd: String(node.port_range_end),
    };
}

/**
 * The one owner of a node's editable fields and their save. Both the list's edit modal and the
 * detail's inline edit use it; each renders its own shell and calls [reset] when it opens.
 */
export function useNodeEdit(node: Node, onSaved: () => void) {
    const [draft, setDraft] = useState<NodeEditDraft>(() => fromNode(node));
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    function setField(field: keyof NodeEditDraft, value: string) {
        setDraft((d) => ({...d, [field]: value}));
    }

    function reset() {
        setDraft(fromNode(node));
        setError(null);
    }

    /** Saves the draft; returns true on success (the caller closes its editor). */
    async function save(): Promise<boolean> {
        setSaving(true);
        setError(null);
        try {
            const {error: e} = await updateNode({
                path: {id: node.id},
                body: {
                    display_name: draft.displayName || undefined,
                    port_range_start: draft.portStart ? parseInt(draft.portStart) : undefined,
                    port_range_end: draft.portEnd ? parseInt(draft.portEnd) : undefined,
                },
            });
            if (e) {
                setError(e.message ?? "Failed to save");
                return false;
            }
            onSaved();
            return true;
        } catch {
            setError("Failed to save");
            return false;
        } finally {
            setSaving(false);
        }
    }

    return {draft, setField, reset, saving, error, save};
}
