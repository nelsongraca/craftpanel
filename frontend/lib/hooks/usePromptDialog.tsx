"use client";

import {useCallback, useState} from "react";
import {PromptDialog} from "@/components/ui/prompt-dialog";

export interface PromptState {
    title: string;
    description?: string;
    label: string;
    defaultValue?: string;
    confirmLabel?: string;
    onConfirm: (value: string) => void;
}

export function usePromptDialog() {
    const [state, setState] = useState<PromptState | null>(null);

    const prompt = useCallback((s: PromptState) => setState(s), []);

    const dialog = (
        <PromptDialog
            open={state !== null}
            onOpenChange={(open) => !open && setState(null)}
            title={state?.title ?? ""}
            description={state?.description}
            label={state?.label ?? ""}
            defaultValue={state?.defaultValue}
            confirmLabel={state?.confirmLabel}
            onConfirm={state?.onConfirm ?? (() => {})}
        />
    );

    return {prompt, dialog};
}
