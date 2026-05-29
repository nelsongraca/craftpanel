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
import {timeAgo} from "@/lib/utils/format";
import {TokenModal} from "@/components/nodes/TokenModal";
import type {IoCraftpanelMasterServiceServerResponse as Server} from "@/lib/generated/types.gen";

// ── Status helpers ────────────────────────────────────────────────────────────

type NodeStatus = "ACTIVE" | "PENDING" | "DEGRADED" | "REJECTED" | "DECOMMISSIONED";

function toNodeStatus(s: string): NodeStatus {
    return (["ACTIVE", "PENDING", "DEGRADED", "REJECTED", "DECOMMISSIONED"].includes(s)
        ? s
        : "PENDING") as NodeStatus;
}

const STATUS_LABELS: Record<NodeStatus, string> = {
    ACTIVE: "Active", PENDING: "Pending", DEGRADED: "Degraded",
    REJECTED: "Rejected", DECOMMISSIONED: "Decommissioned",
};

const STATUS_CLASSES: Record<NodeStatus, string> = {
    ACTIVE: "text-healthy  border border-healthy/30  bg-healthy/10",
    PENDING: "text-warning  border border-warning/30  bg-warning/10",
    DEGRADED: "text-error    border border-error/30    bg-error/10",
    REJECTED: "text-text-muted border border-border   bg-surface-high",
    DECOMMISSIONED: "text-text-muted border border-border   bg-surface-high",
};

// Server status labels reused for the servers tab
const SERVER_STATUS_CLASSES: Record<string, string> = {
    HEALTHY: "text-healthy  border border-healthy/30  bg-healthy/10",
    UNHEALTHY: "text-error    border border-error/30    bg-error/10",
    STARTING: "text-warning  border border-warning/30  bg-warning/10",
    STOPPING: "text-warning  border border-warning/30  bg-warning/10",
    STOPPED: "text-text-muted border border-border   bg-surface-high",
};

// ── Utilities ─────────────────────────────────────────────────────────────────

function fmtMb(mb: number): string {
    return mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${mb} MB`;
}

function fmtBytes(b: number): string {
    if (b >= 1e9) return `${(b / 1e9).toFixed(1)} GB`;
    if (b >= 1e6) return `${(b / 1e6).toFixed(1)} MB`;
    if (b >= 1e3) return `${(b / 1e3).toFixed(1)} KB`;
    return `${b} B`;
}

function fillColor(pct: number): string {
    if (pct >= 86) return "bg-error";
    if (pct >= 66) return "bg-warning";
    return "bg-accent";
}

// ── Sub-components ────────────────────────────────────────────────────────────

function StatCard({label, children}: { label: string; children: React.ReactNode }) {
    return (
        <div className="bg-surface border border-border rounded p-4 flex flex-col gap-2">
            <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">
                {label}
            </p>
            {children}
        </div>
    );
}

function ResourceBar({used, total, fmt}: { used: number; total: number; fmt: (v: number) => string }) {
    const pct = total > 0 ? Math.min(100, (used / total) * 100) : 0;
    const cls = fillColor(pct);
    return (
        <div className="flex flex-col gap-1.5">
            <p className="font-mono text-[20px] text-text-primary leading-none">{fmt(used)}</p>
            <p className="font-mono text-[11px] text-text-muted">{fmt(used)} / {fmt(total)}</p>
            <div className="h-1.5 rounded-full bg-surface-higher w-full overflow-hidden">
                <div className={`h-full rounded-full ${cls}`} style={{width: `${pct}%`}}/>
            </div>
        </div>
    );
}

function InfoRow({label, value}: { label: string; value: React.ReactNode }) {
    return (
        <div className="flex items-start justify-between gap-4 py-2 border-b border-border last:border-0">
      <span className="text-[11px] font-heading font-bold uppercase tracking-wider text-text-muted shrink-0">
        {label}
      </span>
            <span className="font-mono text-[12px] text-text-primary text-right">{value}</span>
        </div>
    );
}

function HeaderActionButton({
                                icon, label, loading, onClick, variant, disabled,
                            }: {
    icon: React.ReactNode;
    label: string;
    loading: boolean;
    onClick: () => void;
    variant: "green" | "red" | "amber" | "default";
    disabled?: boolean;
}) {
    const cls = {
        green: "text-healthy  border-healthy/40  hover:bg-healthy/10",
        red: "text-error    border-error/40    hover:bg-error/10",
        amber: "text-warning  border-warning/40  hover:bg-warning/10",
        default: "text-text-muted border-border   hover:bg-surface-high hover:text-text-primary",
    }[variant];

    return (
        <button
            onClick={onClick}
            disabled={loading || disabled}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded border text-[11px] font-heading font-bold uppercase tracking-widest transition-colors disabled:opacity-40 ${cls}`}
        >
            {loading ? (
                <span className="w-3 h-3 border border-current border-t-transparent rounded-full animate-spin"/>
            ) : icon}
            {label}
        </button>
    );
}

// ── Edit modal ─────────────────────────────────────────────────────────────────

function EditModal({node, onClose, onSaved}: { node: Node; onClose: () => void; onSaved: () => void }) {
    const [displayName, setDisplayName] = useState(node.display_name);
    const [portStart, setPortStart] = useState(String(node.port_range_start));
    const [portEnd, setPortEnd] = useState(String(node.port_range_end));
    const [dataPath, setDataPath] = useState(node.data_path);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    async function save() {
        setSaving(true);
        setError(null);
        try {
            const {error: e} = await updateNode({
                path: {id: node.id},
                body: {
                    display_name: displayName || null,
                    port_range_start: portStart ? parseInt(portStart) : null,
                    port_range_end: portEnd ? parseInt(portEnd) : null,
                    data_path: dataPath || null,
                },
            });
            if (e) {
                setError(e.message ?? "Failed to save");
            } else {
                onSaved();
                onClose();
            }
        } catch {
            setError("Failed to save");
        } finally {
            setSaving(false);
        }
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
                        <span className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">Display Name</span>
                        <input value={displayName} onChange={(e) => setDisplayName(e.target.value)}
                               className="w-full h-8 bg-surface border border-border rounded px-2.5 text-[12px] font-mono text-text-primary focus:outline-none focus:border-accent"/>
                    </label>
                    <div className="grid grid-cols-2 gap-3">
                        <label className="block">
                            <span className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">Port Start</span>
                            <input type="number" value={portStart} onChange={(e) => setPortStart(e.target.value)}
                                   className="w-full h-8 bg-surface border border-border rounded px-2.5 text-[12px] font-mono text-text-primary focus:outline-none focus:border-accent"/>
                        </label>
                        <label className="block">
                            <span className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">Port End</span>
                            <input type="number" value={portEnd} onChange={(e) => setPortEnd(e.target.value)}
                                   className="w-full h-8 bg-surface border border-border rounded px-2.5 text-[12px] font-mono text-text-primary focus:outline-none focus:border-accent"/>
                        </label>
                    </div>
                    <label className="block">
                        <span className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted block mb-1">Data Path</span>
                        <input value={dataPath} onChange={(e) => setDataPath(e.target.value)}
                               className="w-full h-8 bg-surface border border-border rounded px-2.5 text-[12px] font-mono text-text-primary focus:outline-none focus:border-accent"/>
                    </label>
                </div>
                <div className="flex justify-end gap-2 mt-6">
                    <button onClick={onClose}
                            className="px-3 py-1.5 text-[11px] font-heading font-bold uppercase tracking-widest text-text-muted border border-border rounded hover:bg-surface-high transition-colors">
                        Cancel
                    </button>
                    <button onClick={save} disabled={saving}
                            className="px-3 py-1.5 text-[11px] font-heading font-bold uppercase tracking-widest bg-accent text-bg rounded hover:bg-accent-bright transition-colors disabled:opacity-40">
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
    const ns = toNodeStatus(node.status);
    const ramPct = node.total_ram_mb > 0 ? Math.min(100, (node.allocated_ram_mb / node.total_ram_mb) * 100) : 0;
    const cpuPct = node.total_cpu_shares > 0 ? Math.min(100, (node.allocated_cpu_shares / node.total_cpu_shares) * 100) : 0;

    return (
        <div className="px-6 py-6 space-y-6">
            {/* Stat cards */}
            <div className="grid grid-cols-4 gap-4">
                <StatCard label="RAM Allocated">
                    <ResourceBar used={node.allocated_ram_mb} total={node.total_ram_mb} fmt={fmtMb}/>
                </StatCard>
                <StatCard label="CPU Allocated">
                    <div className="flex flex-col gap-1.5">
                        <p className="font-mono text-[20px] text-text-primary leading-none">
                            {cpuPct.toFixed(0)}%
                        </p>
                        <p className="font-mono text-[11px] text-text-muted">
                            {node.allocated_cpu_shares} / {node.total_cpu_shares} shares
                        </p>
                        <div className="h-1.5 rounded-full bg-surface-higher w-full overflow-hidden">
                            <div
                                className={`h-full rounded-full ${fillColor(cpuPct)}`}
                                style={{width: `${cpuPct}%`}}
                            />
                        </div>
                    </div>
                </StatCard>
                <StatCard label="Servers">
                    <p className="font-mono text-[20px] text-text-primary leading-none">
                        {servers.length}
                    </p>
                    <p className="font-mono text-[11px] text-text-muted">
                        {servers.filter((s) => s.status === "HEALTHY").length} healthy
                    </p>
                </StatCard>
                <StatCard label="Status">
          <span
              className={`self-start text-[11px] font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${STATUS_CLASSES[ns]}`}
          >
            {STATUS_LABELS[ns]}
          </span>
                    {node.last_seen_at && (
                        <p className="text-[11px] text-text-muted">last seen {timeAgo(node.last_seen_at)}</p>
                    )}
                </StatCard>
            </div>

            {/* Info panel */}
            <div className="bg-surface border border-border rounded p-4">
                <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted mb-2">
                    Node Info
                </p>
                <InfoRow label="Hostname" value={node.hostname}/>
                <InfoRow label="Public IP" value={node.public_ip}/>
                <InfoRow label="Private IP" value={node.private_ip}/>
                <InfoRow label="Data Path" value={node.data_path}/>
                <InfoRow label="Port Range" value={`${node.port_range_start}–${node.port_range_end}`}/>
                <InfoRow label="Agent" value={node.agent_version ?? "—"}/>
                <InfoRow label="RAM Total" value={fmtMb(node.total_ram_mb)}/>
                <InfoRow label="CPU Shares" value={String(node.total_cpu_shares)}/>
                <InfoRow label="Last Seen" value={node.last_seen_at ? timeAgo(node.last_seen_at) : "—"}/>
                <InfoRow label="Created" value={new Date(node.created_at).toLocaleDateString()}/>
            </div>

            {/* RAM allocation bar */}
            <div className="bg-surface border border-border rounded p-4">
                <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted mb-3">
                    RAM Allocation
                </p>
                <div className="flex items-center gap-3">
                    <div className="flex-1 h-3 rounded-full bg-surface-higher overflow-hidden">
                        <div
                            className={`h-full rounded-full transition-all ${fillColor(ramPct)}`}
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
                    const sCls = SERVER_STATUS_CLASSES[s.status] ?? SERVER_STATUS_CLASSES["STOPPED"];
                    return (
                        <tr key={s.id} className="border-b border-border hover:bg-surface transition-colors">
                            <td className="py-3 pr-4">
                                <p className="text-[13px] font-heading font-bold text-text-primary leading-none">{s.display_name}</p>
                                <p className="mt-0.5 font-mono text-[11px] text-text-muted leading-none">{s.name}</p>
                            </td>
                            <td className="py-3 pr-4">
                  <span className="font-mono text-[10px] uppercase tracking-wider text-text-dim border border-border px-1.5 py-0.5 rounded">
                    {s.server_type}
                  </span>
                            </td>
                            <td className="py-3 pr-4">
                  <span className={`text-[11px] font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${sCls}`}>
                    {s.status}
                  </span>
                            </td>
                            <td className="py-3 pr-4">
                                <span className="font-mono text-[11px] text-text-muted">{fmtMb(s.memory_mb)}</span>
                            </td>
                            <td className="py-3 pr-4">
                                <span className="font-mono text-[11px] text-text-muted">{s.host_port ?? "—"}</span>
                            </td>
                            <td className="py-3 text-right">
                                <Link
                                    href={`/servers/${s.id}`}
                                    className="text-[11px] font-heading font-bold uppercase tracking-wider text-text-muted hover:text-accent transition-colors"
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
            if ((payload as Record<string, unknown>).node_id !== nodeId) return;
            const p = payload as Record<string, unknown>;
            const t = (p.recorded_at as string) ?? new Date().toISOString();
            const pt: MetricsPoint = {
                t,
                ts: new Date(t).getTime(),
                cpu: (p.cpu_percent as number) ?? 0,
                ramUsed: (p.ram_used_mb as number) ?? 0,
                ramTotal: (p.ram_total_mb as number) ?? 0,
                diskUsed: (p.disk_used_bytes as number) ?? 0,
                diskTotal: (p.disk_total_bytes as number) ?? 0,
                netIn: (p.net_in_bytes as number) ?? 0,
                netOut: (p.net_out_bytes as number) ?? 0,
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
                            "px-3 py-1 text-[11px] font-heading font-bold uppercase tracking-widest rounded border transition-colors",
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
                <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted mb-4">
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
                <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted mb-4">
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
                <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted mb-4">
                    Disk Usage
                </p>
                <ResponsiveContainer width="100%" height={140}>
                    <LineChart data={points} margin={{top: 0, right: 8, bottom: 0, left: 0}}>
                        <CartesianGrid {...chartStyle.cartesianGrid} />
                        <XAxis dataKey="t" tickFormatter={fmtAxisTime} {...chartStyle.xAxis} />
                        <YAxis tickFormatter={(v) => fmtBytes(v)} {...chartStyle.yAxis} />
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
                <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted mb-4">
                    Network I/O
                </p>
                <div className="flex items-center gap-4 mb-3">
                    <div className="flex items-center gap-1.5">
                        <div className="w-3 h-0.5 rounded" style={{background: "var(--healthy)"}}/>
                        <span className="text-[10px] font-mono text-text-muted">Net ↓</span>
                    </div>
                    <div className="flex items-center gap-1.5">
                        <div className="w-3 h-0.5 rounded" style={{background: "var(--accent)"}}/>
                        <span className="text-[10px] font-mono text-text-muted">Net ↑</span>
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

    const [node, setNode] = useState<Node | null>(null);
    const [servers, setServers] = useState<Server[]>([]);
    const [loading, setLoading] = useState(true);
    const [notFound, setNotFound] = useState(false);
    const [activeTab, setActiveTab] = useState<Tab>("Overview");
    const [actionError, setActionError] = useState<string | null>(null);
    const [pending, setPending] = useState<string | null>(null);

    // Modals
    const [showEdit, setShowEdit] = useState(false);
    const [tokenKey, setTokenKey] = useState<string | null>(null);

    const fetchNode = useCallback(async () => {
        const {data, response} = await getNode({path: {id}});
        if (response?.status === 404) {
            setNotFound(true);
            setLoading(false);
            return;
        }
        if (data) setNode(data);
        setLoading(false);
    }, [id]);

    useEffect(() => {
        void fetchNode();
    }, [fetchNode]);

    useEffect(() => {
        listServers().then(({data}) => {
            if (data) setServers(data.filter((s) => s.node_id === id));
        });
    }, [id]);

    useEffect(() => {
        const timer = setInterval(fetchNode, 30_000);
        return () => clearInterval(timer);
    }, [fetchNode]);

    // ── Actions ────────────────────────────────────────────────────────────────

    async function doTrust() {
        setPending("trust");
        setActionError(null);
        try {
            const {error} = await trustNode({path: {id}});
            if (error) {
                setActionError(error.message ?? "Failed to trust node");
            } else {
                await fetchNode();
            }
        } catch {
            setActionError("Failed to trust node");
        } finally {
            setPending(null);
        }
    }

    async function doReject() {
        if (!window.confirm("Reject this node? The agent will not be able to connect.")) return;
        setPending("reject");
        setActionError(null);
        try {
            const {error} = await rejectNode({path: {id}});
            if (error) {
                setActionError(error.message ?? "Failed to reject node");
            } else {
                await fetchNode();
            }
        } catch {
            setActionError("Failed to reject node");
        } finally {
            setPending(null);
        }
    }

    async function doRotateToken() {
        if (!window.confirm("Rotate the node key? The agent will need to re-register.")) return;
        setPending("rotate");
        setActionError(null);
        try {
            const {data, error} = await rotateNodeToken({path: {id}});
            if (error) {
                setActionError(error.message ?? "Failed to rotate token");
            } else if (data) {
                setTokenKey(data.node_key);
            }
        } catch {
            setActionError("Failed to rotate token");
        } finally {
            setPending(null);
        }
    }

    async function doShutdown() {
        if (!node) return;
        if (!window.confirm(`Send shutdown command to "${node.display_name}"?`)) return;
        setPending("shutdown");
        setActionError(null);
        try {
            const {error} = await shutdownNode({path: {id}});
            if (error) {
                setActionError(error.message ?? "Failed to shutdown node");
            } else {
                await fetchNode();
            }
        } catch {
            setActionError("Failed to shutdown node");
        } finally {
            setPending(null);
        }
    }

    async function doDecommission() {
        if (!node) return;
        if (!window.confirm(`Decommission "${node.display_name}"? This cannot be undone.`)) return;
        setPending("decommission");
        setActionError(null);
        try {
            const {error} = await decommissionNode({path: {id}});
            if (error) {
                setActionError(error.message ?? "Failed to decommission node");
            } else {
                router.push("/nodes");
            }
        } catch {
            setActionError("Failed to decommission node");
        } finally {
            setPending(null);
        }
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

    const ns = toNodeStatus(node.status);
    const canManage = hasPermission(permissions, "system.nodes");

    // ── Render ─────────────────────────────────────────────────────────────────

    return (
        <div>
            {/* ── Page header ── */}
            <div className="px-6 pt-6 pb-5 border-b border-border">
                {/* Breadcrumb */}
                <div className="flex items-center gap-1.5 text-[11px] font-heading font-bold uppercase tracking-wider text-text-muted mb-4">
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
                        <span className={`text-[11px] font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${STATUS_CLASSES[ns]}`}>
              {STATUS_LABELS[ns]}
            </span>
                    </div>

                    {canManage && (
                        <div className="flex items-center gap-2 shrink-0 flex-wrap">
                            {ns === "PENDING" && (
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
                            {ns === "ACTIVE" && (
                                <HeaderActionButton
                                    icon={<Power size={12} strokeWidth={2.5}/>}
                                    label="Shutdown"
                                    loading={pending === "shutdown"}
                                    onClick={doShutdown}
                                    variant="amber"
                                />
                            )}
                            {servers.length === 0 && ns !== "DECOMMISSIONED" && (
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
                            "relative px-4 py-3 text-[11px] font-heading font-bold uppercase tracking-widest transition-colors",
                            activeTab === tab
                                ? "text-accent after:absolute after:bottom-0 after:left-0 after:right-0 after:h-[2px] after:bg-accent"
                                : "text-text-dim hover:text-text-primary",
                        ].join(" ")}
                    >
                        {tab}
                        {tab === "Servers" && servers.length > 0 && (
                            <span className="ml-1.5 font-mono text-[10px] text-text-muted">{servers.length}</span>
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
        </div>
    );
}
