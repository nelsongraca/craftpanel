"use client";

import {useEffect, useState} from "react";
import {getServerStatusHistory} from "@/lib/generated/sdk.gen";
import {serverStatusClass, serverStatusLabel} from "@/lib/status";
import {timeAgo} from "@/lib/utils/format";
import {useWs} from "@/lib/ws-context";
import {Skeleton} from "@/components/ui/skeleton";

const MAX_ENTRIES = 50;

type HistoryEntry = {status: string; recordedAt: string; ts: number};

function toEntry(e: {status: string; recorded_at: string}): HistoryEntry {
    return {status: e.status, recordedAt: e.recorded_at, ts: new Date(e.recorded_at).getTime()};
}

/** Human-readable duration for how long a status was held. */
function fmtDuration(ms: number): string {
    const secs = Math.max(0, Math.floor(ms / 1000));
    if (secs < 60) return `${secs}s`;
    const mins = Math.floor(secs / 60);
    if (mins < 60) return `${mins}m`;
    const hours = Math.floor(mins / 60);
    if (hours < 24) return `${hours}h ${mins % 60}m`;
    return `${Math.floor(hours / 24)}d ${hours % 24}h`;
}

/**
 * Timeline of agent-reported status transitions, newest first. Seeded from the persisted history
 * endpoint, then live-appended from the `server.status` WS stream. Repeated re-affirmations of the
 * same status are dropped so reconnect snapshots do not flood the list.
 */
export function StatusHistoryPanel({serverId}: {serverId: string}) {
    const [events, setEvents] = useState<HistoryEntry[] | null>(null);
    const [error, setError] = useState<string | null>(null);
    const {subscribe} = useWs();

    useEffect(() => {
        let cancelled = false;
        void (async () => {
            const {data} = await getServerStatusHistory({path: {id: serverId}, query: {limit: MAX_ENTRIES}});
            if (cancelled) return;
            if (!data) {
                setError("Failed to load status history");
                setEvents([]);
                return;
            }
            setEvents(data.events.map(toEntry));
        })();
        return () => {
            cancelled = true;
        };
    }, [serverId]);

    useEffect(() => {
        return subscribe("server.status", (payload) => {
            if (payload.server_id !== serverId) return;
            setEvents((prev) => {
                const next = toEntry(payload);
                if (!prev) return [next];
                if (prev[0]?.status === next.status) return prev;
                return [next, ...prev].slice(0, MAX_ENTRIES);
            });
        });
    }, [subscribe, serverId]);

    return (
        <div className="rounded border border-border bg-surface p-4">
            <p className="mb-2 font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                Status History
            </p>

            {events === null ? (
                <div className="space-y-2">
                    {Array.from({length: 3}).map((_, i) => (
                        <Skeleton key={i} className="h-8 bg-surface-high" />
                    ))}
                </div>
            ) : error ? (
                <p className="text-xs text-error">{error}</p>
            ) : events.length === 0 ? (
                <p className="text-xs text-text-muted">No status changes recorded yet.</p>
            ) : (
                <ul className="space-y-1">
                    {events.map((e, i) => {
                        const older = events[i + 1];
                        const heldMs = older ? e.ts - older.ts : new Date().getTime() - e.ts;
                        return (
                            <li key={`${e.recordedAt}-${i}`} className="flex items-center justify-between gap-3 py-1">
                                <span
                                    className={`rounded px-2 py-0.5 font-heading text-[10px] font-bold tracking-wider uppercase ${serverStatusClass(e.status)}`}
                                >
                                    {serverStatusLabel(e.status)}
                                </span>
                                <span className="flex-1 text-right font-mono text-xs text-text-dim">
                                    {older ? `held ${fmtDuration(heldMs)}` : "current"}
                                </span>
                                <span
                                    className="w-20 text-right font-mono text-xs text-text-muted"
                                    title={e.recordedAt}
                                >
                                    {timeAgo(e.recordedAt)}
                                </span>
                            </li>
                        );
                    })}
                </ul>
            )}
        </div>
    );
}
