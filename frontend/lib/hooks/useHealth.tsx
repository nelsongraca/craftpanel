"use client";

import {createContext, useContext, useEffect, useState} from "react";

export interface HealthInfo {
    frontendVersion: string;
    masterVersion: string;
    versionMismatch: boolean;
}

const HealthContext = createContext<HealthInfo | null>(null);

/**
 * Reads build versions once from the frontend `/healthz` route (which proxies master's `/health`)
 * and shares them with every consumer, so the shell footer, the node list and the node detail agree
 * on the master build hash without each firing its own request.
 */
export function HealthProvider({children}: { children: React.ReactNode }) {
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

    return <HealthContext.Provider value={health}>{children}</HealthContext.Provider>;
}

export function useHealth(): HealthInfo | null {
    return useContext(HealthContext);
}
