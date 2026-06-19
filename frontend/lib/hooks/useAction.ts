"use client";

import {useCallback, useState} from "react";

export function useAction<T extends (...args: never[]) => Promise<unknown>>(
    fn: T,
): [(...args: Parameters<T>) => Promise<void>, {pending: boolean; error: string | null}] {
    const [pending, setPending] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const run = useCallback(
        async (...args: Parameters<T>) => {
            setPending(true);
            setError(null);
            try {
                await fn(...args);
            } catch (e: unknown) {
                setError(e instanceof Error ? e.message : "Request failed");
            } finally {
                setPending(false);
            }
        },
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [fn],
    );

    return [run, {pending, error}];
}
