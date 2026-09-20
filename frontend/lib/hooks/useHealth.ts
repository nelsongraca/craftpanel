"use client";

import {useEffect, useState} from "react";

export interface HealthInfo {
    frontendVersion: string;
    masterVersion: string;
    versionMismatch: boolean;
}

/**
 * Reads build versions from the frontend `/healthz` route (which proxies master's `/health`).
 * Shared by the shell footer and the node list so both agree on the master build hash.
 */
export function useHealth(): HealthInfo | null {
    const [health, setHealth] = useState<HealthInfo | null>(null);

    useEffect(() => {
        let active = true;
        fetch("/healthz")
            .then((res) => res.json())
            .then((data) => {
                if (active) setHealth(data);
            })
            .catch(() => {
                if (active) setHealth(null);
            });
        return () => {
            active = false;
        };
    }, []);

    return health;
}
