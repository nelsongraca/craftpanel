"use client";

import {useRef} from "react";
import {authWsTicket} from "@/lib/generated/sdk.gen";
import {getAccessToken} from "@/lib/client";
import {useReconnectingSocket} from "@/lib/hooks/useReconnectingSocket";

export type WsEventType =
    | "snapshot"
    | "node.metrics"
    | "node.status"
    | "server.metrics"
    | "server.status"
    | "server.players"
    | "server.backup.progress"
    | "server.backup.complete"
    | "alert.fired"
    | "alert.resolved";

interface UseDashboardSocketOptions {
    enabled?: boolean;
}

export function useDashboardSocket(
    onEvent: (type: WsEventType, payload: Record<string, unknown>) => void,
    options: UseDashboardSocketOptions = {},
) {
    const {enabled = true} = options;
    const onEventRef = useRef(onEvent);

    onEventRef.current = onEvent;

    const urlFactory = async () => {
        if (!getAccessToken()) return null;
        const {data} = await authWsTicket();
        if (!data?.ticket) return null;
        const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
        return `${protocol}//${window.location.host}/api/ws?ticket=${data.ticket}`;
    };

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
