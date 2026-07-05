"use client";

import {useCallback, useEffect, useState, type Dispatch, type SetStateAction} from "react";

export function useResourceList<T>(
    loader: () => Promise<{data?: T[]}>,
    opts?: {pollMs?: number},
): {
    data: T[];
    initialLoad: boolean;
    reload: () => void;
    setData: Dispatch<SetStateAction<T[]>>;
} {
    const pollMs = opts?.pollMs ?? 30_000;

    const [data, setData] = useState<T[]>([]);
    const [initialLoad, setInitialLoad] = useState(true);

    const reload = useCallback(async () => {
        const {data} = await loader();
        if (data) setData(data);
    }, [loader]);

    useEffect(() => {
        let cancelled = false;
        void reload().then(() => {
            if (!cancelled) setInitialLoad(false);
        });
        // pollMs <= 0 disables polling — load once on mount, refresh only via reload()
        const id = pollMs > 0 ? setInterval(reload, pollMs) : undefined;
        return () => {
            cancelled = true;
            if (id !== undefined) clearInterval(id);
        };
    }, [reload, pollMs]);

    return {data, initialLoad, reload, setData};
}
