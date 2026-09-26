"use client";

import {useEffect, useState} from "react";
import {CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis} from "recharts";
import {getServerMetrics} from "@/lib/generated/sdk.gen";
import {useWs} from "@/lib/ws-context";
import {fmtBytes, fmtMb} from "@/lib/utils/format";
import {Empty, EmptyDescription} from "@/components/ui/empty";
import {Skeleton} from "@/components/ui/skeleton";

type TimeRange = "1h" | "6h" | "24h";
const TIME_RANGE_HOURS: Record<TimeRange, number> = {"1h": 1, "6h": 6, "24h": 24};

type MetricsPoint = {
    t: string;
    ts: number;
    cpu: number;
    ramUsed: number;
    netIn: number;
    netOut: number;
    blockIn: number;
    blockOut: number;
    heapUsed: number | null;
    heapMax: number | null;
};

const BUFFER_MAX = 360;

function fmtAxisTime(t: string) {
    return new Date(t).toLocaleTimeString([], {hour: "2-digit", minute: "2-digit"});
}

const chartStyle = {
    cartesianGrid: {strokeDasharray: "3 3", stroke: "var(--border)"},
    xAxis: {tick: {fill: "var(--text-muted)", fontSize: 10}, tickLine: false, axisLine: false},
    yAxis: {tick: {fill: "var(--text-muted)", fontSize: 10}, tickLine: false, axisLine: false, width: 52},
    tooltip: {
        contentStyle: {
            background: "var(--surface-higher)",
            border: "1px solid var(--border)",
            borderRadius: 4,
            fontSize: 11,
            color: "var(--text-primary)",
        },
    },
};

/**
 * Container resource history for one server: CPU, RAM, JVM heap, network, and block I/O.
 * Historical series come from the range endpoint; live samples are appended from the
 * `server.metrics` WS stream.
 */
export function MetricsTab({serverId, ramLimitMb}: {serverId: string; ramLimitMb: number}) {
    const [range, setRange] = useState<TimeRange>("1h");
    const [loading, setLoading] = useState(true);
    const [buffer, setBuffer] = useState<MetricsPoint[]>([]);
    const {subscribe} = useWs();

    // Initial historical load, re-fetched when the range widens.
    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        void (async () => {
            const to = new Date();
            const from = new Date(to.getTime() - TIME_RANGE_HOURS[range] * 3600 * 1000);
            const {data} = await getServerMetrics({
                path: {id: serverId},
                query: {from: from.toISOString(), to: to.toISOString()},
            });
            if (cancelled) return;
            if (data) {
                const series = data.series;
                const heapUsedByT = new Map(series.heap_used_bytes?.map((p) => [p.t, p.v]) ?? []);
                const heapMaxByT = new Map(series.heap_max_bytes?.map((p) => [p.t, p.v]) ?? []);
                const pts = series.cpu_percent.map((p, i) => ({
                    t: p.t,
                    ts: new Date(p.t).getTime(),
                    cpu: p.v,
                    ramUsed: series.ram_used_mb[i]?.v ?? 0,
                    netIn: series.net_in_bytes[i]?.v ?? 0,
                    netOut: series.net_out_bytes[i]?.v ?? 0,
                    blockIn: series.block_in_bytes?.[i]?.v ?? 0,
                    blockOut: series.block_out_bytes?.[i]?.v ?? 0,
                    heapUsed: heapUsedByT.get(p.t) ?? null,
                    heapMax: heapMaxByT.get(p.t) ?? null,
                }));
                setBuffer(pts.slice(-BUFFER_MAX));
            }
            setLoading(false);
        })();
        return () => {
            cancelled = true;
        };
    }, [serverId, range]);

    // Live WS updates
    useEffect(() => {
        return subscribe("server.metrics", (payload) => {
            if (payload.server_id !== serverId) return;
            const t = payload.recorded_at ?? new Date().toISOString();
            const pt: MetricsPoint = {
                t,
                ts: new Date(t).getTime(),
                cpu: payload.cpu_percent ?? 0,
                ramUsed: payload.ram_used_mb ?? 0,
                netIn: payload.net_in_bytes ?? 0,
                netOut: payload.net_out_bytes ?? 0,
                blockIn: payload.block_in_bytes ?? 0,
                blockOut: payload.block_out_bytes ?? 0,
                heapUsed: payload.heap_used_bytes ?? null,
                heapMax: payload.heap_max_bytes ?? null,
            };
            setBuffer((prev) => [...prev.slice(-(BUFFER_MAX - 1)), pt]);
        });
    }, [subscribe, serverId]);

    const cutoff = TIME_RANGE_HOURS[range] * 3600 * 1000;
    const now = new Date().getTime();
    const points = buffer.filter((p) => p.ts >= now - cutoff);
    const heapPoints = points.filter((p) => p.heapUsed != null);

    if (loading) {
        return (
            <div className="space-y-4 px-6 py-6">
                {Array.from({length: 3}).map((_, i) => (
                    <Skeleton key={i} className="h-40 bg-surface" />
                ))}
            </div>
        );
    }

    if (points.length === 0) {
        return (
            <div className="px-6 py-10">
                <Empty className="rounded-md border-2 border-border py-10">
                    <EmptyDescription>No metrics available for the selected time range</EmptyDescription>
                </Empty>
            </div>
        );
    }

    const lastHeapMax = heapPoints.at(-1)?.heapMax ?? null;

    return (
        <div className="space-y-6 px-6 py-6">
            <div className="flex items-center gap-1">
                {(["1h", "6h", "24h"] as TimeRange[]).map((r) => (
                    <button
                        key={r}
                        onClick={() => setRange(r)}
                        className={[
                            "rounded border px-3 py-1 font-heading text-xs font-bold tracking-widest uppercase transition-colors",
                            range === r
                                ? "border-accent bg-accent text-bg"
                                : "border-border text-text-muted hover:bg-surface-high hover:text-text-primary",
                        ].join(" ")}
                    >
                        {r}
                    </button>
                ))}
            </div>

            {/* CPU % */}
            <div className="rounded border border-border bg-surface p-4">
                <p className="mb-4 font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                    CPU Utilization
                </p>
                <ResponsiveContainer width="100%" height={140}>
                    <LineChart data={points} margin={{top: 0, right: 8, bottom: 0, left: 0}}>
                        <CartesianGrid {...chartStyle.cartesianGrid} />
                        <XAxis dataKey="t" tickFormatter={fmtAxisTime} {...chartStyle.xAxis} />
                        <YAxis domain={[0, 100]} tickFormatter={(v) => `${v}%`} {...chartStyle.yAxis} />
                        <Tooltip
                            {...chartStyle.tooltip}
                            // eslint-disable-next-line @typescript-eslint/no-explicit-any
                            formatter={(v: any) => [`${(v as number).toFixed(1)}%`, "CPU"]}
                            // eslint-disable-next-line @typescript-eslint/no-explicit-any
                            labelFormatter={(t: any) => fmtAxisTime(String(t))}
                        />
                        <Line type="monotone" dataKey="cpu" stroke="var(--accent)" strokeWidth={1.5} dot={false} />
                    </LineChart>
                </ResponsiveContainer>
            </div>

            {/* RAM */}
            <div className="rounded border border-border bg-surface p-4">
                <p className="mb-4 font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                    RAM Usage
                </p>
                <ResponsiveContainer width="100%" height={140}>
                    <LineChart data={points} margin={{top: 0, right: 8, bottom: 0, left: 0}}>
                        <CartesianGrid {...chartStyle.cartesianGrid} />
                        <XAxis dataKey="t" tickFormatter={fmtAxisTime} {...chartStyle.xAxis} />
                        <YAxis tickFormatter={(v) => fmtMb(v)} {...chartStyle.yAxis} />
                        <Tooltip
                            {...chartStyle.tooltip}
                            // eslint-disable-next-line @typescript-eslint/no-explicit-any
                            formatter={(v: any) => [fmtMb(v as number), "RAM Used"]}
                            // eslint-disable-next-line @typescript-eslint/no-explicit-any
                            labelFormatter={(t: any) => fmtAxisTime(String(t))}
                        />
                        {ramLimitMb > 0 && (
                            <ReferenceLine
                                y={ramLimitMb}
                                stroke="var(--border)"
                                strokeDasharray="4 2"
                                label={{
                                    value: `Limit ${fmtMb(ramLimitMb)}`,
                                    fill: "var(--text-muted)",
                                    fontSize: 10,
                                    position: "insideTopRight",
                                }}
                            />
                        )}
                        <Line type="monotone" dataKey="ramUsed" stroke="var(--healthy)" strokeWidth={1.5} dot={false} />
                    </LineChart>
                </ResponsiveContainer>
            </div>

            {/* JVM Heap — hidden entirely when the server reports no JVM samples */}
            {heapPoints.length > 0 && (
                <div className="rounded border border-border bg-surface p-4">
                    <p className="mb-4 font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                        JVM Heap
                    </p>
                    <ResponsiveContainer width="100%" height={140}>
                        <LineChart data={heapPoints} margin={{top: 0, right: 8, bottom: 0, left: 0}}>
                            <CartesianGrid {...chartStyle.cartesianGrid} />
                            <XAxis dataKey="t" tickFormatter={fmtAxisTime} {...chartStyle.xAxis} />
                            <YAxis tickFormatter={(v) => fmtBytes(v)} {...chartStyle.yAxis} />
                            <Tooltip
                                {...chartStyle.tooltip}
                                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                                formatter={(v: any) => [fmtBytes(v as number), "Heap Used"]}
                                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                                labelFormatter={(t: any) => fmtAxisTime(String(t))}
                            />
                            {lastHeapMax != null && lastHeapMax > 0 && (
                                <ReferenceLine
                                    y={lastHeapMax}
                                    stroke="var(--border)"
                                    strokeDasharray="4 2"
                                    label={{
                                        value: `Max ${fmtBytes(lastHeapMax)}`,
                                        fill: "var(--text-muted)",
                                        fontSize: 10,
                                        position: "insideTopRight",
                                    }}
                                />
                            )}
                            <Line
                                type="monotone"
                                dataKey="heapUsed"
                                stroke="var(--accent-bright)"
                                strokeWidth={1.5}
                                dot={false}
                            />
                        </LineChart>
                    </ResponsiveContainer>
                </div>
            )}

            {/* Network */}
            <div className="rounded border border-border bg-surface p-4">
                <p className="mb-4 font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                    Network I/O
                </p>
                <div className="mb-3 flex items-center gap-4">
                    <div className="flex items-center gap-1.5">
                        <div className="h-0.5 w-3 rounded" style={{background: "var(--healthy)"}} />
                        <span className="font-mono text-xs text-text-muted">Net {"\u2193"}</span>
                    </div>
                    <div className="flex items-center gap-1.5">
                        <div className="h-0.5 w-3 rounded" style={{background: "var(--accent)"}} />
                        <span className="font-mono text-xs text-text-muted">Net {"\u2191"}</span>
                    </div>
                </div>
                <ResponsiveContainer width="100%" height={140}>
                    <LineChart data={points} margin={{top: 0, right: 8, bottom: 0, left: 0}}>
                        <CartesianGrid {...chartStyle.cartesianGrid} />
                        <XAxis dataKey="t" tickFormatter={fmtAxisTime} {...chartStyle.xAxis} />
                        <YAxis tickFormatter={(v) => fmtBytes(v)} {...chartStyle.yAxis} />
                        <Tooltip
                            {...chartStyle.tooltip}
                            // eslint-disable-next-line @typescript-eslint/no-explicit-any
                            formatter={(v: any, name: any) => [
                                fmtBytes(v as number),
                                name === "netIn" ? "Net \u2193" : "Net \u2191",
                            ]}
                            // eslint-disable-next-line @typescript-eslint/no-explicit-any
                            labelFormatter={(t: any) => fmtAxisTime(String(t))}
                        />
                        <Line type="monotone" dataKey="netIn" stroke="var(--healthy)" strokeWidth={1.5} dot={false} />
                        <Line type="monotone" dataKey="netOut" stroke="var(--accent)" strokeWidth={1.5} dot={false} />
                    </LineChart>
                </ResponsiveContainer>
            </div>

            {/* Block I/O */}
            <div className="rounded border border-border bg-surface p-4">
                <p className="mb-4 font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                    Block I/O
                </p>
                <div className="mb-3 flex items-center gap-4">
                    <div className="flex items-center gap-1.5">
                        <div className="h-0.5 w-3 rounded" style={{background: "var(--warning)"}} />
                        <span className="font-mono text-xs text-text-muted">Read</span>
                    </div>
                    <div className="flex items-center gap-1.5">
                        <div className="h-0.5 w-3 rounded" style={{background: "var(--accent)"}} />
                        <span className="font-mono text-xs text-text-muted">Write</span>
                    </div>
                </div>
                <ResponsiveContainer width="100%" height={140}>
                    <LineChart data={points} margin={{top: 0, right: 8, bottom: 0, left: 0}}>
                        <CartesianGrid {...chartStyle.cartesianGrid} />
                        <XAxis dataKey="t" tickFormatter={fmtAxisTime} {...chartStyle.xAxis} />
                        <YAxis tickFormatter={(v) => fmtBytes(v)} {...chartStyle.yAxis} />
                        <Tooltip
                            {...chartStyle.tooltip}
                            // eslint-disable-next-line @typescript-eslint/no-explicit-any
                            formatter={(v: any, name: any) => [
                                fmtBytes(v as number),
                                name === "blockIn" ? "Read" : "Write",
                            ]}
                            // eslint-disable-next-line @typescript-eslint/no-explicit-any
                            labelFormatter={(t: any) => fmtAxisTime(String(t))}
                        />
                        <Line type="monotone" dataKey="blockIn" stroke="var(--warning)" strokeWidth={1.5} dot={false} />
                        <Line type="monotone" dataKey="blockOut" stroke="var(--accent)" strokeWidth={1.5} dot={false} />
                    </LineChart>
                </ResponsiveContainer>
            </div>
        </div>
    );
}
