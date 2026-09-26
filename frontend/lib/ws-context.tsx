"use client";

import {createContext, useCallback, useContext, useRef} from "react";
import {useDashboardSocket} from "./hooks/useDashboardSocket";
import type {ServerEventMap} from "./ws-events";
import type {SnapshotPayload} from "@/lib/generated/types.gen";

type Listener = (payload: unknown) => void;
type Unsubscribe = () => void;

interface WsContextValue {
    subscribe: <K extends keyof ServerEventMap>(type: K, listener: (payload: ServerEventMap[K]) => void) => Unsubscribe;
}

const WsContext = createContext<WsContextValue | null>(null);

export function WsProvider({children}: {children: React.ReactNode}) {
    const listenersRef = useRef<Map<string, Set<Listener>>>(new Map());
    const lastSnapshotRef = useRef<SnapshotPayload | null>(null);

    const subscribe = useCallback(
        <K extends keyof ServerEventMap>(type: K, listener: (payload: ServerEventMap[K]) => void): Unsubscribe => {
            if (!listenersRef.current.has(type)) {
                listenersRef.current.set(type, new Set());
            }
            listenersRef.current.get(type)!.add(listener as Listener);
            // The server sends the snapshot once per connection, so a consumer that mounts while the
            // socket is already open (e.g. navigating to /servers) would miss it. Replay the cached
            // snapshot so live metrics render immediately instead of waiting for the next tick.
            if (type === "snapshot" && lastSnapshotRef.current) {
                (listener as Listener)(lastSnapshotRef.current);
            }
            return () => {
                listenersRef.current.get(type)?.delete(listener as Listener);
            };
        },
        [],
    );

    const handleEvent = useCallback((type: string, payload: Record<string, unknown>) => {
        if (type === "snapshot") lastSnapshotRef.current = payload as SnapshotPayload;
        listenersRef.current.get(type)?.forEach((fn) => fn(payload));
    }, []);

    useDashboardSocket(handleEvent);

    return <WsContext.Provider value={{subscribe}}>{children}</WsContext.Provider>;
}

export function useWs() {
    const ctx = useContext(WsContext);
    if (!ctx) throw new Error("useWs must be used within WsProvider");
    return ctx;
}
