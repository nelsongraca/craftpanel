"use client";

import { createContext, useCallback, useContext, useRef } from "react";
import { useDashboardSocket, type WsEventType } from "./hooks/useDashboardSocket";

type Listener = (payload: Record<string, unknown>) => void;
type Unsubscribe = () => void;

interface WsContextValue {
  subscribe: (type: WsEventType, listener: Listener) => Unsubscribe;
}

const WsContext = createContext<WsContextValue | null>(null);

export function WsProvider({ children }: { children: React.ReactNode }) {
  const listenersRef = useRef<Map<WsEventType, Set<Listener>>>(new Map());

  const subscribe = useCallback((type: WsEventType, listener: Listener): Unsubscribe => {
    if (!listenersRef.current.has(type)) {
      listenersRef.current.set(type, new Set());
    }
    listenersRef.current.get(type)!.add(listener);
    return () => { listenersRef.current.get(type)?.delete(listener); };
  }, []);

  const handleEvent = useCallback((type: WsEventType, payload: Record<string, unknown>) => {
    listenersRef.current.get(type)?.forEach((fn) => fn(payload));
  }, []);

  useDashboardSocket(handleEvent);

  return <WsContext.Provider value={{ subscribe }}>{children}</WsContext.Provider>;
}

export function useWs() {
  const ctx = useContext(WsContext);
  if (!ctx) throw new Error("useWs must be used within WsProvider");
  return ctx;
}
