"use client";

import {createContext, useCallback, useContext, useRef} from "react";
import {useDashboardSocket} from "./hooks/useDashboardSocket";
import type {ServerEventMap} from "./ws-events";

type Listener = (payload: unknown) => void;
type Unsubscribe = () => void;

interface WsContextValue {
    subscribe: <K extends keyof ServerEventMap>(type: K, listener: (payload: ServerEventMap[K]) => void) => Unsubscribe;
}

const WsContext = createContext<WsContextValue | null>(null);

export function WsProvider({children}: { children: React.ReactNode }) {
    const listenersRef = useRef<Map<string, Set<Listener>>>(new Map());

    const subscribe = useCallback(<K extends keyof ServerEventMap>(type: K, listener: (payload: ServerEventMap[K]) => void): Unsubscribe => {
        if (!listenersRef.current.has(type)) {
            listenersRef.current.set(type, new Set());
        }
        listenersRef.current.get(type)!.add(listener as Listener);
        return () => {
            listenersRef.current.get(type)?.delete(listener as Listener);
        };
    }, []);

    const handleEvent = useCallback((type: string, payload: Record<string, unknown>) => {
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
