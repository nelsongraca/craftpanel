"use client";

import {useEffect, useRef, useState} from "react";
import {authWsTicket, fetchServerConsoleLogs} from "@/lib/generated/sdk.gen";
import {useReconnectingSocket} from "@/lib/hooks/useReconnectingSocket";
import Anser from "anser";

interface Props {
    serverId: string;
    serverStatus: string;
}

function ServerLogView({serverId}: { serverId: string }) {
    const [html, setHtml] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        fetchServerConsoleLogs({path: {id: serverId}}).then(({data, error: err}) => {
            if (cancelled) return;
            if (err || !data) {
                setError(err?.message ?? "Failed to fetch logs");
                return;
            }
            const raw = data.lines.join("");
            if (raw.length === 0) {
                setHtml("");
                return;
            }
            setHtml(Anser.ansiToHtml(Anser.escapeForHtml(raw)));
        });
        return () => {
            cancelled = true;
        };
    }, [serverId]);

    const fallback = (msg: string) => (
        <p className="text-text-muted text-xs font-mono">{msg}</p>
    );

    return (
        <div className="px-6 py-6 flex flex-col gap-2 h-full min-h-0">
            {error && <p className="text-error text-xs font-mono shrink-0">{error}</p>}
            {!error && html === null && fallback("Loading\u2026")}
            {!error && html !== null && html === "" && fallback("No log output available")}
            {!error && html !== null && html !== "" && (
                <pre
                    className="rounded border border-border bg-surface p-3 text-xs font-mono overflow-auto whitespace-pre-wrap leading-relaxed flex-1 min-h-0"
                    dangerouslySetInnerHTML={{__html: html}}
                />
            )}
        </div>
    );
}

export function ConsoleTab({serverId, serverStatus}: Props) {
    const containerRef = useRef<HTMLDivElement>(null);
    const [error, setError] = useState<string | null>(null);
    const [statusMsg, setStatusMsg] = useState<string>("Connecting\u2026");
    const termRef = useRef<import("@xterm/xterm").Terminal | null>(null);
    const roRef = useRef<ResizeObserver | null>(null);
    const disposedRef = useRef(false);

    const lineBufRef = useRef("");
    const historyRef = useRef<string[]>([]);
    const histPosRef = useRef(-1);
    const draftBufRef = useRef("");

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

    const {socketRef} = useReconnectingSocket({
        urlFactory,
        onMessage,
        onOpen,
        onClose,
        onError: () => setError("WebSocket connection failed"),
        enabled: serverStatus === "HEALTHY",
    });

    useEffect(() => {
        if (serverStatus !== "HEALTHY") return;

        setStatusMsg("Connecting\u2026");
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
                const h = historyRef.current;
                const send = (text: string) => {
                    if (socketRef.current?.readyState === WebSocket.OPEN) {
                        socketRef.current.send(JSON.stringify({type: "console.input", data: text}));
                    }
                };

                if (data === "\r" || data === "\n") {
                    const cmd = lineBufRef.current;
                    if (cmd) {
                        send(cmd + "\n");
                        if (h.length === 0 || h[h.length - 1] !== cmd) {
                            h.push(cmd);
                            if (h.length > 100) h.shift();
                        }
                    }
                    lineBufRef.current = "";
                    histPosRef.current = -1;
                    term.write("\r\n");
                } else if (data === "\x7f" || data === "\x08") {
                    if (lineBufRef.current) {
                        lineBufRef.current = lineBufRef.current.slice(0, -1);
                        term.write("\b \b");
                    }
                } else if (data === "\x1b[A") {
                    if (h.length > 0) {
                        if (histPosRef.current === -1) {
                            draftBufRef.current = lineBufRef.current;
                        }
                        histPosRef.current = Math.min(histPosRef.current + 1, h.length - 1);
                        lineBufRef.current = h[h.length - 1 - histPosRef.current];
                        term.write("\r\x1b[K" + lineBufRef.current);
                    }
                } else if (data === "\x1b[B") {
                    if (histPosRef.current === -1) return;
                    histPosRef.current--;
                    if (histPosRef.current < 0) {
                        lineBufRef.current = draftBufRef.current;
                        draftBufRef.current = "";
                        histPosRef.current = -1;
                    } else {
                        lineBufRef.current = h[h.length - 1 - histPosRef.current];
                    }
                    term.write("\r\x1b[K" + lineBufRef.current);
                } else if (data === "\x03") {
                    lineBufRef.current = "";
                    histPosRef.current = -1;
                    term.write("^C\r\n");
                } else if (data === "\x1b") {
                    // Escape alone - ignore
                } else if (data === "\t") {
                } else if (data.startsWith("\x1b")) {
                    term.write(data);
                } else {
                    for (let i = 0; i < data.length; i++) {
                        const ch = data[i];
                        if (ch >= " ") {
                            lineBufRef.current += ch;
                            term.write(ch);
                        } else {
                            term.write(ch);
                        }
                    }
                }
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
    }, [serverId, serverStatus, socketRef]);

    if (serverStatus !== "HEALTHY") {
        return <ServerLogView serverId={serverId}/>;
    }

    return (
        <div className="px-6 py-6 flex flex-col gap-2 h-full min-h-0">
            {error && (
                <p className="text-error text-xs font-mono shrink-0">{error}</p>
            )}
            {statusMsg && !error && (
                <p className="text-text-muted text-xs font-mono shrink-0">{statusMsg}</p>
            )}
            <div
                ref={containerRef}
                className="rounded border border-border overflow-hidden flex-1 min-h-0"
            />
        </div>
    );
}