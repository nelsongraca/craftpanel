"use client";

import {useCallback, useEffect, useRef} from "react";
import {authWsTicket} from "@/lib/generated/sdk.gen";
import {getAccessToken} from "@/lib/client";

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
    const wsRef = useRef<WebSocket | null>(null);
    const onEventRef = useRef(onEvent);
    const retryRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const retryCount = useRef(0);
    const mountedRef = useRef(true);
    const connectRef = useRef<(() => Promise<void>) | null>(null);

    useEffect(() => {
        onEventRef.current = onEvent;
    }, [onEvent]);

    const connect = useCallback(async () => {
        if (!mountedRef.current || !enabled) return;
        if (!getAccessToken()) return;

        try {
            const {data} = await authWsTicket();
            if (!data?.ticket || !mountedRef.current) return;

            const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
            const url = `${protocol}//${window.location.host}/api/ws?ticket=${data.ticket}`;
            const ws = new WebSocket(url);
            wsRef.current = ws;

            ws.onmessage = (ev) => {
                try {
                    const msg = JSON.parse(ev.data as string) as { type: string; payload: Record<string, unknown> };
                    onEventRef.current(msg.type as WsEventType, msg.payload);
                } catch {
                }
            };

            ws.onclose = () => {
                if (!mountedRef.current) return;
                const delay = Math.min(1000 * 2 ** retryCount.current, 30000);
                retryCount.current = Math.min(retryCount.current + 1, 5);
                retryRef.current = setTimeout(() => {
                    void connectRef.current?.();
                }, delay);
            };

            ws.onopen = () => {
                retryCount.current = 0;
            };
        } catch {
            if (!mountedRef.current) return;
            const delay = Math.min(1000 * 2 ** retryCount.current, 30000);
            retryCount.current = Math.min(retryCount.current + 1, 5);
            retryRef.current = setTimeout(() => {
                void connectRef.current?.();
            }, delay);
        }
    }, [enabled]);

    useEffect(() => {
        connectRef.current = connect;
    }, [connect]);

    useEffect(() => {
        mountedRef.current = true;
        void connect();
        return () => {
            mountedRef.current = false;
            if (retryRef.current) clearTimeout(retryRef.current);
            wsRef.current?.close();
            wsRef.current = null;
        };
    }, [connect]);
}
