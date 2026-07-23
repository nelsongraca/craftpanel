"use client";

import {useCallback, useEffect, useRef, useState} from "react";

interface UseReconnectingSocketOptions {
    urlFactory: () => Promise<string | null>;
    onMessage: (ev: MessageEvent) => void;
    onOpen?: () => void;
    onClose?: () => void;
    onError?: () => void;
    enabled?: boolean;
}

export function useReconnectingSocket({
                                          urlFactory,
                                          onMessage,
                                          onOpen,
                                          onClose,
                                          onError,
                                          enabled = true,
                                      }: UseReconnectingSocketOptions) {
    const wsRef = useRef<WebSocket | null>(null);
    const retryRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const retryCount = useRef(0);
    const mountedRef = useRef(true);
    const connectRef = useRef<(() => Promise<void>) | null>(null);
    const onMessageRef = useRef(onMessage);
    const onOpenRef = useRef(onOpen);
    const onCloseRef = useRef(onClose);
    const onErrorRef = useRef(onError);
    const [connected, setConnected] = useState(false);

    useEffect(() => {
        onMessageRef.current = onMessage;
        onOpenRef.current = onOpen;
        onCloseRef.current = onClose;
        onErrorRef.current = onError;
    }, [onMessage, onOpen, onClose, onError]);

    const scheduleRetry = useCallback(() => {
        const delay = Math.min(1000 * 2 ** retryCount.current, 30000);
        retryCount.current = Math.min(retryCount.current + 1, 5);
        retryRef.current = setTimeout(() => {
            void connectRef.current?.();
        }, delay);
    }, []);

    const connect = useCallback(async () => {
        if (!mountedRef.current) return;

        const url = await urlFactory();
        if (!url || !mountedRef.current) return;

        try {
            const ws = new WebSocket(url);
            wsRef.current = ws;

            ws.onmessage = onMessageRef.current;

            ws.onerror = () => {
                onErrorRef.current?.();
            };

            ws.onclose = () => {
                if (!mountedRef.current) return;
                setConnected(false);
                onCloseRef.current?.();
                scheduleRetry();
            };

            ws.onopen = () => {
                retryCount.current = 0;
                setConnected(true);
                onOpenRef.current?.();
            };
        } catch {
            if (!mountedRef.current) return;
            scheduleRetry();
        }
    }, [scheduleRetry]);

    useEffect(() => {
        connectRef.current = connect;
    }, [connect]);

    useEffect(() => {
        if (!enabled) return;
        mountedRef.current = true;
        void connect();
        return () => {
            mountedRef.current = false;
            if (retryRef.current) clearTimeout(retryRef.current);
            wsRef.current?.close();
            wsRef.current = null;
        };
    }, [connect, enabled]);

    return {connected, socketRef: wsRef};
}
