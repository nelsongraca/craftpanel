"use client";

import {useCallback, useEffect, useState} from "react";
import {useParams, useRouter} from "next/navigation";
import Link from "next/link";
import {ChevronRight, MoreHorizontal, Play, RotateCcw, Shuffle, Square, Trash2, X,} from "lucide-react";
import {deleteServer, getNetwork, getNode, getServer, restartServer, startServer, stopServer} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import type {Network, Node, Server} from "@/lib/types";
import {useWs} from "@/lib/ws-context";
import {serverStatusClass, serverStatusLabel} from "@/lib/status";
import {ConsoleTab} from "./console-tab";
import {FilesTab} from "./files-tab";
import {BackupsTab} from "./backups-tab";
import {ModsTab} from "./mods-tab";
import {ConfigTab} from "./config-tab";
import {MigrationTab} from "./migration-tab";
import {ConfirmDialog} from "@/components/ui/confirm-dialog";
import {HeaderActionButton} from "@/components/servers/header-action-button";
import {OverviewTab} from "@/components/servers/overview-tab";

type LiveMetrics = { cpuPercent: number; ramUsedMb: number; netInBytes: number; netOutBytes: number };
type LivePlayers = { count: number; list: string[] };

const TABS = ["Overview", "Console", "Files", "Mods", "Backups", "Configuration", "Migration"] as const;
type Tab = (typeof TABS)[number];

function ComingSoon({tab}: { tab: string }) {
    return (
        <div className="px-6 py-10">
            <div className="border-2 border-dashed border-border rounded-md py-10 text-center text-text-muted text-[13px]">
                {tab} \u2014 coming soon
            </div>
        </div>
    );
}

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
    const [actionError, setActionError] = useState<string | null>(null);
    const [pending, setPending] = useState<string | null>(null);
    const [menuOpen, setMenuOpen] = useState(false);
    const [confirmDialog, setConfirmDialog] = useState<{
        title: string;
        description: string;
        destructive?: boolean;
        onConfirm: () => void;
    } | null>(null);

    // Live WS data
    const [liveMetrics, setLiveMetrics] = useState<LiveMetrics | null>(null);
    const [livePlayers, setLivePlayers] = useState<LivePlayers | null>(null);

    // Bumped to force-open the General Settings edit form from another tab (e.g. Configuration).
    const [generalOpenSignal, setGeneralOpenSignal] = useState<number | undefined>(undefined);

    // Data fetching

    const fetchServer = useCallback(async () => {
        const {data, response} = await getServer({path: {id}});
        if (response?.status === 404) setNotFound(true);
        if (data) setServer(data);
        setLoading(false);
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
            if (mine?.metrics) {
                setLiveMetrics({
                    cpuPercent: mine.metrics.cpu_percent,
                    ramUsedMb: mine.metrics.ram_used_mb,
                    netInBytes: mine.metrics.net_in_bytes,
                    netOutBytes: mine.metrics.net_out_bytes,
                });
            }
        });
        const unsubMetrics = subscribe("server.metrics", (payload) => {
            if (payload.server_id !== id) return;
            setLiveMetrics({
                cpuPercent: payload.cpu_percent,
                ramUsedMb: payload.ram_used_mb,
                netInBytes: payload.net_in_bytes,
                netOutBytes: payload.net_out_bytes,
            });
        });
        const unsubStatus = subscribe("server.status", (payload) => {
            if (payload.server_id !== id) return;
            setServer((prev) => prev ? {...prev, status: payload.status} : prev);
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

    const ACTION_FNS = {
        start: startServer,
        stop: stopServer,
        restart: restartServer,
    } as const;

    async function doAction(action: "start" | "stop" | "restart") {
        setPending(action);
        setActionError(null);
        const {error} = await ACTION_FNS[action]({path: {id}});
        if (error) {
            setActionError(error.message ?? `Failed to ${action} server`);
        } else {
            await fetchServer();
        }
        setPending(null);
    }

    function doDelete() {
        if (!server) return;
        setConfirmDialog({
            title: "Delete Server?",
            description: `Delete "${server.display_name}"? This cannot be undone.`,
            destructive: true,
            onConfirm: async () => {
                setActionError(null);
                const {error: deleteErr} = await deleteServer({path: {id}});
                if (deleteErr) {
                    setActionError(deleteErr.message ?? "Failed to delete server");
                } else {
                    router.push("/servers");
                }
            },
        });
    }

    // Loading / not-found guards

    if (loading) {
        return (
            <div className="px-6 pt-6 space-y-4">
                <div className="h-4 w-40 bg-surface rounded animate-pulse"/>
                <div className="h-8 w-64 bg-surface rounded animate-pulse"/>
                <div className="h-4 w-48 bg-surface rounded animate-pulse"/>
            </div>
        );
    }

    if (notFound || !server) {
        return (
            <div className="px-6 py-10 text-center text-text-muted text-[13px]">
                Server not found.{" "}
                <Link href="/servers" className="text-accent hover:underline">
                    Back to servers
                </Link>
            </div>
        );
    }

    // Render

    const sStatus = server.status;
    const isProxy = ["VELOCITY", "BUNGEECORD", "WATERFALL"].includes(server.server_type);
    const isModServerType = ["FABRIC", "FORGE", "NEOFORGE", "QUILT"].includes(server.server_type);

    return (
        <div>
            {/* Page header */}
            <div className="px-6 pt-6 pb-5 border-b border-border">

                {/* Breadcrumb */}
                <div className="flex items-center gap-1.5 text-[12px] font-heading font-bold uppercase tracking-wider text-text-muted mb-4">
                    <Link href="/servers" className="hover:text-text-primary transition-colors">
                        Servers
                    </Link>
                    {network && (
                        <>
                            <ChevronRight size={11} strokeWidth={2.5}/>
                            <Link
                                href="/networks"
                                className="hover:text-text-primary transition-colors"
                            >
                                {network.name}
                            </Link>
                        </>
                    )}
                    <ChevronRight size={11} strokeWidth={2.5}/>
                    <span className="text-text-dim">{server.display_name}</span>
                </div>

                {/* Name row + action buttons */}
                <div className="flex items-start justify-between gap-4">
                    <div className="flex items-center gap-3 flex-wrap">
                        <h1 className="text-[22px] font-heading font-bold uppercase tracking-wide text-text-primary leading-none">
                            {server.display_name}
                        </h1>
                        <span
                            className={`text-[12px] font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${serverStatusClass(sStatus)}`}
                        >
              {serverStatusLabel(sStatus)}
            </span>
                    </div>

                    {/* Action buttons + menu */}
                    <div className="flex items-center gap-2 shrink-0">
                        {sStatus === "STOPPED" && hasPermission(permissions, "server.start") && (
                            <HeaderActionButton
                                icon={<Play size={12} strokeWidth={2.5}/>}
                                label="Start"
                                loading={pending === "start"}
                                onClick={() => doAction("start")}
                                variant="green"
                            />
                        )}
                        {(sStatus === "HEALTHY" || sStatus === "STARTING" || sStatus === "UNHEALTHY") && hasPermission(permissions, "server.stop") && (
                            <HeaderActionButton
                                icon={<Square size={12} strokeWidth={2.5}/>}
                                label="Stop"
                                loading={pending === "stop"}
                                onClick={() => doAction("stop")}
                                variant="red"
                            />
                        )}
                        {sStatus === "HEALTHY" && hasPermission(permissions, "server.restart") && (
                            <HeaderActionButton
                                icon={<RotateCcw size={12} strokeWidth={2.5}/>}
                                label="Restart"
                                loading={pending === "restart"}
                                onClick={() => doAction("restart")}
                                variant="yellow"
                            />
                        )}
                        {sStatus === "STOPPED" && hasPermission(permissions, "server.delete") && (
                            <HeaderActionButton
                                icon={<Trash2 size={12} strokeWidth={2.5}/>}
                                label="Delete"
                                onClick={doDelete}
                                variant="red"
                            />
                        )}

                        {/* Overflow menu */}
                        {hasPermission(permissions, "server.migrate") && (
                            <div className="relative">
                                <button
                                    onClick={(e) => {
                                        e.nativeEvent.stopImmediatePropagation();
                                        setMenuOpen((o) => !o);
                                    }}
                                    className="flex items-center justify-center w-8 h-8 rounded border border-border text-text-muted hover:text-text-primary hover:bg-surface-high transition-colors"
                                >
                                    <MoreHorizontal size={14} strokeWidth={2}/>
                                </button>

                                {menuOpen && (
                                    <div
                                        className="absolute right-0 top-full mt-1 z-50 bg-surface-higher border border-border rounded shadow-xl min-w-[160px] py-1"
                                        onClick={(e) => e.stopPropagation()}
                                    >
                                        <button
                                            onClick={() => {
                                                setMenuOpen(false);
                                                setActiveTab("Migration");
                                            }}
                                            className="flex items-center gap-2 w-full text-left px-3 py-2 text-[12px] font-heading font-bold uppercase tracking-wider text-text-primary hover:bg-surface-high transition-colors"
                                        >
                                            <Shuffle size={12} strokeWidth={2}/>
                                            Migrate
                                        </button>
                                    </div>
                                )}
                            </div>
                        )}
                    </div>
                </div>

                {/* Type / config badges */}
                <div className="flex items-center gap-2 mt-3 flex-wrap">
                    <span className="font-mono text-[12px] uppercase tracking-wider text-text-dim border border-border bg-surface-high px-1.5 py-0.5 rounded">
                        {server.server_type}
                    </span>
                    <span className="font-mono text-[12px] uppercase tracking-wider text-text-dim border border-border bg-surface-high px-1.5 py-0.5 rounded">
                        {server.config_mode}
                    </span>
                    {server.is_migrating && (
                        <span className="font-mono text-[12px] uppercase tracking-wider text-warning border border-warning/30 bg-warning/10 px-1.5 py-0.5 rounded">
                            \u27f3 Migrating
                        </span>
                    )}
                </div>

                {/* Hostname */}
                {server.exposed_externally && server.public_subdomain && (
                    <p className="mt-2 font-mono text-[12px] text-text-muted">
                        {server.public_subdomain}
                    </p>
                )}

                {/* Node */}
                {node && (
                    <p className="mt-1 text-[12px] font-heading text-text-muted">
                        Node:{" "}
                        <Link
                            href={`/nodes/${node.id}`}
                            className="text-text-dim hover:text-text-primary transition-colors font-mono"
                        >
                            {node.display_name}
                        </Link>
                    </p>
                )}
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

            {/* Restart required banner */}
            {server.needs_recreate && (
                <div className="mx-6 mt-4 flex items-center justify-between bg-warning/10 border border-warning/30 text-warning rounded px-3 py-2 text-[12px]">
                    <span>Settings saved. Restart the server for changes to take effect.</span>
                    {(server.status === "HEALTHY") && hasPermission(permissions, "server.restart") && (
                        <button
                            onClick={() => void doAction("restart")}
                            className="ml-4 shrink-0 text-[12px] font-heading font-bold uppercase tracking-wider underline hover:no-underline"
                        >
                            Restart Now
                        </button>
                    )}
                </div>
            )}

            {/* Tab bar */}
            <div className="flex items-end px-6 border-b border-border bg-surface">
                {TABS.filter((tab) => !(isProxy && tab === "Migration")).map((tab) => (
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
                        {tab === "Mods" && !isModServerType ? "Plugins" : tab}
                    </button>
                ))}
            </div>

            {/* Tab content */}
            {activeTab === "Console" ? (
                <ConsoleTab serverId={server.id} serverStatus={server.status}/>
            ) : activeTab === "Files" ? (
                <FilesTab serverId={server.id}/>
            ) : activeTab === "Backups" ? (
                <BackupsTab serverId={server.id}/>
            ) : activeTab === "Mods" ? (
                <ModsTab serverId={server.id} serverType={server.server_type} mcVersion={server.mc_version} onModsChanged={() => void fetchServer()}/>
            ) : activeTab === "Configuration" ? (
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
            ) : activeTab === "Migration" ? (
                <div className="px-6 py-4">
                    <MigrationTab
                        serverId={server.id}
                        nodeId={server.node_id}
                        canMigrate={hasPermission(permissions, "server.migrate")}
                    />
                </div>
            ) : activeTab !== "Overview" ? (
                <ComingSoon tab={activeTab}/>
            ) : (
                <OverviewTab
                    server={server}
                    node={node}
                    network={network}
                    permissions={permissions}
                    liveMetrics={liveMetrics}
                    livePlayers={livePlayers}
                    forceOpenGeneralSignal={generalOpenSignal}
                    onSaved={() => void fetchServer()}
                />
            )}
            <ConfirmDialog
                open={confirmDialog !== null}
                onOpenChange={(open) => !open && setConfirmDialog(null)}
                title={confirmDialog?.title ?? ""}
                description={confirmDialog?.description ?? ""}
                destructive={confirmDialog?.destructive}
                onConfirm={confirmDialog?.onConfirm ?? (() => {
                })}
            />
        </div>
    );
}
