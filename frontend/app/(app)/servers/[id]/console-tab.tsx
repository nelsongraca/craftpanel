"use client";

import {useEffect, useRef, useState} from "react";
import {authWsTicket, fetchServerConsoleLogs} from "@/lib/generated/sdk.gen";

interface Props {
    serverId: string;
    serverStatus: string;
}

function CrashLogView({serverId}: { serverId: string }) {
    const [lines, setLines] = useState<string[] | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        fetchServerConsoleLogs({path: {id: serverId}, query: {tail: 200}}).then(({data, error: err}) => {
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
    const [reconnectKey, setReconnectKey] = useState(0);

    useEffect(() => {
        if (serverStatus !== "HEALTHY") return;

        setStatusMsg("Connecting…");
        setError(null);
        let disposed = false;
        let term: import("@xterm/xterm").Terminal | null = null;
        let ws: WebSocket | null = null;

        async function init() {
            const {Terminal} = await import("@xterm/xterm");
            const {FitAddon} = await import("@xterm/addon-fit");
            await import("@xterm/xterm/css/xterm.css");

            if (disposed || !containerRef.current) return;

            const css = getComputedStyle(document.documentElement);
            const v = (name: string) => css.getPropertyValue(name).trim();

            term = new Terminal({
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

            const fitAddon = new FitAddon();
            term.loadAddon(fitAddon);
            term.open(containerRef.current);
            fitAddon.fit();

            const ro = new ResizeObserver(() => fitAddon.fit());
            if (containerRef.current) ro.observe(containerRef.current);

            const {data, error: ticketErr} = await authWsTicket();
            if (ticketErr || !data?.ticket) {
                setError("Failed to get WebSocket ticket");
                return;
            }
            if (disposed) return;

            const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
            ws = new WebSocket(`${proto}//${window.location.host}/api/ws/console/${serverId}?ticket=${data.ticket}`);

            ws.onmessage = (evt) => {
                try {
                    const msg = JSON.parse(evt.data as string) as { type: string; data?: string; reason?: string };
                    if (msg.type === "console.ready") {
                        setStatusMsg("");
                    } else if (msg.type === "console.output") {
                        term?.write((msg.data ?? "").replace(/\r?\n/g, "\r\n"));
                    } else if (msg.type === "console.disconnected") {
                        const reason = msg.reason ?? "Disconnected";
                        term?.write(`\r\n\x1b[33m[${reason}]\x1b[0m\r\n`);
                        setStatusMsg(reason);
                    }
                } catch {
                    // ignore malformed frames
                }
            };

            ws.onerror = () => setError("WebSocket connection failed");
            ws.onclose = () => {
                setStatusMsg((s) => s || "Disconnected");
                if (!disposed) {
                    setTimeout(() => {
                        if (!disposed) setReconnectKey((k) => k + 1);
                    }, 3000);
                }
            };

            term.onData((data) => {
                if (ws?.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify({type: "console.input", data}));
                }
                term?.write(data);
            });

            return () => {
                ro.disconnect();
            };
        }

        void init();

        return () => {
            disposed = true;
            ws?.close();
            term?.dispose();
        };
    }, [serverId, serverStatus, reconnectKey]);

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
