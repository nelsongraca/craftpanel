"use client";

import {useCallback, useState} from "react";

export interface ConfirmState {
    title: string;
    description: string;
    destructive?: boolean;
    onConfirm: () => void;
}

export function useConfirmDialog() {
    const [state, setState] = useState<ConfirmState | null>(null);

    const confirm = useCallback((s: ConfirmState) => setState(s), []);

    return {confirm, state, setState};
}
