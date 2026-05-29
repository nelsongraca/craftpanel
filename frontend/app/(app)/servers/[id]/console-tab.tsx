"use client";

import {useEffect, useRef, useState} from "react";
import {authWsTicket} from "@/lib/generated/sdk.gen";

interface Props {
    serverId: string;
    serverStatus: string;
}

export function ConsoleTab({serverId, serverStatus}: Props) {
    const containerRef = useRef<HTMLDivElement>(null);
    const [error, setError] = useState<string | null>(null);
    const [statusMsg, setStatusMsg] = useState<string>("Connecting…");

    useEffect(() => {
        if (serverStatus !== "HEALTHY") return;

        let disposed = false;
        let term: import("@xterm/xterm").Terminal | null = null;
        let ws: WebSocket | null = null;

        async function init() {
            const {Terminal} = await import("@xterm/xterm");
            const {FitAddon} = await import("@xterm/addon-fit");
            await import("@xterm/xterm/css/xterm.css");

            if (disposed || !containerRef.current) return;

            term = new Terminal({
                theme: {
                    background: "#0e0d0c",
                    foreground: "#f5f0e8",
                    cursor: "#d97706",
                    selectionBackground: "rgba(217,119,6,0.3)",
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
                        term?.write(msg.data ?? "");
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
            ws.onclose = () => setStatusMsg((s) => s || "Disconnected");

            term.onData((data) => {
                if (ws?.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify({type: "console.input", data}));
                }
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
    }, [serverId, serverStatus]);

    if (serverStatus !== "HEALTHY") {
        return (
            <div className="px-6 py-10 flex items-center justify-center">
                <p className="text-text-muted text-[13px]">Server is not running</p>
            </div>
        );
    }

    return (
        <div className="px-6 py-4 flex flex-col gap-2">
            {error && (
                <p className="text-error text-[12px] font-mono">{error}</p>
            )}
            {statusMsg && !error && (
                <p className="text-text-muted text-[12px] font-mono">{statusMsg}</p>
            )}
            <div
                ref={containerRef}
                className="rounded border border-border overflow-hidden"
                style={{height: "520px"}}
            />
        </div>
    );
}
