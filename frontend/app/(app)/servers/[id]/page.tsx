"use client";

import {useCallback, useEffect, useState} from "react";
import dynamic from "next/dynamic";
import {useParams, useRouter} from "next/navigation";
import Link from "next/link";
import {
    ChevronRight,
    Copy,
    Download,
    MoreHorizontal,
    Play,
    RotateCcw,
    Shuffle,
    Skull,
    Square,
    Trash2,
    X,
} from "lucide-react";
import {exportServer, getNetwork, getNode, getServer, getServerMetrics} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import {hasPermission, scopedPermissions} from "@/lib/permissions";
import type {Network, Node, Server} from "@/lib/types";
import {useWs} from "@/lib/ws-context";
import {serverExpired, serverStatusLabel, serverStatusVariant} from "@/lib/status";
import {Badge} from "@/components/ui/badge";
import {Skeleton} from "@/components/ui/skeleton";
import {Empty, EmptyDescription} from "@/components/ui/empty";
import {HeaderActionButton} from "@/components/servers/header-action-button";
import {type ServerActionKind, useServerActions} from "@/components/servers/server-actions";
import {OverviewTab} from "@/components/servers/overview-tab";
import {Tabs, TabsContent, TabsList, TabsTrigger} from "@/components/ui/tabs";
import {isCustomType, isModLoaderType, isPicolimboType, isProxyType} from "@/lib/server-types";

type LiveMetrics = {
    cpuPercent: number;
    ramUsedMb: number;
    netInBytes: number;
    netOutBytes: number;
    heapUsedBytes?: number | null;
    heapMaxBytes?: number | null;
    nonHeapUsedBytes?: number | null;
};
type LivePlayers = {count: number; list: string[]};

const TABS = ["Overview", "Console", "Files", "Mods", "Backups", "Configuration", "Ports", "Migration"] as const;
type Tab = (typeof TABS)[number];

const HEADER_ACTION_BUTTONS = {
    start: {icon: <Play size={12} strokeWidth={2.5} />, label: "Start", variant: "green"},
    stop: {icon: <Square size={12} strokeWidth={2.5} />, label: "Stop", variant: "red"},
    forceStop: {icon: <Skull size={12} strokeWidth={2.5} />, label: "Force Stop", variant: "red"},
    restart: {icon: <RotateCcw size={12} strokeWidth={2.5} />, label: "Restart", variant: "yellow"},
    delete: {icon: <Trash2 size={12} strokeWidth={2.5} />, label: "Delete", variant: "red"},
} as const;

// Lazily load each tab so visiting one tab does not bundle/evaluate the rest.
const ConsoleTab = dynamic(() => import("./console-tab").then((m) => m.ConsoleTab), {ssr: false});
const FilesTab = dynamic(() => import("./files-tab").then((m) => m.FilesTab), {ssr: false});
const BackupsTab = dynamic(() => import("./backups-tab").then((m) => m.BackupsTab), {ssr: false});
const ModsTab = dynamic(() => import("./mods-tab").then((m) => m.ModsTab), {ssr: false});
const ConfigTab = dynamic(() => import("./config-tab").then((m) => m.ConfigTab), {ssr: false});
const PortsTab = dynamic(() => import("./ports-tab").then((m) => m.PortsTab), {ssr: false});
const MigrationTab = dynamic(() => import("./migration-tab").then((m) => m.MigrationTab), {ssr: false});

export default function ServerDetailPage() {
    const params = useParams();
    const id = params.id as string;
    const router = useRouter();
    const {user} = useAuth();
    const permissions = user?.permissions ?? [];
    const {subscribe} = useWs();
    const [server, setServer] = useState<Server | null>(null);
    const [node, setNode] = useState<Node | null>(null);
    const [network, setNetwork] = useState<Network | null>(null);
    const [loading, setLoading] = useState(true);
    const [notFound, setNotFound] = useState(false);
    const [activeTab, setActiveTab] = useState<Tab>("Overview");
    const [menuOpen, setMenuOpen] = useState(false);

    // Live WS data
    const [liveMetrics, setLiveMetrics] = useState<LiveMetrics | null>(null);
    const [livePlayers, setLivePlayers] = useState<LivePlayers | null>(null);

    // Bumped to force-open the General Settings edit form from another tab (e.g. Configuration).
    const [generalOpenSignal, setGeneralOpenSignal] = useState<number | undefined>(undefined);

    // Data fetching

    const fetchServer = useCallback(async () => {
        const {data, response} = await getServer({path: {id}});
        if (response?.status === 404) setNotFound(true);
        if (data) {
            setServer(data);
            // Seed players from the persisted last-known values so the count renders on first
            // paint instead of waiting for the next WS push.
            if (data.last_player_count != null) {
                setLivePlayers({
                    count: data.last_player_count,
                    list: data.last_player_names ?? [],
                });
            }
        }
        setLoading(false);
    }, [id]);

    // Seed metrics from the persisted series so RAM/CPU/network render immediately; WS pushes
    // replace these as fresh samples arrive.
    useEffect(() => {
        const to = new Date();
        const from = new Date(to.getTime() - 10 * 60_000);
        getServerMetrics({path: {id}, query: {from: from.toISOString(), to: to.toISOString()}}).then(({data}) => {
            if (!data) return;
            const last = (points: {t: string; v: number}[]) => points.at(-1)?.v;
            const cpu = last(data.series.cpu_percent);
            const ram = last(data.series.ram_used_mb);
            const netIn = last(data.series.net_in_bytes);
            const netOut = last(data.series.net_out_bytes);
            const heapUsed = last(data.series.heap_used_bytes ?? []);
            const heapMax = last(data.series.heap_max_bytes ?? []);
            const nonHeap = last(data.series.non_heap_used_bytes ?? []);
            if (cpu == null && ram == null && netIn == null && netOut == null) return;
            setLiveMetrics({
                cpuPercent: cpu ?? 0,
                ramUsedMb: ram ?? 0,
                netInBytes: netIn ?? 0,
                netOutBytes: netOut ?? 0,
                heapUsedBytes: heapUsed ?? null,
                heapMaxBytes: heapMax ?? null,
                nonHeapUsedBytes: nonHeap ?? null,
            });
        });
    }, [id]);

    useEffect(() => {
        void fetchServer();
        const timer = setInterval(fetchServer, 30_000);
        return () => clearInterval(timer);
    }, [fetchServer]);

    const nodeId = server?.node_id;
    const networkId = server?.network_id;

    useEffect(() => {
        if (!nodeId) return;
        getNode({path: {id: nodeId}}).then(({data}) => {
            if (data) setNode(data);
        });
    }, [nodeId]);

    useEffect(() => {
        if (!networkId) return;
        getNetwork({path: {id: networkId}}).then(({data}) => {
            if (data) setNetwork(data);
        });
    }, [networkId]);

    useEffect(() => {
        const close = () => setMenuOpen(false);
        document.addEventListener("click", close);
        return () => document.removeEventListener("click", close);
    }, []);

    // Live WS subscriptions
    useEffect(() => {
        const unsubSnapshot = subscribe("snapshot", (payload) => {
            const mine = payload.servers?.find((s) => s.id === id);
            const metrics = mine?.metrics;
            if (metrics) {
                // JVM heap is sampled on a slower cadence than container metrics, so a sample
                // without heap is not "no data" — keep the last known value so the card does not
                // flicker between samples. Cleared explicitly on STOPPED (see status handler).
                setLiveMetrics((prev) => ({
                    cpuPercent: metrics.cpu_percent,
                    ramUsedMb: metrics.ram_used_mb,
                    netInBytes: metrics.net_in_bytes,
                    netOutBytes: metrics.net_out_bytes,
                    heapUsedBytes: metrics.heap_used_bytes ?? prev?.heapUsedBytes ?? null,
                    heapMaxBytes: metrics.heap_max_bytes ?? prev?.heapMaxBytes ?? null,
                    nonHeapUsedBytes: metrics.non_heap_used_bytes ?? prev?.nonHeapUsedBytes ?? null,
                }));
            }
        });
        const unsubMetrics = subscribe("server.metrics", (payload) => {
            if (payload.server_id !== id) return;
            // See snapshot handler: retain the last heap sample on ticks that carry none.
            setLiveMetrics((prev) => ({
                cpuPercent: payload.cpu_percent,
                ramUsedMb: payload.ram_used_mb,
                netInBytes: payload.net_in_bytes,
                netOutBytes: payload.net_out_bytes,
                heapUsedBytes: payload.heap_used_bytes ?? prev?.heapUsedBytes ?? null,
                heapMaxBytes: payload.heap_max_bytes ?? prev?.heapMaxBytes ?? null,
                nonHeapUsedBytes: payload.non_heap_used_bytes ?? prev?.nonHeapUsedBytes ?? null,
            }));
        });
        const unsubStatus = subscribe("server.status", (payload) => {
            if (payload.server_id !== id) return;
            setServer((prev) => (prev ? {...prev, status: payload.status} : prev));
            if (payload.status === "STOPPED") {
                setLiveMetrics(null);
                setLivePlayers(null);
            }
        });
        const unsubPlayers = subscribe("server.players", (payload) => {
            if (payload.server_id !== id) return;
            setLivePlayers({
                count: payload.player_count,
                list: payload.player_list,
            });
        });
        return () => {
            unsubSnapshot();
            unsubMetrics();
            unsubStatus();
            unsubPlayers();
        };
    }, [subscribe, id]);

    // Actions

    const {allowedActions, run, remove, pendingFor, actionError, setActionError, dialog} = useServerActions({
        permissions,
        serverPermissionsMap: user?.server_permissions ?? {},
        onChanged: () => void fetchServer(),
        onDeleted: () => router.push("/servers"),
    });

    async function doExport() {
        if (!server) return;
        setActionError(null);
        const {data, error} = await exportServer({path: {id}});
        if (error || !data) {
            setActionError(error?.message ?? "Failed to export server");
            return;
        }
        const blob = new Blob([JSON.stringify(data, null, 2)], {type: "application/json"});
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = `${data.name}.craftpanel.json`;
        a.click();
        URL.revokeObjectURL(url);
    }

    // Loading / not-found guards

    if (loading) {
        return (
            <div className="space-y-4 px-6 pt-6">
                <Skeleton className="h-4 w-40 bg-surface" />
                <Skeleton className="h-8 w-64 bg-surface" />
                <Skeleton className="h-4 w-48 bg-surface" />
            </div>
        );
    }

    if (notFound || !server) {
        return (
            <Empty className="min-h-[200px]">
                <EmptyDescription>
                    Server not found.{" "}
                    <Link href="/servers" className="text-accent hover:underline">
                        Back to servers
                    </Link>
                </EmptyDescription>
            </Empty>
        );
    }

    // Render

    const sStatus = server.status;
    const isProxy = isProxyType(server.server_type);
    const isCustom = isCustomType(server.server_type);
    const isPicolimbo = isPicolimboType(server.server_type);
    const isModServerType = isModLoaderType(server.server_type);
    const serverPerms = scopedPermissions(permissions, user?.server_permissions ?? {}, server.id);
    const expired = serverExpired(server.expires_at);

    return (
        <div className="flex h-full min-h-0 flex-col">
            {/* Page header */}
            <div className="shrink-0 border-b border-border px-6 pt-6 pb-5">
                {/* Breadcrumb */}
                <div className="mb-4 flex items-center gap-1.5 font-heading text-xs font-bold tracking-wider text-text-muted uppercase">
                    <Link href="/servers" className="transition-colors hover:text-text-primary">
                        Servers
                    </Link>
                    {network && (
                        <>
                            <ChevronRight size={11} strokeWidth={2.5} />
                            <Link
                                href={`/servers?network=${network.id}`}
                                className="transition-colors hover:text-text-primary"
                            >
                                {network.name}
                            </Link>
                        </>
                    )}
                    <ChevronRight size={11} strokeWidth={2.5} />
                    <span className="text-text-dim">{server.display_name}</span>
                </div>

                {/* Name row + action buttons */}
                <div className="flex items-start justify-between gap-4">
                    <div className="flex flex-wrap items-center gap-3">
                        <h1 className="font-heading text-[22px] leading-none font-bold tracking-wide text-text-primary uppercase">
                            {server.display_name}
                        </h1>
                        <Badge variant={serverStatusVariant(sStatus)}>{serverStatusLabel(sStatus)}</Badge>
                        {server.disabled && <Badge variant="destructive">Disabled</Badge>}
                        {!server.disabled && expired && <Badge variant="destructive">Expired</Badge>}
                    </div>

                    {/* Action buttons + menu */}
                    <div className="flex shrink-0 items-center gap-2">
                        {allowedActions(server)
                            .filter(
                                (action): action is Exclude<ServerActionKind, "duplicate"> => action !== "duplicate",
                            )
                            .map((action) => {
                                const {icon, label, variant} = HEADER_ACTION_BUTTONS[action];
                                return (
                                    <HeaderActionButton
                                        key={action}
                                        icon={icon}
                                        label={label}
                                        loading={pendingFor(server.id) === action}
                                        onClick={() => (action === "delete" ? remove(server) : run(server.id, action))}
                                        variant={variant}
                                    />
                                );
                            })}

                        {/* Overflow menu */}
                        {(hasPermission(serverPerms, "server.migrate") ||
                            hasPermission(serverPerms, "server.export") ||
                            hasPermission(serverPerms, "server.create")) && (
                            <div className="relative">
                                <button
                                    onClick={(e) => {
                                        e.nativeEvent.stopImmediatePropagation();
                                        setMenuOpen((o) => !o);
                                    }}
                                    className="flex h-8 w-8 items-center justify-center rounded border border-border text-text-muted transition-colors hover:bg-surface-high hover:text-text-primary"
                                >
                                    <MoreHorizontal size={14} strokeWidth={2} />
                                </button>

                                {menuOpen && (
                                    <div
                                        className="absolute top-full right-0 z-50 mt-1 min-w-[160px] rounded border border-border bg-surface-higher py-1 shadow-xl"
                                        onClick={(e) => e.stopPropagation()}
                                    >
                                        {hasPermission(serverPerms, "server.migrate") && (
                                            <button
                                                onClick={() => {
                                                    setMenuOpen(false);
                                                    setActiveTab("Migration");
                                                }}
                                                className="flex w-full items-center gap-2 px-3 py-2 text-left font-heading text-xs font-bold tracking-wider text-text-primary uppercase transition-colors hover:bg-surface-high"
                                            >
                                                <Shuffle size={12} strokeWidth={2} />
                                                Migrate
                                            </button>
                                        )}
                                        {hasPermission(serverPerms, "server.create") && (
                                            <Link
                                                href={`/servers/new?clone=${server.id}`}
                                                onClick={() => setMenuOpen(false)}
                                                className="flex w-full items-center gap-2 px-3 py-2 text-left font-heading text-xs font-bold tracking-wider text-text-primary uppercase transition-colors hover:bg-surface-high"
                                            >
                                                <Copy size={12} strokeWidth={2} />
                                                Clone Server
                                            </Link>
                                        )}
                                        {hasPermission(serverPerms, "server.export") && (
                                            <button
                                                onClick={() => {
                                                    setMenuOpen(false);
                                                    void doExport();
                                                }}
                                                className="flex w-full items-center gap-2 px-3 py-2 text-left font-heading text-xs font-bold tracking-wider text-text-primary uppercase transition-colors hover:bg-surface-high"
                                            >
                                                <Download size={12} strokeWidth={2} />
                                                Export
                                            </button>
                                        )}
                                    </div>
                                )}
                            </div>
                        )}
                    </div>
                </div>

                {/* Type / config badges */}
                <div className="mt-3 flex flex-wrap items-center gap-2">
                    <span className="rounded border border-border bg-surface-high px-1.5 py-0.5 font-mono text-xs tracking-wider text-text-dim uppercase">
                        {server.server_type}
                    </span>
                    <span className="rounded border border-border bg-surface-high px-1.5 py-0.5 font-mono text-xs tracking-wider text-text-dim uppercase">
                        {server.config_mode}
                    </span>
                    {server.is_migrating && (
                        <span className="rounded border border-warning/30 bg-warning/10 px-1.5 py-0.5 font-mono text-xs tracking-wider text-warning uppercase">
                            \u27f3 Migrating
                        </span>
                    )}
                </div>

                {/* Hostname */}
                {server.canonical_hostname && (
                    <p className="mt-2 font-mono text-xs text-text-muted">{server.canonical_hostname}</p>
                )}

                {/* Node */}
                {node && (
                    <p className="mt-1 font-heading text-xs text-text-muted">
                        Node:{" "}
                        <Link
                            href={`/nodes/${node.id}`}
                            className="font-mono text-text-dim transition-colors hover:text-text-primary"
                        >
                            {node.display_name}
                        </Link>
                    </p>
                )}
            </div>

            {/* Restart required banner */}
            {server.restart_pending && sStatus !== "STOPPED" && (
                <div className="mx-6 mt-4 flex items-center justify-between rounded border border-warning/30 bg-warning/10 px-3 py-2 text-xs text-warning">
                    <span>Settings saved. Restart the server for changes to take effect.</span>
                    {allowedActions(server).includes("restart") && (
                        <button
                            onClick={() => void run(server.id, "restart")}
                            className="ml-4 shrink-0 font-heading text-xs font-bold tracking-wider uppercase underline hover:no-underline"
                        >
                            Restart Now
                        </button>
                    )}
                </div>
            )}

            {/* Error banner */}
            {actionError && (
                <div className="mx-6 mt-4 flex items-center justify-between rounded border border-error/30 bg-error/10 px-3 py-2 text-xs text-error">
                    <span>{actionError}</span>
                    <button onClick={() => setActionError(null)} className="ml-4 hover:opacity-70">
                        <X size={13} />
                    </button>
                </div>
            )}

            {/* Tab bar */}
            <Tabs
                value={activeTab}
                onValueChange={(value) => setActiveTab(value as Tab)}
                className="min-h-0 flex-1 overflow-hidden"
            >
                <div className="shrink-0 scrollbar-none overflow-x-auto border-b border-border bg-surface pb-[7px]">
                    <TabsList
                        variant="line"
                        className="h-auto w-full justify-start rounded-none bg-transparent px-6 py-0"
                    >
                        {TABS.filter(
                            (tab) =>
                                !(isProxy && tab === "Migration") && !((isCustom || isPicolimbo) && tab === "Mods"),
                        ).map((tab) => (
                            <TabsTrigger
                                key={tab}
                                value={tab}
                                className="shrink-0 rounded-none border-none px-4 py-3 font-heading text-xs font-bold tracking-widest text-text-dim uppercase after:bg-accent hover:text-text-primary data-active:bg-transparent data-active:text-accent data-active:shadow-none"
                            >
                                {tab === "Mods" && !isModServerType ? "Plugins" : tab}
                            </TabsTrigger>
                        ))}
                    </TabsList>
                </div>

                <TabsContent value="Overview" className="overflow-auto">
                    <OverviewTab
                        server={server}
                        node={node}
                        network={network}
                        permissions={serverPerms}
                        liveMetrics={liveMetrics}
                        livePlayers={livePlayers}
                        forceOpenGeneralSignal={generalOpenSignal}
                        onSaved={() => void fetchServer()}
                    />
                </TabsContent>
                <TabsContent value="Console" className="min-h-0 flex-1 overflow-hidden">
                    <ConsoleTab serverId={server.id} serverStatus={server.status} />
                </TabsContent>
                <TabsContent value="Files" className="min-h-0 flex-1 overflow-hidden">
                    <FilesTab serverId={server.id} />
                </TabsContent>
                <TabsContent value="Backups" className="overflow-auto">
                    <BackupsTab serverId={server.id} />
                </TabsContent>
                {!isCustom && !isPicolimbo && (
                    <TabsContent value="Mods" className="overflow-auto">
                        <ModsTab
                            serverId={server.id}
                            serverType={server.server_type}
                            mcVersion={server.mc_version}
                            onModsChanged={() => void fetchServer()}
                        />
                    </TabsContent>
                )}
                <TabsContent value="Configuration" className="overflow-auto">
                    <ConfigTab
                        serverId={server.id}
                        serverType={server.server_type}
                        networkId={server.network_id ?? null}
                        configMode={server.config_mode ?? "MANAGED"}
                        stopCommand={server.stop_command ?? "stop"}
                        onOpenGeneralSettings={() => {
                            setActiveTab("Overview");
                            setGeneralOpenSignal((n) => (n ?? 0) + 1);
                        }}
                    />
                </TabsContent>
                <TabsContent value="Ports" className="overflow-auto">
                    <PortsTab
                        serverId={server.id}
                        serverType={server.server_type}
                        currentContainerPort={server.container_listen_port}
                        currentProtocol={server.container_protocol}
                    />
                </TabsContent>
                {!isProxy && (
                    <TabsContent value="Migration" className="overflow-auto">
                        <div className="px-6 py-6">
                            <MigrationTab
                                serverId={server.id}
                                nodeId={server.node_id}
                                canMigrate={hasPermission(serverPerms, "server.migrate")}
                            />
                        </div>
                    </TabsContent>
                )}
            </Tabs>
            {dialog}
        </div>
    );
}
