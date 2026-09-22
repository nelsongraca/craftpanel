"use client";

import {useCallback, useEffect, useState} from "react";
import {useParams, useRouter} from "next/navigation";
import Link from "next/link";
import {Ban, Check, ChevronRight, KeyRound, Power, Trash2, X,} from "lucide-react";
import {CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis,} from "recharts";
import {getNode, getNodeMetrics, listServers} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import {useWs} from "@/lib/ws-context";
import type {Node} from "@/lib/types";
import {timeAgo, fmtBytes, fmtMb, fillColorBg, fmtPct, fmtCpuCores} from "@/lib/utils/format";
import {TokenModal} from "@/components/nodes/token-modal";
import type {ServerResponse as Server} from "@/lib/generated/types.gen";
import {HeaderActionButton} from "@/components/servers/header-action-button";
import {useNodeActions} from "@/components/nodes/node-actions";

import {nodeStatusLabel, nodeStatusVariant} from "@/lib/status";
import {EditNode} from "@/components/nodes/edit-node";
import {ServerList} from "@/components/servers/server-list";
import {ServerActions, useServerActions} from "@/components/servers/server-actions";
import {Badge} from "@/components/ui/badge";
import {Skeleton} from "@/components/ui/skeleton";
import {Empty, EmptyDescription} from "@/components/ui/empty";
import {Tabs, TabsList, TabsTrigger, TabsContent} from "@/components/ui/tabs";

// ── Sub-components ────────────────────────────────────────────────────────────

function StatCard({label, children}: { label: string; children: React.ReactNode }) {
    return (
        <div className="bg-surface border border-border rounded p-4 flex flex-col gap-2">
            <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted">
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
            <p className="font-mono text-xs text-text-muted">{fmt(used)} / {fmt(total)}</p>
            <div className="h-1.5 rounded-full bg-surface-higher w-full overflow-hidden">
                <div className={`h-full rounded-full ${cls}`} style={{width: `${pct}%`}}/>
            </div>
        </div>
    );
}

// ── Tabs ──────────────────────────────────────────────────────────────────────

const TABS = ["Overview", "Servers", "Metrics"] as const;
type Tab = (typeof TABS)[number];

const NODE_HEADER_ACTION_BUTTONS = {
    trust: {icon: <Check size={12} strokeWidth={2.5}/>, label: "Trust", variant: "green"},
    reject: {icon: <Ban size={12} strokeWidth={2.5}/>, label: "Reject", variant: "red"},
    rotate: {icon: <KeyRound size={12} strokeWidth={2.5}/>, label: "Rotate Key", variant: "default"},
    shutdown: {icon: <Power size={12} strokeWidth={2.5}/>, label: "Shutdown", variant: "amber"},
    decommission: {icon: <Trash2 size={12} strokeWidth={2.5}/>, label: "Decommission", variant: "red"},
} as const;

// ── Overview tab ──────────────────────────────────────────────────────────────

function OverviewTab({node, servers, onSaved, canEdit}: { node: Node; servers: Server[]; onSaved: () => void; canEdit: boolean }) {
    const cpuPct = node.total_cpu_millicores > 0 ? Math.min(100, (node.allocated_cpu_millicores / node.total_cpu_millicores) * 100) : 0;
    const ramUsedMb = Math.max(node.allocated_ram_mb, node.system_ram_used_mb ?? 0);
    const ramUsagePct = node.total_ram_mb > 0 ? Math.min(100, (ramUsedMb / node.total_ram_mb) * 100) : 0;
    const cpuUsagePct = node.system_cpu_percent != null ? Math.min(100, node.system_cpu_percent) : 0;

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
                        <p className="font-mono text-xs text-text-muted">
                            {fmtCpuCores(node.allocated_cpu_millicores)} / {fmtCpuCores(node.total_cpu_millicores)}
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
                    <p className="font-mono text-xs text-text-muted">
                        {servers.filter((s) => s.status === "HEALTHY").length} healthy
                    </p>
                </StatCard>
                <StatCard label="Status">
                    <Badge variant={nodeStatusVariant(node.status, node.health)}>{nodeStatusLabel(node.status, node.health)}</Badge>
                    {node.last_seen_at && (
                        <p className="text-xs text-text-muted">last seen {timeAgo(node.last_seen_at)}</p>
                    )}
                </StatCard>
            </div>

            {/* Info panel — inline editable */}
            <EditNode node={node} onSaved={onSaved} canEdit={canEdit}/>

            {/* Resource usage bars */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="bg-surface border border-border rounded p-4">
                    <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-3">
                        RAM Usage
                    </p>
                    <div className="flex items-center gap-3">
                        <div className="flex-1 h-3 rounded-full bg-surface-higher overflow-hidden">
                            <div
                                className={`h-full rounded-full transition-all ${fillColorBg(ramUsagePct)}`}
                                style={{width: `${ramUsagePct}%`}}
                            />
                        </div>
                        <span className="font-mono text-xs text-text-dim shrink-0 w-36 text-right">
              {fmtMb(ramUsedMb)} / {fmtMb(node.total_ram_mb)}
            </span>
                    </div>
                </div>
                <div className="bg-surface border border-border rounded p-4">
                    <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-3">
                        CPU Usage
                    </p>
                    <div className="flex items-center gap-3">
                        <div className="flex-1 h-3 rounded-full bg-surface-higher overflow-hidden">
                            <div
                                className={`h-full rounded-full transition-all ${fillColorBg(cpuUsagePct)}`}
                                style={{width: `${cpuUsagePct}%`}}
                            />
                        </div>
                        <span className="font-mono text-xs text-text-dim shrink-0 w-36 text-right">
              {node.system_cpu_percent != null ? fmtPct(node.system_cpu_percent) : "-"}
            </span>
                    </div>
                </div>
            </div>
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
                    <Skeleton key={i} className="h-40 bg-surface"/>
                ))}
            </div>
        );
    }

    if (points.length === 0) {
        return (
            <div className="px-6 py-10">
                <Empty className="border-2 border-border rounded-md py-10">
                    <EmptyDescription>No metrics available for the selected time range</EmptyDescription>
                </Empty>
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
                            "px-3 py-1 text-xs font-heading font-bold uppercase tracking-widest rounded border transition-colors",
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
                <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-4">
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
                <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-4">
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
                <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-4">
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
                <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-4">
                    Network I/O
                </p>
                <div className="flex items-center gap-4 mb-3">
                    <div className="flex items-center gap-1.5">
                        <div className="w-3 h-0.5 rounded" style={{background: "var(--healthy)"}}/>
                        <span className="text-xs font-mono text-text-muted">Net ↓</span>
                    </div>
                    <div className="flex items-center gap-1.5">
                        <div className="w-3 h-0.5 rounded" style={{background: "var(--accent)"}}/>
                        <span className="text-xs font-mono text-text-muted">Net ↑</span>
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
    const {subscribe} = useWs();

    const [node, setNode] = useState<Node | null>(null);
    const [servers, setServers] = useState<Server[]>([]);
    const [loading, setLoading] = useState(true);
    const [notFound, setNotFound] = useState(false);
    const [activeTab, setActiveTab] = useState<Tab>("Overview");

    // Modals
    const [tokenKey, setTokenKey] = useState<string | null>(null);

    const fetchNode = useCallback(async () => {
        const {data, response} = await getNode({path: {id}});
        if (response?.status === 404) setNotFound(true);
        if (data) setNode(data);
        setLoading(false);
    }, [id]);

    const fetchServers = useCallback(async () => {
        const {data} = await listServers();
        if (data) setServers(data.filter((s) => s.node_id === id));
    }, [id]);

    const {
        allowedActions: allowedServerActions,
        run: runServerAction,
        remove: removeServer,
        duplicate: duplicateServer,
        pendingFor: serverPendingFor,
        actionError: serverActionError,
        setActionError: setServerActionError,
        dialog: serverActionsDialog,
    } = useServerActions({
        permissions,
        serverPermissionsMap: user?.server_permissions ?? {},
        onChanged: () => void fetchServers(),
    });

    useEffect(() => {
        void fetchNode();
        void fetchServers();
        const timer = setInterval(fetchNode, 30_000);
        return () => clearInterval(timer);
    }, [fetchNode, fetchServers]);

    useEffect(() => {
        return subscribe("node.status", (payload) => {
            if (payload.node_id !== id) return;
            void fetchNode();
        });
    }, [subscribe, id, fetchNode]);

    const {allowedActions: allowedNodeActions, trust, reject, rotate, shutdown, decommission, pendingFor: nodePendingFor, actionError, setActionError, dialog: nodeActionsDialog} = useNodeActions({
        onChanged: () => void fetchNode(),
        onTokenRotated: setTokenKey,
        onDecommissioned: () => router.push("/nodes"),
    });

    // ── Guards ─────────────────────────────────────────────────────────────────

    if (loading) {
        return (
            <div className="px-6 pt-6 space-y-4">
                <Skeleton className="h-4 w-40 bg-surface"/>
                <Skeleton className="h-8 w-64 bg-surface"/>
                <Skeleton className="h-4 w-48 bg-surface"/>
            </div>
        );
    }

    if (notFound || !node) {
        return (
            <Empty className="min-h-[200px]">
                    <EmptyDescription>
                        Node not found.{" "}
                        <Link href="/nodes" className="text-accent hover:underline">Back to nodes</Link>
                    </EmptyDescription>
                </Empty>
        );
    }

    const canManage = hasPermission(permissions, "system.nodes");

    // ── Render ─────────────────────────────────────────────────────────────────

    return (
        <div className="flex flex-col h-full min-h-0">
            {/* ── Page header ── */}
            <div className="px-6 pt-6 pb-5 border-b border-border shrink-0">
                {/* Breadcrumb */}
                <div className="flex items-center gap-1.5 text-xs font-heading font-bold uppercase tracking-wider text-text-muted mb-4">
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
                        <Badge variant={nodeStatusVariant(node.status, node.health)}>{nodeStatusLabel(node.status, node.health)}</Badge>
                    </div>

                    {canManage && (
                        <div className="flex items-center gap-2 shrink-0 flex-wrap">
                            {allowedNodeActions(node, servers.length).map((action) => {
                                const {icon, label, variant} = NODE_HEADER_ACTION_BUTTONS[action];
                                return (
                                    <HeaderActionButton
                                        key={action}
                                        icon={icon}
                                        label={label}
                                        loading={nodePendingFor(node.id) === action}
                                        onClick={() => {
                                            switch (action) {
                                                case "trust": void trust(node.id); break;
                                                case "reject": reject(node.id); break;
                                                case "rotate": rotate(node.id); break;
                                                case "shutdown": shutdown(node); break;
                                                case "decommission": decommission(node); break;
                                            }
                                        }}
                                        variant={variant}
                                    />
                                );
                            })}
                        </div>
                    )}
                </div>

                {/* Meta */}
                <p className="mt-2 font-mono text-xs text-text-muted">
                    {node.hostname} · {node.private_ip} · {node.agent_version ?? "unknown agent"}
                </p>
            </div>

            {/* Error banner */}
            {(actionError || serverActionError) && (
                <div className="mx-6 mt-4 flex items-center justify-between bg-error/10 border border-error/30 text-error rounded px-3 py-2 text-xs">
                    <span>{actionError ?? serverActionError}</span>
                    <button onClick={() => { setActionError(null); setServerActionError(null); }} className="ml-4 hover:opacity-70">
                        <X size={13}/>
                    </button>
                </div>
            )}

            {/* Tab bar */}
            <Tabs value={activeTab} onValueChange={(value) => setActiveTab(value as Tab)} className="flex-1 min-h-0 overflow-hidden">
                <div className="scrollbar-none border-b border-border bg-surface overflow-x-auto pb-[7px] shrink-0">
                    <TabsList variant="line" className="h-auto w-full justify-start rounded-none bg-transparent px-6 py-0">
                        {TABS.map((tab) => (
                            <TabsTrigger
                                key={tab}
                                value={tab}
                                className="shrink-0 rounded-none border-none px-4 py-3 text-xs font-heading font-bold uppercase tracking-widest text-text-dim data-active:bg-transparent data-active:text-accent data-active:shadow-none after:bg-accent hover:text-text-primary"
                            >
                                {tab}
                                {tab === "Servers" && servers.length > 0 && (
                                    <span className="ml-1.5 font-mono text-xs text-text-muted">{servers.length}</span>
                                )}
                            </TabsTrigger>
                        ))}
                    </TabsList>
                </div>

                <TabsContent value="Overview" className="overflow-auto">
                    <OverviewTab node={node} servers={servers} onSaved={fetchNode} canEdit={canManage}/>
                </TabsContent>
                <TabsContent value="Servers" className="overflow-auto">
                    <div className="px-6 py-4">
                        <ServerList
                            servers={servers}
                            nodes={[node]}
                            showNodeColumn={false}
                            empty="No servers assigned to this node"
                            onRowClick={(server) => router.push(`/servers/${server.id}`)}
                            renderActions={(server) => (
                                <ServerActions
                                    actions={allowedServerActions(server)}
                                    pending={serverPendingFor(server.id)}
                                    onAction={(action) => runServerAction(server.id, action)}
                                    onDelete={() => removeServer(server)}
                                    onDuplicate={() => duplicateServer(server)}
                                />
                            )}
                            actionsHeader="Actions"
                        />
                    </div>
                </TabsContent>
                <TabsContent value="Metrics" className="overflow-auto"><MetricsTab nodeId={id}/></TabsContent>
            </Tabs>

            {/* Modals */}
            {tokenKey && (
                <TokenModal nodeKey={tokenKey} onClose={() => setTokenKey(null)}/>
            )}
            {nodeActionsDialog}
            {serverActionsDialog}
        </div>
    );
}
