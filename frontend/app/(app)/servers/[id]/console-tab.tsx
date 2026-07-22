"use client";

import {useEffect, useRef, useState} from "react";
import {authWsTicket, fetchServerConsoleLogs} from "@/lib/generated/sdk.gen";
import {useReconnectingSocket} from "@/lib/hooks/useReconnectingSocket";

interface Props {
    serverId: string;
    serverStatus: string;
}

function CrashLogView({serverId}: { serverId: string }) {
    const [lines, setLines] = useState<string[] | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        fetchServerConsoleLogs({path: {id: serverId}}).then(({data, error: err}) => {
            if (cancelled) return;
            if (err || !data) {
                setError(err?.message ?? "Failed to fetch logs");
                return;
            }
            setLines(data.lines);
        });
        return () => {
            cancelled = true;
        };
    }, [serverId]);

    return (
        <div className="px-6 py-4 flex flex-col gap-2">
            <p className="text-warning text-xs font-mono">Server crashed - showing last output before exit</p>
            {error && <p className="text-error text-xs font-mono">{error}</p>}
            {!error && lines === null && <p className="text-text-muted text-xs font-mono">Loading…</p>}
            {!error && lines !== null && lines.length === 0 && (
                <p className="text-text-muted text-xs font-mono">No log output available</p>
            )}
            {!error && lines !== null && lines.length > 0 && (
                <pre className="rounded border border-border bg-surface p-3 text-xs font-mono text-text-dim overflow-auto whitespace-pre-wrap" style={{height: "520px"}}>
                    {lines.join("")}
                </pre>
            )}
        </div>
    );
}

export function ConsoleTab({serverId, serverStatus}: Props) {
    const containerRef = useRef<HTMLDivElement>(null);
    const [error, setError] = useState<string | null>(null);
    const [statusMsg, setStatusMsg] = useState<string>("Connecting…");
    const termRef = useRef<import("@xterm/xterm").Terminal | null>(null);
    const roRef = useRef<ResizeObserver | null>(null);
    const disposedRef = useRef(false);

    const urlFactory = async () => {
        if (serverStatus !== "HEALTHY") return null;
        const res = await authWsTicket();
        if (!res) return null;
        const ticketErr = res.error;
        const data = res.data;
        if (ticketErr || !data?.ticket) {
            setError("Failed to get WebSocket ticket");
            return null;
        }
        const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
        return `${proto}//${window.location.host}/api/ws/console/${serverId}?ticket=${data.ticket}`;
    };

    const onMessage = (evt: MessageEvent) => {
        try {
            const msg = JSON.parse(evt.data as string) as { type: string; data?: string; reason?: string };
            if (msg.type === "console.ready") {
                setStatusMsg("");
            } else if (msg.type === "console.output") {
                termRef.current?.write((msg.data ?? "").replace(/\r?\n/g, "\r\n"));
            } else if (msg.type === "console.disconnected") {
                const reason = msg.reason ?? "Disconnected";
                termRef.current?.write(`\r\n\x1b[33m[${reason}]\x1b[0m\r\n`);
                setStatusMsg(reason);
            }
        } catch {
        }
    };

    const onOpen = () => {
        setStatusMsg("");
    };

    const onClose = () => {
        setStatusMsg((s) => s || "Disconnected");
    };

    const {connected, socketRef} = useReconnectingSocket({
        urlFactory,
        onMessage,
        onOpen,
        onClose,
        onError: () => setError("WebSocket connection failed"),
        enabled: serverStatus === "HEALTHY",
    });

    useEffect(() => {
        if (serverStatus !== "HEALTHY") return;

        setStatusMsg("Connecting…");
        setError(null);
        disposedRef.current = false;

        async function init() {
            const {Terminal} = await import("@xterm/xterm");
            const {FitAddon} = await import("@xterm/addon-fit");
            await import("@xterm/xterm/css/xterm.css");

            if (disposedRef.current || !containerRef.current) return;

            const css = getComputedStyle(document.documentElement);
            const v = (name: string) => css.getPropertyValue(name).trim();

            const term = new Terminal({
                theme: {
                    background: v("--bg"),
                    foreground: v("--text-primary"),
                    cursor: v("--accent"),
                    selectionBackground: v("--terminal-selection"),
                },
                fontFamily: "var(--font-mono, 'JetBrains Mono', monospace)",
                fontSize: 13,
                lineHeight: 1.4,
                scrollback: 5000,
            });

            termRef.current = term;

            const fitAddon = new FitAddon();
            term.loadAddon(fitAddon);
            term.open(containerRef.current!);
            fitAddon.fit();

            const ro = new ResizeObserver(() => fitAddon.fit());
            roRef.current = ro;
            if (containerRef.current) ro.observe(containerRef.current);

            const {data: logData} = await fetchServerConsoleLogs({path: {id: serverId}});
            if (disposedRef.current) return;
            if (logData?.lines.length) {
                term.write(logData.lines.join("").replace(/\r?\n/g, "\r\n"));
                term.write("\x1b[90m--- live output below ---\x1b[0m\r\n");
            }

            term.onData((data) => {
                if (socketRef.current?.readyState === WebSocket.OPEN) {
                    socketRef.current.send(JSON.stringify({type: "console.input", data}));
                }
                term.write(data);
            });
        }

        void init();

        return () => {
            disposedRef.current = true;
            roRef.current?.disconnect();
            roRef.current = null;
            termRef.current?.dispose();
            termRef.current = null;
        };
    }, [serverId, serverStatus]);

    if (serverStatus === "UNHEALTHY") {
        return <CrashLogView serverId={serverId}/>;
    }

    if (serverStatus !== "HEALTHY") {
        return (
            <div className="px-6 py-10 flex items-center justify-center">
                <p className="text-text-muted text-sm">Server is not running</p>
            </div>
        );
    }

    return (
        <div className="px-6 py-4 flex flex-col gap-2">
            {error && (
                <p className="text-error text-xs font-mono">{error}</p>
            )}
            {statusMsg && !error && (
                <p className="text-text-muted text-xs font-mono">{statusMsg}</p>
            )}
            <div
                ref={containerRef}
                className="rounded border border-border overflow-hidden"
                style={{height: "520px"}}
            />
        </div>
    );
}
