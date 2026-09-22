"use client";

import {useEffect, useRef} from "react";
import {useReconnectingSocket} from "@/lib/hooks/useReconnectingSocket";
import {ticketWsUrl} from "@/lib/ws-url";
import type {ServerEventMap} from "@/lib/ws-events";

/** The dashboard WebSocket event names — the keys of the typed event map. */
export type WsEventType = keyof ServerEventMap;

interface UseDashboardSocketOptions {
    enabled?: boolean;
}

export function useDashboardSocket(
    onEvent: (type: WsEventType, payload: Record<string, unknown>) => void,
    options: UseDashboardSocketOptions = {},
) {
    const {enabled = true} = options;
    const onEventRef = useRef(onEvent);

    useEffect(() => {
        onEventRef.current = onEvent;
    }, [onEvent]);

    const urlFactory = () => ticketWsUrl("/api/ws");

    const onMessage = (ev: MessageEvent) => {
        try {
            const msg = JSON.parse(ev.data as string) as { type: string; payload: Record<string, unknown> };
            onEventRef.current(msg.type as WsEventType, msg.payload);
        } catch {
        }
    };

    const {connected} = useReconnectingSocket({
        urlFactory,
        onMessage,
        enabled,
    });

    return {connected};
}
