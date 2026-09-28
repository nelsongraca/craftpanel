"use client";

import {useEffect, useRef, useState} from "react";
import {Maximize2, Minimize2} from "lucide-react";
import {fetchServerConsoleLogs} from "@/lib/generated/sdk.gen";
import {useReconnectingSocket} from "@/lib/hooks/useReconnectingSocket";
import {FULLSCREEN_PANE_CLASS, useFullscreenPane} from "@/lib/hooks/useFullscreenPane";
import {ticketWsUrl} from "@/lib/ws-url";
import {loadConsoleHistory, saveConsoleHistory} from "@/lib/console-history";
import {attachXtermTouchScroll} from "@/lib/xterm-touch-scroll";
import {cn} from "@/lib/utils";
import Anser from "anser";

interface Props {
    serverId: string;
    serverStatus: string;
}

function ServerLogView({serverId}: {serverId: string}) {
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

    const fallback = (msg: string) => <p className="font-mono text-xs text-text-muted">{msg}</p>;

    return (
        <div className="flex h-full min-h-0 flex-col gap-2 px-4 py-6">
            {error && <p className="shrink-0 font-mono text-xs text-error">{error}</p>}
            {!error && html === null && fallback("Loading\u2026")}
            {!error && html !== null && html === "" && fallback("No log output available")}
            {!error && html !== null && html !== "" && (
                <pre
                    className="min-h-0 flex-1 overflow-auto rounded border border-border bg-surface p-3 font-mono text-xs leading-relaxed whitespace-pre-wrap"
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
    const {fullscreen, toggle: toggleFullscreen} = useFullscreenPane();
    const termRef = useRef<import("@xterm/xterm").Terminal | null>(null);
    const roRef = useRef<ResizeObserver | null>(null);
    const detachTouchRef = useRef<(() => void) | null>(null);
    const disposedRef = useRef(false);

    const lineBufRef = useRef("");
    const historyRef = useRef<string[]>([]);
    const histPosRef = useRef(-1);
    const draftBufRef = useRef("");

    const urlFactory = async () => {
        if (serverStatus !== "HEALTHY") return null;
        const url = await ticketWsUrl(`/api/ws/console/${serverId}`);
        if (!url) setError("Failed to get WebSocket ticket");
        return url;
    };

    const onMessage = (evt: MessageEvent) => {
        try {
            const msg = JSON.parse(evt.data as string) as {type: string; data?: string; reason?: string};
            if (msg.type === "console.ready") {
                setStatusMsg("");
            } else if (msg.type === "console.output") {
                termRef.current?.write((msg.data ?? "").replace(/\r?\n/g, "\r\n"));
            } else if (msg.type === "console.disconnected") {
                const reason = msg.reason ?? "Disconnected";
                termRef.current?.write(`\r\n\x1b[33m[${reason}]\x1b[0m\r\n`);
                setStatusMsg(reason);
            }
        } catch {}
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

        // Restore persisted command history and reset per-session line state so a server
        // switch never carries over another server's buffer.
        historyRef.current = loadConsoleHistory(serverId);
        lineBufRef.current = "";
        histPosRef.current = -1;
        draftBufRef.current = "";

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

            // xterm 6 wires no touch-scroll path; translate vertical drags into scrollback.
            detachTouchRef.current = attachXtermTouchScroll(containerRef.current!, term);

            // Ctrl/Cmd+C copies the selection instead of emitting ETX. xterm preventDefaults
            // every ctrl chord, so returning false both suppresses the signal and lets the
            // native copy event through.
            term.attachCustomKeyEventHandler((e) => {
                if (e.type === "keydown" && (e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === "c") {
                    const selection = term.hasSelection() ? term.getSelection() : "";
                    if (selection && navigator.clipboard?.writeText) {
                        void navigator.clipboard.writeText(selection).catch(() => {});
                    }
                    return false;
                }
                return true;
            });

            const ro = new ResizeObserver(() => fitAddon.fit());
            roRef.current = ro;
            if (containerRef.current) ro.observe(containerRef.current);

            // Tail history is fetched in parallel so it never delays the live socket; live output
            // may arrive first on a busy server, which is preferable to a blank terminal.
            void fetchServerConsoleLogs({path: {id: serverId}}).then(({data: logData}) => {
                if (disposedRef.current) return;
                if (logData?.lines.length) {
                    term.write(logData.lines.join("").replace(/\r?\n/g, "\r\n"));
                    term.write("\x1b[90m--- live output below ---\x1b[0m\r\n");
                }
            });

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
                            saveConsoleHistory(serverId, h);
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
            detachTouchRef.current?.();
            detachTouchRef.current = null;
            roRef.current?.disconnect();
            roRef.current = null;
            termRef.current?.dispose();
            termRef.current = null;
        };
    }, [serverId, serverStatus, socketRef]);

    if (serverStatus !== "HEALTHY") {
        return <ServerLogView serverId={serverId} />;
    }

    return (
        <div
            className={cn(
                "relative flex h-full min-h-0 flex-col gap-2",
                fullscreen ? `${FULLSCREEN_PANE_CLASS} px-2` : "px-4 py-6",
            )}
        >
            {error && <p className="shrink-0 font-mono text-xs text-error">{error}</p>}
            {statusMsg && !error && <p className="shrink-0 font-mono text-xs text-text-muted">{statusMsg}</p>}
            <button
                type="button"
                title={fullscreen ? "Exit fullscreen" : "Fullscreen"}
                aria-label={fullscreen ? "Exit fullscreen" : "Fullscreen"}
                onClick={toggleFullscreen}
                className="absolute top-2 right-2 z-10 rounded border border-border bg-surface/80 p-1.5 text-text-muted backdrop-blur transition-colors hover:text-accent md:hidden"
            >
                {fullscreen ? <Minimize2 size={15} /> : <Maximize2 size={15} />}
            </button>
            <div
                ref={containerRef}
                className="min-h-0 flex-1 touch-none overflow-hidden overscroll-contain rounded border border-border"
            />
        </div>
    );
}
