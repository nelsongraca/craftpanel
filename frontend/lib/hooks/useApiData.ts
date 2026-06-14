'use client'

import {useEffect, useRef, useState} from 'react'
import {ApiError} from '@/lib/api'

export function useApiData<T>(
    loader: () => Promise<T>,
    deps: unknown[],
    {pollMs}: { pollMs?: number } = {},
): { data: T | undefined; loading: boolean; error: string | null; reload: () => void } {
    const [data, setData] = useState<T | undefined>(undefined)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)
    const [reloadKey, setReloadKey] = useState(0)
    const loaderRef = useRef(loader)

    useEffect(() => {
        loaderRef.current = loader
    })

    useEffect(() => {
        let cancelled = false
        setLoading(true)
        setError(null)
        void loaderRef.current().then((result) => {
            if (!cancelled) {
                setData(result);
                setLoading(false)
            }
        }).catch((e: unknown) => {
            if (!cancelled) {
                setError(e instanceof ApiError ? e.message : e instanceof Error ? e.message : 'Failed to load')
                setLoading(false)
            }
        })
        if (!pollMs) return () => {
            cancelled = true
        }
        const id = setInterval(() => {
            void loaderRef.current().then((r) => {
                if (!cancelled) setData(r)
            })
        }, pollMs)
        return () => {
            cancelled = true;
            clearInterval(id)
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [reloadKey, pollMs, ...deps])

    return {data, loading, error, reload: () => setReloadKey((k) => k + 1)}
}
