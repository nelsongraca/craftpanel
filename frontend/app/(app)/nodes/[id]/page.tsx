"use client";

import {useCallback, useEffect, useState} from "react";
import {useParams, useRouter} from "next/navigation";
import Link from "next/link";
import {Ban, Check, ChevronRight, KeyRound, Pencil, Power, Trash2, X,} from "lucide-react";
import {CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis,} from "recharts";
import {decommissionNode, getNode, getNodeMetrics, listServers, rejectNode, rotateNodeToken, shutdownNode, trustNode, updateNode,} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import {useWs} from "@/lib/ws-context";
import type {Node} from "@/lib/types";
import {timeAgo, fmtBytes, fmtMb, fmtBytesNetworkIo, fillColorBg} from "@/lib/utils/format";
import {TokenModal} from "@/components/nodes/TokenModal";
import type {ServerResponse as Server} from "@/lib/generated/types.gen";
import {useConfirmDialog} from "@/lib/hooks/useConfirmDialog";
import {HeaderActionButton} from "@/components/servers/header-action-button";

import {nodeStatusClass, nodeStatusLabel, serverStatusClass} from "@/lib/status";

// ── Sub-components ────────────────────────────────────────────────────────────

function StatCard({label, children}: { label: string; children: React.ReactNode }) {
    return (
        <div className="bg-surface border border-border rounded p-4 flex flex-col gap-2">
            <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted">
                {label}
            </p>
            {children}
        </div>
    );
}

function ResourceBar({used, total, fmt}: { used: number; total: number; fmt: (v: number) => string }) {
    const pct = total > 0 ? Math.min(100, (used / total) * 100) : 0;
    const cls = fillColorBg(pct);
    return (
        <div className="flex flex-col gap-1.5">
            <p className="font-mono text-[20px] text-text-primary leading-none">{fmt(used)}</p>
            <p className="font-mono text-[12px] text-text-muted">{fmt(used)} / {fmt(total)}</p>
            <div className="h-1.5 rounded-full bg-surface-higher w-full overflow-hidden">
                <div className={`h-full rounded-full ${cls}`} style={{width: `${pct}%`}}/>
            </div>
        </div>
    );
}

function InfoRow({label, value}: { label: string; value: React.ReactNode }) {
    return (
        <div className="flex items-start justify-between gap-4 py-2 border-b border-border last:border-0">
      <span className="text-[12px] font-heading font-bold uppercase tracking-wider text-text-muted shrink-0">
        {label}
      </span>
            <span className="font-mono text-[12px] text-text-primary text-right">{value}</span>
        </div>
    );
}

// ── Edit modal ─────────────────────────────────────────────────────────────────

function EditModal({node, onClose, onSaved}: { node: Node; onClose: () => void; onSaved: () => void }) {
    const [displayName, setDisplayName] = useState(node.display_name);
    const [portStart, setPortStart] = useState(String(node.port_range_start));
    const [portEnd, setPortEnd] = useState(String(node.port_range_end));
    const [reservedRam, setReservedRam] = useState(String(node.reserved_ram_mb));
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    async function save() {
        setSaving(true);
        setError(null);
        const {error} = await updateNode({
            path: {id: node.id},
            body: {
                display_name: displayName || undefined,
                port_range_start: portStart ? parseInt(portStart) : undefined,
                port_range_end: portEnd ? parseInt(portEnd) : undefined,
                reserved_ram_mb: reservedRam ? parseInt(reservedRam) : undefined,
            },
        });
        if (error) {
            setError(error.message ?? "Failed to save");
        } else {
            onSaved();
            onClose();
        }
        setSaving(false);
    }

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
            <div className="bg-surface-higher border border-border rounded shadow-2xl w-[400px] p-6">
                <div className="flex items-center justify-between mb-5">
                    <p className="text-[13px] font-heading font-bold uppercase tracking-widest text-text-primary">Edit Node</p>
                    <button onClick={onClose} className="text-text-muted hover:text-text-primary"><X size={14}/></button>
                </div>
                {error && (
                    <div className="mb-4 text-[12px] text-error bg-error/10 border border-error/30 rounded px-3 py-2">{error}</div>
                )}
                <div className="space-y-4">
                    <label className="block">
                        <span className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">Display Name</span>
                        <input value={displayName} onChange={(e) => setDisplayName(e.target.value)}
                               className="w-full h-8 bg-surface border border-border rounded px-2.5 text-[12px] font-mono text-text-primary focus:outline-none focus:border-accent"/>
                    </label>
                    <div className="grid grid-cols-2 gap-3">
                        <label className="block">
                            <span className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">Port Start</span>
                            <input type="number" value={portStart} onChange={(e) => setPortStart(e.target.value)}
                                   className="w-full h-8 bg-surface border border-border rounded px-2.5 text-[12px] font-mono text-text-primary focus:outline-none focus:border-accent"/>
                        </label>
                        <label className="block">
                            <span className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">Port End</span>
                            <input type="number" value={portEnd} onChange={(e) => setPortEnd(e.target.value)}
                                   className="w-full h-8 bg-surface border border-border rounded px-2.5 text-[12px] font-mono text-text-primary focus:outline-none focus:border-accent"/>
                        </label>
                    </div>
                    <label className="block">
                        <span className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">Reserved RAM (MB)</span>
                        <input type="number" value={reservedRam} onChange={(e) => setReservedRam(e.target.value)}
                               className="w-full h-8 bg-surface border border-border rounded px-2.5 text-[12px] font-mono text-text-primary focus:outline-none focus:border-accent"/>
                    </label>
                </div>
                <div className="flex justify-end gap-2 mt-6">
                    <button onClick={onClose}
                            className="px-3 py-1.5 text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted border border-border rounded hover:bg-surface-high transition-colors">
                        Cancel
                    </button>
                    <button onClick={save} disabled={saving}
                            className="px-3 py-1.5 text-[12px] font-heading font-bold uppercase tracking-widest bg-accent text-bg rounded hover:bg-accent-bright transition-colors disabled:opacity-40">
                        {saving ? "Saving…" : "Save"}
                    </button>
                </div>
            </div>
        </div>
    );
}

// ── Tabs ──────────────────────────────────────────────────────────────────────

const TABS = ["Overview", "Servers", "Metrics"] as const;
type Tab = (typeof TABS)[number];

// ── Overview tab ──────────────────────────────────────────────────────────────

function OverviewTab({node, servers}: { node: Node; servers: Server[] }) {
    const ramPct = node.total_ram_mb > 0 ? Math.min(100, (node.allocated_ram_mb / node.total_ram_mb) * 100) : 0;
    const cpuPct = node.total_cpu_shares > 0 ? Math.min(100, (node.allocated_cpu_shares / node.total_cpu_shares) * 100) : 0;

    return (
        <div className="px-6 py-6 space-y-6">
            {/* Stat cards */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                <StatCard label="RAM Allocated">
                    <ResourceBar used={node.allocated_ram_mb} total={node.total_ram_mb} fmt={fmtMb}/>
                </StatCard>
                <StatCard label="CPU Allocated">
                    <div className="flex flex-col gap-1.5">
                        <p className="font-mono text-[20px] text-text-primary leading-none">
                            {cpuPct.toFixed(0)}%
                        </p>
                        <p className="font-mono text-[12px] text-text-muted">
                            {node.allocated_cpu_shares} / {node.total_cpu_shares} shares
                        </p>
                        <div className="h-1.5 rounded-full bg-surface-higher w-full overflow-hidden">
                            <div
                                className={`h-full rounded-full ${fillColorBg(cpuPct)}`}
                                style={{width: `${cpuPct}%`}}
                            />
                        </div>
                    </div>
                </StatCard>
                <StatCard label="Servers">
                    <p className="font-mono text-[20px] text-text-primary leading-none">
                        {servers.length}
                    </p>
                    <p className="font-mono text-[12px] text-text-muted">
                        {servers.filter((s) => s.status === "HEALTHY").length} healthy
                    </p>
                </StatCard>
                <StatCard label="Status">
          <span
              className={`self-start text-[12px] font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${nodeStatusClass(node.status, node.health)}`}
          >
            {nodeStatusLabel(node.status, node.health)}
          </span>
                    {node.last_seen_at && (
                        <p className="text-[12px] text-text-muted">last seen {timeAgo(node.last_seen_at)}</p>
                    )}
                </StatCard>
            </div>

            {/* Info panel */}
            <div className="bg-surface border border-border rounded p-4">
                <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted mb-2">
                    Node Info
                </p>
                <InfoRow label="Hostname" value={node.hostname}/>
                <InfoRow label="Public IP" value={node.public_ip}/>
                <InfoRow label="Private IP" value={node.private_ip}/>
                <InfoRow label="Port Range" value={`${node.port_range_start}–${node.port_range_end}`}/>
                <InfoRow label="Agent" value={node.agent_version ?? "—"}/>
                <InfoRow label="RAM Total" value={fmtMb(node.total_ram_mb)}/>
                <InfoRow label="RAM Reserved" value={fmtMb(node.reserved_ram_mb)}/>
                <InfoRow label="CPU Shares" value={String(node.total_cpu_shares)}/>
                <InfoRow label="Last Seen" value={node.last_seen_at ? timeAgo(node.last_seen_at) : "—"}/>
                <InfoRow label="Created" value={new Date(node.created_at).toLocaleDateString()}/>
            </div>

            {/* RAM allocation bar */}
            <div className="bg-surface border border-border rounded p-4">
                <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted mb-3">
                    RAM Allocation
                </p>
                <div className="flex items-center gap-3">
                    <div className="flex-1 h-3 rounded-full bg-surface-higher overflow-hidden">
                        <div
                            className={`h-full rounded-full transition-all ${fillColorBg(ramPct)}`}
                            style={{width: `${ramPct}%`}}
                        />
                    </div>
                    <span className="font-mono text-[12px] text-text-dim shrink-0 w-36 text-right">
            {fmtMb(node.allocated_ram_mb)} / {fmtMb(node.total_ram_mb)}
          </span>
                </div>
            </div>
        </div>
    );
}

// ── Servers tab ───────────────────────────────────────────────────────────────

function ServersTab({servers}: { servers: Server[] }) {
    if (servers.length === 0) {
        return (
            <div className="px-6 py-10">
                <div className="border-2 border-dashed border-border rounded-md py-10 text-center text-text-muted text-[13px]">
                    No servers assigned to this node
                </div>
            </div>
        );
    }

    return (
        <div className="px-6 py-4">
            <table className="w-full border-collapse">
                <thead>
                <tr className="border-b border-border">
                    {["Server", "Type", "Status", "RAM", "Port", ""].map((col, i) => (
                        <th
                            key={i}
                            className={[
                                "pb-2 text-[9px] font-mono font-semibold uppercase tracking-[0.1em] text-text-muted",
                                i === 5 ? "text-right" : "text-left pr-4",
                            ].join(" ")}
                        >
                            {col}
                        </th>
                    ))}
                </tr>
                </thead>
                <tbody>
                {servers.map((s) => {
                    const sCls = serverStatusClass(s.status);
                    return (
                        <tr key={s.id} className="border-b border-border hover:bg-surface transition-colors">
                            <td className="py-3 pr-4">
                                <p className="text-[13px] font-heading font-bold text-text-primary leading-none">{s.display_name}</p>
                                <p className="mt-0.5 font-mono text-[12px] text-text-muted leading-none">{s.name}</p>
                            </td>
                            <td className="py-3 pr-4">
                  <span className="font-mono text-[12px] uppercase tracking-wider text-text-dim border border-border px-1.5 py-0.5 rounded">
                    {s.server_type}
                  </span>
                            </td>
                            <td className="py-3 pr-4">
                  <span className={`text-[12px] font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${sCls}`}>
                    {s.status}
                  </span>
                            </td>
                            <td className="py-3 pr-4">
                                <span className="font-mono text-[12px] text-text-muted">{fmtMb(s.memory_mb)}</span>
                            </td>
                            <td className="py-3 pr-4">
                                <span className="font-mono text-[12px] text-text-muted">{s.host_port ?? "—"}</span>
                            </td>
                            <td className="py-3 text-right">
                                <Link
                                    href={`/servers/${s.id}`}
                                    className="text-[12px] font-heading font-bold uppercase tracking-wider text-text-muted hover:text-accent transition-colors"
                                >
                                    View →
                                </Link>
                            </td>
                        </tr>
                    );
                })}
                </tbody>
            </table>
        </div>
    );
}

// ── Metrics tab ───────────────────────────────────────────────────────────────

type TimeRange = "1h" | "6h" | "24h";
const TIME_RANGE_HOURS: Record<TimeRange, number> = {"1h": 1, "6h": 6, "24h": 24};

type MetricsPoint = {
    t: string; ts: number;
    cpu: number; ramUsed: number; ramTotal: number;
    diskUsed: number; diskTotal: number; netIn: number; netOut: number;
};

const BUFFER_MAX = 360;

function MetricsTab({nodeId}: { nodeId: string }) {
    const [range, setRange] = useState<TimeRange>("1h");
    const [loading, setLoading] = useState(true);
    const [buffer, setBuffer] = useState<MetricsPoint[]>([]);
    const {subscribe} = useWs();

    // Initial historical load
    useEffect(() => {
        void (async () => {
            const {data} = await getNodeMetrics({path: {id: nodeId}});
            if (data) {
                const pts = data.timestamps.map((t, i) => ({
                    t,
                    ts: new Date(t).getTime(),
                    cpu: data.cpu_percent[i] ?? 0,
                    ramUsed: data.ram_used_mb[i] ?? 0,
                    ramTotal: data.ram_total_mb[i] ?? 0,
                    diskUsed: data.disk_used_bytes[i] ?? 0,
                    diskTotal: data.disk_total_bytes[i] ?? 0,
                    netIn: data.net_in_bytes[i] ?? 0,
                    netOut: data.net_out_bytes[i] ?? 0,
                }));
                setBuffer(pts.slice(-BUFFER_MAX));
            }
            setLoading(false);
        })();
    }, [nodeId]);

    // Live WS updates
    useEffect(() => {
        return subscribe("node.metrics", (payload) => {
            if (payload.node_id !== nodeId) return;
            const t = payload.recorded_at ?? new Date().toISOString();
            const pt: MetricsPoint = {
                t,
                ts: new Date(t).getTime(),
                cpu: payload.cpu_percent ?? 0,
                ramUsed: payload.ram_used_mb ?? 0,
                ramTotal: payload.ram_total_mb ?? 0,
                diskUsed: payload.disk_used_bytes ?? 0,
                diskTotal: payload.disk_total_bytes ?? 0,
                netIn: payload.net_in_bytes ?? 0,
                netOut: payload.net_out_bytes ?? 0,
            };
            setBuffer((prev) => [...prev.slice(-(BUFFER_MAX - 1)), pt]);
        });
    }, [subscribe, nodeId]);

    const cutoff = TIME_RANGE_HOURS[range] * 3600 * 1000;
    const now = new Date().getTime();
    const points = buffer.filter((p) => p.ts >= now - cutoff);

    function fmtAxisTime(t: string) {
        return new Date(t).toLocaleTimeString([], {hour: "2-digit", minute: "2-digit"});
    }

    const chartStyle = {
        cartesianGrid: {strokeDasharray: "3 3", stroke: "var(--border)"},
        xAxis: {tick: {fill: "var(--text-muted)", fontSize: 10}, tickLine: false, axisLine: false},
        yAxis: {tick: {fill: "var(--text-muted)", fontSize: 10}, tickLine: false, axisLine: false, width: 44},
        tooltip: {contentStyle: {background: "var(--surface-higher)", border: "1px solid var(--border)", borderRadius: 4, fontSize: 11, color: "var(--text-primary)"}},
    };

    if (loading) {
        return (
            <div className="px-6 py-6 space-y-4">
                {Array.from({length: 3}).map((_, i) => (
                    <div key={i} className="h-40 bg-surface rounded animate-pulse"/>
                ))}
            </div>
        );
    }

    if (points.length === 0) {
        return (
            <div className="px-6 py-10">
                <div className="border-2 border-dashed border-border rounded-md py-10 text-center text-text-muted text-[13px]">
                    No metrics available for the selected time range
                </div>
            </div>
        );
    }

    const lastRamTotal = points.at(-1)?.ramTotal ?? 0;
    const lastDiskTotal = points.at(-1)?.diskTotal ?? 0;

    return (
        <div className="px-6 py-6 space-y-6">
            {/* Time range selector */}
            <div className="flex items-center gap-1">
                {(["1h", "6h", "24h"] as TimeRange[]).map((r) => (
                    <button
                        key={r}
                        onClick={() => setRange(r)}
                        className={[
                            "px-3 py-1 text-[12px] font-heading font-bold uppercase tracking-widest rounded border transition-colors",
                            range === r
                                ? "bg-accent text-bg border-accent"
                                : "text-text-muted border-border hover:bg-surface-high hover:text-text-primary",
                        ].join(" ")}
                    >
                        {r}
                    </button>
                ))}
            </div>

            {/* CPU % */}
            <div className="bg-surface border border-border rounded p-4">
                <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted mb-4">
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
                        <Line type="monotone" dataKey="cpu" stroke="var(--accent)" strokeWidth={1.5} dot={false}/>
                    </LineChart>
                </ResponsiveContainer>
            </div>

            {/* RAM */}
            <div className="bg-surface border border-border rounded p-4">
                <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted mb-4">
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
                        {lastRamTotal > 0 && (
                            <ReferenceLine y={lastRamTotal} stroke="var(--border)" strokeDasharray="4 2"
                                           label={{value: `Total ${fmtMb(lastRamTotal)}`, fill: "var(--text-muted)", fontSize: 10, position: "insideTopRight"}}/>
                        )}
                        <Line type="monotone" dataKey="ramUsed" stroke="var(--healthy)" strokeWidth={1.5} dot={false}/>
                    </LineChart>
                </ResponsiveContainer>
            </div>

            {/* Disk */}
            <div className="bg-surface border border-border rounded p-4">
                <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted mb-4">
                    Disk Usage
                </p>
                <ResponsiveContainer width="100%" height={140}>
                    <LineChart data={points} margin={{top: 0, right: 8, bottom: 0, left: 0}}>
                        <CartesianGrid {...chartStyle.cartesianGrid} />
                        <XAxis dataKey="t" tickFormatter={fmtAxisTime} {...chartStyle.xAxis} />
                        <YAxis tickFormatter={(v) => fmtBytesNetworkIo(v)} {...chartStyle.yAxis} />
                        <Tooltip
                            {...chartStyle.tooltip}
                            // eslint-disable-next-line @typescript-eslint/no-explicit-any
                            formatter={(v: any) => [fmtBytes(v as number), "Disk Used"]}
                            // eslint-disable-next-line @typescript-eslint/no-explicit-any
                            labelFormatter={(t: any) => fmtAxisTime(String(t))}
                        />
                        {lastDiskTotal > 0 && (
                            <ReferenceLine y={lastDiskTotal} stroke="var(--border)" strokeDasharray="4 2"
                                           label={{value: `Total ${fmtBytes(lastDiskTotal)}`, fill: "var(--text-muted)", fontSize: 10, position: "insideTopRight"}}/>
                        )}
                        <Line type="monotone" dataKey="diskUsed" stroke="var(--warning)" strokeWidth={1.5} dot={false}/>
                    </LineChart>
                </ResponsiveContainer>
            </div>

            {/* Network */}
            <div className="bg-surface border border-border rounded p-4">
                <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted mb-4">
                    Network I/O
                </p>
                <div className="flex items-center gap-4 mb-3">
                    <div className="flex items-center gap-1.5">
                        <div className="w-3 h-0.5 rounded" style={{background: "var(--healthy)"}}/>
                        <span className="text-[12px] font-mono text-text-muted">Net ↓</span>
                    </div>
                    <div className="flex items-center gap-1.5">
                        <div className="w-3 h-0.5 rounded" style={{background: "var(--accent)"}}/>
                        <span className="text-[12px] font-mono text-text-muted">Net ↑</span>
                    </div>
                </div>
                <ResponsiveContainer width="100%" height={140}>
                    <LineChart data={points} margin={{top: 0, right: 8, bottom: 0, left: 0}}>
                        <CartesianGrid {...chartStyle.cartesianGrid} />
                        <XAxis dataKey="t" tickFormatter={fmtAxisTime} {...chartStyle.xAxis} />
                        <YAxis tickFormatter={(v) => fmtBytesNetworkIo(v)} {...chartStyle.yAxis} />
                        <Tooltip
                            {...chartStyle.tooltip}
                            // eslint-disable-next-line @typescript-eslint/no-explicit-any
                            formatter={(v: any, name: any) => [fmtBytes(v as number), name === "netIn" ? "Net ↓" : "Net ↑"]}
                            // eslint-disable-next-line @typescript-eslint/no-explicit-any
                            labelFormatter={(t: any) => fmtAxisTime(String(t))}
                        />
                        <Line type="monotone" dataKey="netIn" stroke="var(--healthy)" strokeWidth={1.5} dot={false}/>
                        <Line type="monotone" dataKey="netOut" stroke="var(--accent)" strokeWidth={1.5} dot={false}/>
                    </LineChart>
                </ResponsiveContainer>
            </div>
        </div>
    );
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function NodeDetailPage() {
    const params = useParams();
    const id = params.id as string;
    const router = useRouter();
    const {user} = useAuth();
    const permissions = user?.permissions ?? [];
    const {subscribe} = useWs();

    const [node, setNode] = useState<Node | null>(null);
    const [servers, setServers] = useState<Server[]>([]);
    const [loading, setLoading] = useState(true);
    const [notFound, setNotFound] = useState(false);
    const [activeTab, setActiveTab] = useState<Tab>("Overview");
    const [actionError, setActionError] = useState<string | null>(null);
    const [pending, setPending] = useState<string | null>(null);
    const {confirm, dialog} = useConfirmDialog();

    // Modals
    const [showEdit, setShowEdit] = useState(false);
    const [tokenKey, setTokenKey] = useState<string | null>(null);

    const fetchNode = useCallback(async () => {
        const {data, response} = await getNode({path: {id}});
        if (response?.status === 404) setNotFound(true);
        if (data) setNode(data);
        setLoading(false);
    }, [id]);

    useEffect(() => {
        void fetchNode();
        void listServers().then(({data}) => {
            if (data) setServers(data.filter((s) => s.node_id === id));
        });
        const timer = setInterval(fetchNode, 30_000);
        return () => clearInterval(timer);
    }, [fetchNode, id]);

    useEffect(() => {
        return subscribe("node.status", (payload) => {
            if (payload.node_id !== id) return;
            void fetchNode();
        });
    }, [subscribe, id, fetchNode]);

    // ── Actions ────────────────────────────────────────────────────────────────

    async function doTrust() {
        setPending("trust");
        setActionError(null);
        const {error: trustErr} = await trustNode({path: {id}});
        if (trustErr) setActionError(trustErr.message ?? "Failed to trust node"); else await fetchNode();
        setPending(null);
    }

    function doReject() {
        confirm({
            title: "Reject Node?",
            description: "The agent will not be able to connect.",
            destructive: true,
            onConfirm: async () => {
                setPending("reject");
                setActionError(null);
                const {error: rejectErr} = await rejectNode({path: {id}});
                if (rejectErr) setActionError(rejectErr.message ?? "Failed to reject node"); else await fetchNode();
                setPending(null);
            },
        });
    }

    function doRotateToken() {
        confirm({
            title: "Rotate Node Key?",
            description: "The agent will need to re-register.",
            onConfirm: async () => {
                setPending("rotate");
                setActionError(null);
                const {error: rotateErr, data: rotateData} = await rotateNodeToken({path: {id}});
                if (rotateErr) setActionError(rotateErr.message ?? "Failed to rotate key"); else setTokenKey(rotateData!.node_key);
                setPending(null);
            },
        });
    }

    function doShutdown() {
        if (!node) return;
        confirm({
            title: "Shutdown Node?",
            description: `Send shutdown command to "${node.display_name}"?`,
            onConfirm: async () => {
                setPending("shutdown");
                setActionError(null);
                const {error: shutdownErr} = await shutdownNode({path: {id}});
                if (shutdownErr) setActionError(shutdownErr.message ?? "Failed to shutdown node"); else await fetchNode();
                setPending(null);
            },
        });
    }

    function doDecommission() {
        if (!node) return;
        confirm({
            title: "Decommission Node?",
            description: `Decommission "${node.display_name}"? This cannot be undone.`,
            destructive: true,
            onConfirm: async () => {
                setPending("decommission");
                setActionError(null);
                const {error: decomErr} = await decommissionNode({path: {id}});
                if (decomErr) setActionError(decomErr.message ?? "Failed to decommission node"); else router.push("/nodes");
                setPending(null);
            },
        });
    }

    // ── Guards ─────────────────────────────────────────────────────────────────

    if (loading) {
        return (
            <div className="px-6 pt-6 space-y-4">
                <div className="h-4 w-40 bg-surface rounded animate-pulse"/>
                <div className="h-8 w-64 bg-surface rounded animate-pulse"/>
                <div className="h-4 w-48 bg-surface rounded animate-pulse"/>
            </div>
        );
    }

    if (notFound || !node) {
        return (
            <div className="px-6 py-10 text-center text-text-muted text-[13px]">
                Node not found.{" "}
                <Link href="/nodes" className="text-accent hover:underline">Back to nodes</Link>
            </div>
        );
    }

    const canManage = hasPermission(permissions, "system.nodes");

    // ── Render ─────────────────────────────────────────────────────────────────

    return (
        <div>
            {/* ── Page header ── */}
            <div className="px-6 pt-6 pb-5 border-b border-border">
                {/* Breadcrumb */}
                <div className="flex items-center gap-1.5 text-[12px] font-heading font-bold uppercase tracking-wider text-text-muted mb-4">
                    <Link href="/nodes" className="hover:text-text-primary transition-colors">Nodes</Link>
                    <ChevronRight size={11} strokeWidth={2.5}/>
                    <span className="text-text-dim">{node.display_name}</span>
                </div>

                {/* Name row + actions */}
                <div className="flex items-start justify-between gap-4">
                    <div className="flex items-center gap-3 flex-wrap">
                        <h1 className="text-[22px] font-heading font-bold uppercase tracking-wide text-text-primary leading-none">
                            {node.display_name}
                        </h1>
                        <span className={`text-[12px] font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${nodeStatusClass(node.status, node.health)}`}>
              {nodeStatusLabel(node.status, node.health)}
            </span>
                    </div>

                    {canManage && (
                        <div className="flex items-center gap-2 shrink-0 flex-wrap">
                            {node.status === "PENDING" && (
                                <>
                                    <HeaderActionButton
                                        icon={<Check size={12} strokeWidth={2.5}/>}
                                        label="Trust"
                                        loading={pending === "trust"}
                                        onClick={doTrust}
                                        variant="green"
                                    />
                                    <HeaderActionButton
                                        icon={<Ban size={12} strokeWidth={2.5}/>}
                                        label="Reject"
                                        loading={pending === "reject"}
                                        onClick={doReject}
                                        variant="red"
                                    />
                                </>
                            )}
                            <HeaderActionButton
                                icon={<Pencil size={12} strokeWidth={2.5}/>}
                                label="Edit"
                                loading={false}
                                onClick={() => setShowEdit(true)}
                                variant="default"
                            />
                            <HeaderActionButton
                                icon={<KeyRound size={12} strokeWidth={2.5}/>}
                                label="Rotate Key"
                                loading={pending === "rotate"}
                                onClick={doRotateToken}
                                variant="default"
                            />
                            {node.status === "ACTIVE" && (
                                <HeaderActionButton
                                    icon={<Power size={12} strokeWidth={2.5}/>}
                                    label="Shutdown"
                                    loading={pending === "shutdown"}
                                    onClick={doShutdown}
                                    variant="amber"
                                />
                            )}
                            {servers.length === 0 && node.status !== "DECOMMISSIONED" && (
                                <HeaderActionButton
                                    icon={<Trash2 size={12} strokeWidth={2.5}/>}
                                    label="Decommission"
                                    loading={pending === "decommission"}
                                    onClick={doDecommission}
                                    variant="red"
                                />
                            )}
                        </div>
                    )}
                </div>

                {/* Meta */}
                <p className="mt-2 font-mono text-[12px] text-text-muted">
                    {node.hostname} · {node.private_ip} · {node.agent_version ?? "unknown agent"}
                </p>
            </div>

            {/* Error banner */}
            {actionError && (
                <div className="mx-6 mt-4 flex items-center justify-between bg-error/10 border border-error/30 text-error rounded px-3 py-2 text-[12px]">
                    <span>{actionError}</span>
                    <button onClick={() => setActionError(null)} className="ml-4 hover:opacity-70">
                        <X size={13}/>
                    </button>
                </div>
            )}

            {/* ── Tab bar ── */}
            <div className="flex items-end px-6 border-b border-border bg-surface">
                {TABS.map((tab) => (
                    <button
                        key={tab}
                        onClick={() => setActiveTab(tab)}
                        className={[
                            "relative px-4 py-3 text-[12px] font-heading font-bold uppercase tracking-widest transition-colors",
                            activeTab === tab
                                ? "text-accent after:absolute after:bottom-0 after:left-0 after:right-0 after:h-[2px] after:bg-accent"
                                : "text-text-dim hover:text-text-primary",
                        ].join(" ")}
                    >
                        {tab}
                        {tab === "Servers" && servers.length > 0 && (
                            <span className="ml-1.5 font-mono text-[12px] text-text-muted">{servers.length}</span>
                        )}
                    </button>
                ))}
            </div>

            {/* ── Tab content ── */}
            {activeTab === "Overview" && <OverviewTab node={node} servers={servers}/>}
            {activeTab === "Servers" && <ServersTab servers={servers}/>}
            {activeTab === "Metrics" && <MetricsTab nodeId={id}/>}

            {/* Modals */}
            {showEdit && (
                <EditModal node={node} onClose={() => setShowEdit(false)} onSaved={fetchNode}/>
            )}
            {tokenKey && (
                <TokenModal nodeKey={tokenKey} onClose={() => setTokenKey(null)}/>
            )}
            {dialog}
        </div>
    );
}
