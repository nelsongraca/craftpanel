"use client";

import {useCallback, useState} from "react";
import {ConfirmDialog} from "@/components/ui/confirm-dialog";

export interface ConfirmState {
    title: string;
    description: string;
    destructive?: boolean;
    onConfirm: () => void;
}

export function useConfirmDialog() {
    const [state, setState] = useState<ConfirmState | null>(null);

    const confirm = useCallback((s: ConfirmState) => setState(s), []);

    const dialog = (
        <ConfirmDialog
            open={state !== null}
            onOpenChange={(open) => !open && setState(null)}
            title={state?.title ?? ""}
            description={state?.description ?? ""}
            destructive={state?.destructive}
            onConfirm={state?.onConfirm ?? (() => {})}
        />
    );

    return {confirm, dialog, state};
}
