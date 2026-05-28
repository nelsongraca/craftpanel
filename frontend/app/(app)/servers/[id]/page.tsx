"use client";

import {useCallback, useEffect, useState} from "react";
import {useParams, useRouter} from "next/navigation";
import Link from "next/link";
import {ArrowUpCircle, ChevronRight, MoreHorizontal, Play, RotateCcw, Shuffle, Square, X,} from "lucide-react";
import {deleteServer, getNetwork, getNode, getServer, listNetworks, restartServer, startServer, stopServer, updateServer, updateServerResources, upgradeServer} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import type {Network, Node, Server} from "@/lib/types";
import {useWs} from "@/lib/ws-context";
import {ConsoleTab} from "./console-tab";
import {FilesTab} from "./files-tab";
import {BackupsTab} from "./backups-tab";
import {ModsTab} from "./mods-tab";
import {ConfigTab} from "./config-tab";

// ── Mojang version manifest ───────────────────────────────────────────────────

type MojangVersion = { id: string; type: string };

async function fetchReleaseVersions(): Promise<string[]> {
    try {
        const res = await fetch("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json");
        const json = await res.json() as { versions: MojangVersion[] };
        return json.versions.filter((v) => v.type === "release").map((v) => v.id);
    } catch {
        return [];
    }
}

// ── Status helpers ────────────────────────────────────────────────────────────

type DisplayStatus = "HEALTHY" | "UNHEALTHY" | "STARTING" | "STOPPING" | "STOPPED";

function toDisplayStatus(status: string): DisplayStatus {
    return (["HEALTHY", "UNHEALTHY", "STARTING", "STOPPING", "STOPPED"].includes(status)
        ? status
        : "STOPPED") as DisplayStatus;
}

const DISPLAY_LABELS: Record<DisplayStatus, string> = {
    HEALTHY: "Healthy",
    UNHEALTHY: "Unhealthy",
    STARTING: "Starting",
    STOPPING: "Stopping",
    STOPPED: "Stopped",
};

const DISPLAY_CLASSES: Record<DisplayStatus, string> = {
    HEALTHY: "text-healthy  border border-healthy/30  bg-healthy/10",
    UNHEALTHY: "text-error    border border-error/30    bg-error/10",
    STARTING: "text-warning  border border-warning/30  bg-warning/10",
    STOPPING: "text-warning  border border-warning/30  bg-warning/10",
    STOPPED: "text-text-muted border border-border    bg-surface-high",
};

// ── Utilities ─────────────────────────────────────────────────────────────────

function timeAgo(iso: string): string {
    const secs = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
    if (secs < 60) return `${secs}s ago`;
    if (secs < 3600) return `${Math.floor(secs / 60)}m ago`;
    if (secs < 86400) return `${Math.floor(secs / 3600)}h ago`;
    return `${Math.floor(secs / 86400)}d ago`;
}

function fmtMb(mb: number): string {
    if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`;
    return `${mb} MB`;
}

function fmtBytes(bytes: number): string {
    if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(1)} GB`;
    if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`;
    if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${bytes} B`;
}

type LiveMetrics = { cpuPercent: number; ramUsedMb: number; netInBytes: number; netOutBytes: number };
type LivePlayers = { count: number; list: string[] };

// ── Sub-components ────────────────────────────────────────────────────────────

function StatCard({
                      label,
                      children,
                  }: {
    label: string;
    children: React.ReactNode;
}) {
    return (
        <div className="bg-surface border border-border rounded p-4 flex flex-col gap-2">
            <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">
                {label}
            </p>
            {children}
        </div>
    );
}

function RamBarInline({usedMb, totalMb}: { usedMb: number | null; totalMb: number }) {
    if (usedMb === null) {
        return (
            <div className="flex flex-col gap-1.5">
                <p className="font-mono text-[20px] text-text-muted leading-none">—</p>
                <p className="font-mono text-[11px] text-text-muted">— / {fmtMb(totalMb)} alloc</p>
                <div className="h-1.5 rounded-full bg-surface-higher w-full"/>
            </div>
        );
    }
    const pct = Math.min(100, (usedMb / totalMb) * 100);
    const barColor = pct > 85 ? "bg-error" : pct > 65 ? "bg-warning" : "bg-accent";
    return (
        <div className="flex flex-col gap-1.5">
            <p className="font-mono text-[20px] text-text-primary leading-none">
                {fmtMb(usedMb)}
            </p>
            <p className="font-mono text-[11px] text-text-muted">
                {fmtMb(usedMb)} / {fmtMb(totalMb)} alloc
            </p>
            <div className="h-1.5 rounded-full bg-surface-higher w-full overflow-hidden">
                <div className={`h-full rounded-full ${barColor}`} style={{width: `${pct}%`}}/>
            </div>
        </div>
    );
}

function HeaderActionButton({
                                icon,
                                label,
                                loading,
                                onClick,
                                variant,
                            }: {
    icon: React.ReactNode;
    label: string;
    loading: boolean;
    onClick: () => void;
    variant: "green" | "red" | "yellow";
}) {
    const cls = {
        green: "text-healthy  border-healthy/40  hover:bg-healthy/10",
        red: "text-error    border-error/40    hover:bg-error/10",
        yellow: "text-warning  border-warning/40  hover:bg-warning/10",
    }[variant];

    return (
        <button
            onClick={onClick}
            disabled={loading}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded border text-[11px] font-heading font-bold uppercase tracking-widest transition-colors disabled:opacity-40 ${cls}`}
        >
            {loading ? (
                <span className="w-3 h-3 border border-current border-t-transparent rounded-full animate-spin"/>
            ) : (
                icon
            )}
            {label}
        </button>
    );
}

// ── Tabs ──────────────────────────────────────────────────────────────────────

const TABS = ["Overview", "Console", "Files", "Mods", "Backups", "Configuration"] as const;
type Tab = (typeof TABS)[number];

function ComingSoon({tab}: { tab: string }) {
    return (
        <div className="px-6 py-10">
            <div className="border-2 border-dashed border-border rounded-md py-10 text-center text-text-muted text-[13px]">
                {tab} — coming soon
            </div>
        </div>
    );
}

// ── Info row helper ───────────────────────────────────────────────────────────

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

// ── Main page ─────────────────────────────────────────────────────────────────

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

    // ── Edit: general settings ─────────────────────────────────────────────────
    const [editingGeneral, setEditingGeneral] = useState(false);
    const [editDisplayName, setEditDisplayName] = useState("");
    const [editDescription, setEditDescription] = useState("");
    const [editNetworkId, setEditNetworkId] = useState("");
    const [editMcVersion, setEditMcVersion] = useState("");
    const [savingGeneral, setSavingGeneral] = useState(false);
    const [generalError, setGeneralError] = useState<string | null>(null);
    const [restartBanner, setRestartBanner] = useState(false);
    const [networks, setNetworks] = useState<Network[]>([]);
    const [mcVersions, setMcVersions] = useState<string[]>([]);

    // ── Live WS data ───────────────────────────────────────────────────────────
    const [liveMetrics, setLiveMetrics] = useState<LiveMetrics | null>(null);
    const [livePlayers, setLivePlayers] = useState<LivePlayers | null>(null);

    // ── Edit: resources ────────────────────────────────────────────────────────
    const [editingResources, setEditingResources] = useState(false);
    const [editRamMb, setEditRamMb] = useState(0);
    const [editCpuShares, setEditCpuShares] = useState(0);
    const [editItzgTag, setEditItzgTag] = useState("");
    const [savingResources, setSavingResources] = useState(false);
    const [resourcesError, setResourcesError] = useState<string | null>(null);

    // ── Data fetching ──────────────────────────────────────────────────────────

    const fetchServer = useCallback(async () => {
        const {data, response} = await getServer({path: {id}});
        if (response?.status === 404) {
            setNotFound(true);
            setLoading(false);
            return;
        }
        if (data) setServer(data);
        setLoading(false);
    }, [id]);

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        void fetchServer();
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
        const timer = setInterval(fetchServer, 30_000);
        return () => clearInterval(timer);
    }, [fetchServer]);

    useEffect(() => {
        const close = () => setMenuOpen(false);
        document.addEventListener("click", close);
        return () => document.removeEventListener("click", close);
    }, []);

    // ── Live WS subscriptions ──────────────────────────────────────────────────
    useEffect(() => {
        const unsubMetrics = subscribe("server.metrics", (payload) => {
            if (payload.server_id !== id) return;
            setLiveMetrics({
                cpuPercent: payload.cpu_percent as number,
                ramUsedMb: payload.ram_used_mb as number,
                netInBytes: payload.net_in_bytes as number,
                netOutBytes: payload.net_out_bytes as number,
            });
        });
        const unsubStatus = subscribe("server.status", (payload) => {
            if (payload.server_id !== id) return;
            setServer((prev) => prev ? {...prev, status: payload.status as string} : prev);
        });
        const unsubPlayers = subscribe("server.players", (payload) => {
            if (payload.server_id !== id) return;
            setLivePlayers({
                count: payload.player_count as number,
                list: payload.player_list as string[],
            });
        });
        return () => {
            unsubMetrics();
            unsubStatus();
            unsubPlayers();
        };
    }, [subscribe, id]);

    // ── Edit: general settings handlers ───────────────────────────────────────

    function openEditGeneral() {
        if (!server) return;
        setEditDisplayName(server.display_name);
        setEditDescription(server.description ?? "");
        setEditNetworkId(server.network_id ?? "");
        setEditMcVersion(server.mc_version);
        setGeneralError(null);
        setEditingGeneral(true);
        if (networks.length === 0) {
            listNetworks().then(({data}) => {
                if (data) setNetworks(data);
            });
        }
        if (mcVersions.length === 0) {
            fetchReleaseVersions().then(setMcVersions);
        }
    }

    async function saveGeneral() {
        if (!server) return;
        setSavingGeneral(true);
        setGeneralError(null);
        try {
            const mcVersionChanged = editMcVersion !== server.mc_version;
            const body: Record<string, unknown> = {};
            if (editDisplayName !== server.display_name) body.display_name = editDisplayName;
            if (editDescription !== (server.description ?? "")) body.description = editDescription || null;
            if (editNetworkId !== (server.network_id ?? "")) body.network_id = editNetworkId || null;
            if (mcVersionChanged) body.mc_version = editMcVersion;

            const {error} = await updateServer({path: {id}, body: body as Parameters<typeof updateServer>[0]["body"]});
            if (error) {
                setGeneralError(error.message ?? "Failed to save");
                return;
            }
            await fetchServer();
            setEditingGeneral(false);
            if (mcVersionChanged) setRestartBanner(true);
        } catch {
            setGeneralError("Failed to save");
        } finally {
            setSavingGeneral(false);
        }
    }

    // ── Edit: resources handlers ───────────────────────────────────────────────

    function openEditResources() {
        if (!server) return;
        setEditRamMb(server.memory_mb);
        setEditCpuShares(server.cpu_shares);
        setEditItzgTag(server.itzg_image_tag);
        setResourcesError(null);
        setEditingResources(true);
    }

    async function saveResources() {
        if (!server) return;
        setSavingResources(true);
        setResourcesError(null);
        try {
            const {error} = await updateServerResources({
                path: {id},
                body: {memory_mb: editRamMb, cpu_shares: editCpuShares, itzg_image_tag: editItzgTag || null},
            });
            if (error) {
                setResourcesError(error.message ?? "Failed to save");
                return;
            }
            await fetchServer();
            setEditingResources(false);
            setRestartBanner(true);
        } catch {
            setResourcesError("Failed to save");
        } finally {
            setSavingResources(false);
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    const ACTION_FNS = {
        start: startServer,
        stop: stopServer,
        restart: restartServer,
    } as const;

    async function doAction(action: "start" | "stop" | "restart") {
        setPending(action);
        setActionError(null);
        try {
            const {error} = await ACTION_FNS[action]({path: {id}});
            if (error) {
                setActionError(error.message ?? `Failed to ${action} server`);
            } else {
                await fetchServer();
            }
        } catch {
            setActionError(`Failed to ${action} server`);
        } finally {
            setPending(null);
        }
    }

    async function doDelete() {
        if (!server) return;
        if (!window.confirm(`Delete "${server.display_name}"? This cannot be undone.`)) return;
        setActionError(null);
        try {
            const {error} = await deleteServer({path: {id}});
            if (error) {
                setActionError(error.message ?? "Failed to delete server");
            } else {
                router.push("/servers");
            }
        } catch {
            setActionError("Failed to delete server");
        }
    }

    async function doUpgrade() {
        const tag = window.prompt(
            "Enter itzg image tag to upgrade to (e.g. latest, 2024.9.1):",
            "latest"
        );
        if (!tag?.trim()) return;
        setActionError(null);
        try {
            const {error} = await upgradeServer({path: {id}, body: {itzg_image_tag: tag.trim()}});
            if (error) {
                setActionError(error.message ?? "Failed to upgrade server");
            } else {
                await fetchServer();
            }
        } catch {
            setActionError("Failed to upgrade server");
        }
    }

    // ── Loading / not-found guards ─────────────────────────────────────────────

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

    const ds = toDisplayStatus(server.status);

    // ── Render ─────────────────────────────────────────────────────────────────

    return (
        <div>
            {/* ── Page header ── */}
            <div className="px-6 pt-6 pb-5 border-b border-border">

                {/* Breadcrumb */}
                <div className="flex items-center gap-1.5 text-[11px] font-heading font-bold uppercase tracking-wider text-text-muted mb-4">
                    <Link href="/servers" className="hover:text-text-primary transition-colors">
                        Servers
                    </Link>
                    {network && (
                        <>
                            <ChevronRight size={11} strokeWidth={2.5}/>
                            <Link
                                href={`/networks/${network.id}`}
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
                            className={`text-[11px] font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${DISPLAY_CLASSES[ds]}`}
                        >
              {DISPLAY_LABELS[ds]}
            </span>
                    </div>

                    {/* Action buttons + ··· menu */}
                    <div className="flex items-center gap-2 shrink-0">
                        {ds === "STOPPED" && hasPermission(permissions, "server.start") && (
                            <HeaderActionButton
                                icon={<Play size={12} strokeWidth={2.5}/>}
                                label="Start"
                                loading={pending === "start"}
                                onClick={() => doAction("start")}
                                variant="green"
                            />
                        )}
                        {(ds === "HEALTHY" || ds === "STARTING") && hasPermission(permissions, "server.stop") && (
                            <HeaderActionButton
                                icon={<Square size={12} strokeWidth={2.5}/>}
                                label="Stop"
                                loading={pending === "stop"}
                                onClick={() => doAction("stop")}
                                variant="red"
                            />
                        )}
                        {ds === "HEALTHY" && hasPermission(permissions, "server.restart") && (
                            <HeaderActionButton
                                icon={<RotateCcw size={12} strokeWidth={2.5}/>}
                                label="Restart"
                                loading={pending === "restart"}
                                onClick={() => doAction("restart")}
                                variant="yellow"
                            />
                        )}

                        {/* ··· overflow menu */}
                        <div className="relative">
                            <button
                                onClick={(e) => {
                                    e.stopPropagation();
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
                                    {hasPermission(permissions, "server.migrate") && (
                                        <button
                                            onClick={() => {
                                                setMenuOpen(false);
                                                window.alert("Migration UI coming in phase 3.");
                                            }}
                                            className="flex items-center gap-2 w-full text-left px-3 py-2 text-[12px] font-heading font-bold uppercase tracking-wider text-text-primary hover:bg-surface-high transition-colors"
                                        >
                                            <Shuffle size={12} strokeWidth={2}/>
                                            Migrate
                                        </button>
                                    )}
                                    {hasPermission(permissions, "server.upgrade") && (
                                        <button
                                            onClick={() => {
                                                setMenuOpen(false);
                                                doUpgrade();
                                            }}
                                            className="flex items-center gap-2 w-full text-left px-3 py-2 text-[12px] font-heading font-bold uppercase tracking-wider text-text-primary hover:bg-surface-high transition-colors"
                                        >
                                            <ArrowUpCircle size={12} strokeWidth={2}/>
                                            Upgrade
                                        </button>
                                    )}
                                    {ds === "STOPPED" && hasPermission(permissions, "server.delete") && (
                                        <button
                                            onClick={() => {
                                                setMenuOpen(false);
                                                doDelete();
                                            }}
                                            className="flex items-center gap-2 w-full text-left px-3 py-2 text-[12px] font-heading font-bold uppercase tracking-wider text-error hover:bg-surface-high transition-colors"
                                        >
                                            <X size={12} strokeWidth={2}/>
                                            Delete
                                        </button>
                                    )}
                                </div>
                            )}
                        </div>
                    </div>
                </div>

                {/* Type / config badges */}
                <div className="flex items-center gap-2 mt-3 flex-wrap">
          <span className="font-mono text-[10px] uppercase tracking-wider text-text-dim border border-border bg-surface-high px-1.5 py-0.5 rounded">
            {server.server_type}
          </span>
                    <span className="font-mono text-[10px] uppercase tracking-wider text-text-dim border border-border bg-surface-high px-1.5 py-0.5 rounded">
            {server.config_mode}
          </span>
                    {server.is_migrating && (
                        <span className="font-mono text-[10px] uppercase tracking-wider text-warning border border-warning/30 bg-warning/10 px-1.5 py-0.5 rounded">
              ⟳ Migrating
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
            {restartBanner && (
                <div className="mx-6 mt-4 flex items-center justify-between bg-warning/10 border border-warning/30 text-warning rounded px-3 py-2 text-[12px]">
                    <span>Settings saved. Restart the server for changes to take effect.</span>
                    <div className="flex items-center gap-3 ml-4 shrink-0">
                        {(server.status === "HEALTHY") && hasPermission(permissions, "server.restart") && (
                            <button
                                onClick={() => {
                                    setRestartBanner(false);
                                    void doAction("restart");
                                }}
                                className="text-[11px] font-heading font-bold uppercase tracking-wider underline hover:no-underline"
                            >
                                Restart Now
                            </button>
                        )}
                        <button onClick={() => setRestartBanner(false)} className="hover:opacity-70">
                            <X size={13}/>
                        </button>
                    </div>
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
                    </button>
                ))}
            </div>

            {/* ── Tab content ── */}
            {activeTab === "Console" ? (
                <ConsoleTab serverId={server.id} serverStatus={server.status}/>
            ) : activeTab === "Files" ? (
                <FilesTab serverId={server.id}/>
            ) : activeTab === "Backups" ? (
                <BackupsTab serverId={server.id}/>
            ) : activeTab === "Mods" ? (
                <ModsTab serverId={server.id}/>
            ) : activeTab === "Configuration" ? (
                <ConfigTab
                    serverId={server.id}
                    serverType={server.server_type}
                    networkId={server.network_id ?? null}
                />
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
                    editingGeneral={editingGeneral}
                    editDisplayName={editDisplayName}
                    editDescription={editDescription}
                    editNetworkId={editNetworkId}
                    editMcVersion={editMcVersion}
                    savingGeneral={savingGeneral}
                    generalError={generalError}
                    networks={networks}
                    mcVersions={mcVersions}
                    onOpenEditGeneral={openEditGeneral}
                    onSaveGeneral={() => void saveGeneral()}
                    onCancelGeneral={() => setEditingGeneral(false)}
                    onChangeDisplayName={setEditDisplayName}
                    onChangeDescription={setEditDescription}
                    onChangeNetworkId={setEditNetworkId}
                    onChangeMcVersion={setEditMcVersion}
                    editingResources={editingResources}
                    editRamMb={editRamMb}
                    editCpuShares={editCpuShares}
                    editItzgTag={editItzgTag}
                    savingResources={savingResources}
                    resourcesError={resourcesError}
                    onOpenEditResources={openEditResources}
                    onSaveResources={() => void saveResources()}
                    onCancelResources={() => setEditingResources(false)}
                    onChangeRamMb={setEditRamMb}
                    onChangeCpuShares={setEditCpuShares}
                    onChangeItzgTag={setEditItzgTag}
                />
            )}
        </div>
    );
}

// ── Shared field helpers (used by OverviewTab edit panels) ────────────────────

function EditInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
    return (
        <input
            {...props}
            className="w-full bg-bg border border-border rounded px-2.5 py-1.5 text-[12px] font-mono text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent transition-colors disabled:opacity-50"
        />
    );
}

function EditSelect(props: React.SelectHTMLAttributes<HTMLSelectElement>) {
    return (
        <select
            {...props}
            className="w-full bg-bg border border-border rounded px-2.5 py-1.5 text-[12px] font-mono text-text-primary focus:outline-none focus:border-accent transition-colors"
        />
    );
}

function EditTextarea(props: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
    return (
        <textarea
            {...props}
            rows={2}
            className="w-full bg-bg border border-border rounded px-2.5 py-1.5 text-[12px] font-mono text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent transition-colors resize-none"
        />
    );
}

function EditFieldRow({label, children}: { label: string; children: React.ReactNode }) {
    return (
        <div className="space-y-1">
            <p className="text-[10px] font-heading font-bold uppercase tracking-wider text-text-muted">{label}</p>
            {children}
        </div>
    );
}

function SaveCancelRow({
                           onSave,
                           onCancel,
                           saving,
                       }: {
    onSave: () => void;
    onCancel: () => void;
    saving: boolean;
}) {
    return (
        <div className="flex items-center justify-end gap-2 pt-1">
            <button
                onClick={onCancel}
                className="px-3 py-1 text-[10px] font-heading font-bold uppercase tracking-wider text-text-muted hover:text-text-primary transition-colors"
            >
                Cancel
            </button>
            <button
                onClick={onSave}
                disabled={saving}
                className="px-3 py-1 rounded bg-accent text-bg text-[10px] font-heading font-bold uppercase tracking-wider hover:bg-accent-bright transition-colors disabled:opacity-50"
            >
                {saving ? "Saving…" : "Save"}
            </button>
        </div>
    );
}

// ── Overview tab ──────────────────────────────────────────────────────────────

function OverviewTab({
                         server,
                         node,
                         network,
                         permissions,
                         liveMetrics,
                         livePlayers,
                         editingGeneral,
                         editDisplayName,
                         editDescription,
                         editNetworkId,
                         editMcVersion,
                         savingGeneral,
                         generalError,
                         networks,
                         mcVersions,
                         onOpenEditGeneral,
                         onSaveGeneral,
                         onCancelGeneral,
                         onChangeDisplayName,
                         onChangeDescription,
                         onChangeNetworkId,
                         onChangeMcVersion,
                         editingResources,
                         editRamMb,
                         editCpuShares,
                         editItzgTag,
                         savingResources,
                         resourcesError,
                         onOpenEditResources,
                         onSaveResources,
                         onCancelResources,
                         onChangeRamMb,
                         onChangeCpuShares,
                         onChangeItzgTag,
                     }: {
    server: Server;
    node: Node | null;
    network: Network | null;
    permissions: string[];
    liveMetrics: LiveMetrics | null;
    livePlayers: LivePlayers | null;
    editingGeneral: boolean;
    editDisplayName: string;
    editDescription: string;
    editNetworkId: string;
    editMcVersion: string;
    savingGeneral: boolean;
    generalError: string | null;
    networks: Network[];
    mcVersions: string[];
    onOpenEditGeneral: () => void;
    onSaveGeneral: () => void;
    onCancelGeneral: () => void;
    onChangeDisplayName: (v: string) => void;
    onChangeDescription: (v: string) => void;
    onChangeNetworkId: (v: string) => void;
    onChangeMcVersion: (v: string) => void;
    editingResources: boolean;
    editRamMb: number;
    editCpuShares: number;
    editItzgTag: string;
    savingResources: boolean;
    resourcesError: string | null;
    onOpenEditResources: () => void;
    onSaveResources: () => void;
    onCancelResources: () => void;
    onChangeRamMb: (v: number) => void;
    onChangeCpuShares: (v: number) => void;
    onChangeItzgTag: (v: string) => void;
}) {
    const ds = toDisplayStatus(server.status);
    const canConfigure = hasPermission(permissions, "server.configure");
    const canResources = hasPermission(permissions, "server.resources");
    const isProxy = ["VELOCITY", "BUNGEECORD", "WATERFALL"].includes(server.server_type);

    const cpuColor = liveMetrics && liveMetrics.cpuPercent > 85 ? "text-error"
        : liveMetrics && liveMetrics.cpuPercent > 65 ? "text-warning"
            : "text-text-primary";

    return (
        <div className="px-6 py-6 space-y-6">

            {/* Stat cards */}
            <div className="grid grid-cols-4 gap-4">

                {/* PLAYERS ONLINE */}
                <StatCard label="Players Online">
                    {livePlayers ? (
                        <>
                            <p className="font-mono text-[20px] text-text-primary leading-none">{livePlayers.count}</p>
                            <p className="font-mono text-[11px] text-text-muted">online now</p>
                        </>
                    ) : (
                        <>
                            <p className="font-mono text-[20px] text-text-muted leading-none">—</p>
                            <p className="text-[11px] text-text-muted">awaiting data</p>
                        </>
                    )}
                </StatCard>

                {/* RAM USAGE */}
                <StatCard label="RAM Usage">
                    <RamBarInline usedMb={liveMetrics?.ramUsedMb ?? null} totalMb={server.memory_mb}/>
                </StatCard>

                {/* CPU USAGE */}
                <StatCard label="CPU Usage">
                    {liveMetrics ? (
                        <>
                            <p className={`font-mono text-[20px] leading-none ${cpuColor}`}>
                                {liveMetrics.cpuPercent.toFixed(1)}%
                            </p>
                            <p className="font-mono text-[11px] text-text-muted">{server.cpu_shares} shares alloc</p>
                        </>
                    ) : (
                        <>
                            <p className="font-mono text-[20px] text-text-muted leading-none">—%</p>
                            <p className="font-mono text-[11px] text-text-muted">{server.cpu_shares} shares alloc</p>
                        </>
                    )}
                </StatCard>

                {/* STATUS */}
                <StatCard label="Status">
          <span
              className={`self-start text-[11px] font-heading font-bold uppercase tracking-wider px-2 py-0.5 rounded ${DISPLAY_CLASSES[ds]}`}
          >
            {DISPLAY_LABELS[ds]}
          </span>
                    {node?.last_seen_at && (
                        <p className="text-[11px] text-text-muted">
                            last seen {timeAgo(node.last_seen_at)}
                        </p>
                    )}
                </StatCard>
            </div>

            {/* Panels row */}
            <div className="grid grid-cols-[1fr_1fr] gap-4">

                {/* Live Metrics */}
                <div className="bg-surface border border-border rounded p-4">
                    <div className="flex items-center justify-between mb-4">
                        <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">
                            Live Metrics
                        </p>
                        {!liveMetrics && (
                            <span className="text-[10px] font-heading text-text-muted italic">awaiting data…</span>
                        )}
                    </div>
                    <div className="space-y-3">
                        {[
                            {
                                label: "CPU",
                                value: liveMetrics ? `${liveMetrics.cpuPercent.toFixed(1)}%` : "—%",
                                color: liveMetrics ? cpuColor : "text-text-muted",
                            },
                            {
                                label: "RAM",
                                value: liveMetrics
                                    ? `${fmtMb(liveMetrics.ramUsedMb)} / ${fmtMb(server.memory_mb)}`
                                    : "—",
                                color: "text-text-primary",
                            },
                            {
                                label: "Net ↓",
                                value: liveMetrics ? fmtBytes(liveMetrics.netInBytes) : "—",
                                color: "text-text-primary",
                            },
                            {
                                label: "Net ↑",
                                value: liveMetrics ? fmtBytes(liveMetrics.netOutBytes) : "—",
                                color: "text-text-primary",
                            },
                        ].map(({label, value, color}) => (
                            <div key={label} className="flex items-center justify-between">
                <span className="text-[11px] font-heading font-bold uppercase tracking-wider text-text-muted">
                  {label}
                </span>
                                <span className={`font-mono text-[12px] ${liveMetrics ? color : "text-text-muted"}`}>{value}</span>
                            </div>
                        ))}
                    </div>
                    {livePlayers && livePlayers.list.length > 0 && (
                        <div className="mt-4 pt-4 border-t border-border">
                            <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted mb-2">
                                Online Players
                            </p>
                            <div className="flex flex-wrap gap-1">
                                {livePlayers.list.map((name) => (
                                    <span key={name} className="font-mono text-[11px] text-text-dim border border-border bg-surface-high px-1.5 py-0.5 rounded">
                    {name}
                  </span>
                                ))}
                            </div>
                        </div>
                    )}
                </div>

                {/* Server info */}
                <div className="bg-surface border border-border rounded p-4">
                    <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted mb-2">
                        Server Info
                    </p>
                    <InfoRow label="Type" value={server.server_type}/>
                    <InfoRow label="Version" value={isProxy ? "—" : server.mc_version}/>
                    <InfoRow label="Config" value={server.config_mode}/>
                    <InfoRow label="Node" value={node?.display_name ?? server.node_id.slice(0, 8) + "…"}/>
                    <InfoRow label="Network" value={network?.name ?? "—"}/>
                    <InfoRow label="Port" value={server.host_port}/>
                    <InfoRow
                        label="Hostname"
                        value={
                            server.exposed_externally && server.public_subdomain
                                ? server.public_subdomain
                                : "—"
                        }
                    />
                    <InfoRow
                        label="Last seen"
                        value={node?.last_seen_at ? timeAgo(node.last_seen_at) : "—"}
                    />
                    <InfoRow label="Created" value={new Date(server.created_at).toLocaleDateString()}/>
                </div>
            </div>

            {/* ── General settings panel ── */}
            {canConfigure && (
                <div className="bg-surface border border-border rounded p-4">
                    <div className="flex items-center justify-between mb-3">
                        <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">
                            General Settings
                        </p>
                        {!editingGeneral && (
                            <button
                                onClick={onOpenEditGeneral}
                                className="text-[10px] font-heading font-bold uppercase tracking-wider text-text-muted hover:text-accent transition-colors"
                            >
                                Edit
                            </button>
                        )}
                    </div>

                    {!editingGeneral ? (
                        <div>
                            <InfoRow label="Display Name" value={server.display_name}/>
                            <InfoRow label="Description" value={server.description ?? "—"}/>
                            <InfoRow label="Network" value={network?.name ?? "—"}/>
                            {!isProxy && <InfoRow label="MC Version" value={server.mc_version}/>}
                        </div>
                    ) : (
                        <div className="space-y-3">
                            {generalError && (
                                <p className="text-[11px] text-error">{generalError}</p>
                            )}
                            <EditFieldRow label="Display Name">
                                <EditInput
                                    value={editDisplayName}
                                    onChange={(e) => onChangeDisplayName(e.target.value)}
                                    placeholder={server.display_name}
                                />
                            </EditFieldRow>
                            <EditFieldRow label="Description">
                                <EditTextarea
                                    value={editDescription}
                                    onChange={(e) => onChangeDescription(e.target.value)}
                                    placeholder="Optional description"
                                />
                            </EditFieldRow>
                            <EditFieldRow label="Network">
                                <EditSelect value={editNetworkId} onChange={(e) => onChangeNetworkId(e.target.value)}>
                                    <option value="">None</option>
                                    {networks.map((n) => (
                                        <option key={n.id} value={n.id}>{n.name}</option>
                                    ))}
                                </EditSelect>
                            </EditFieldRow>
                            {!isProxy && (
                                <EditFieldRow label="Minecraft Version">
                                    {mcVersions.length > 0 ? (
                                        <EditSelect value={editMcVersion} onChange={(e) => onChangeMcVersion(e.target.value)}>
                                            {mcVersions.map((v) => <option key={v} value={v}>{v}</option>)}
                                        </EditSelect>
                                    ) : (
                                        <EditInput
                                            value={editMcVersion}
                                            onChange={(e) => onChangeMcVersion(e.target.value)}
                                            placeholder="1.21.4"
                                        />
                                    )}
                                    <p className="text-[10px] text-text-muted mt-1">Requires restart to take effect.</p>
                                </EditFieldRow>
                            )}
                            <SaveCancelRow onSave={onSaveGeneral} onCancel={onCancelGeneral} saving={savingGeneral}/>
                        </div>
                    )}
                </div>
            )}

            {/* ── Resources panel ── */}
            {canResources && (
                <div className="bg-surface border border-border rounded p-4">
                    <div className="flex items-center justify-between mb-3">
                        <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">
                            Resources
                        </p>
                        {!editingResources && (
                            <button
                                onClick={onOpenEditResources}
                                className="text-[10px] font-heading font-bold uppercase tracking-wider text-text-muted hover:text-accent transition-colors"
                            >
                                Edit
                            </button>
                        )}
                    </div>

                    {!editingResources ? (
                        <div>
                            <InfoRow label="RAM" value={`${server.memory_mb} MB`}/>
                            <InfoRow label="CPU Shares" value={server.cpu_shares === 0 ? "Unlimited" : String(server.cpu_shares)}/>
                            <InfoRow label="Image Tag" value={server.itzg_image_tag}/>
                        </div>
                    ) : (
                        <div className="space-y-3">
                            {resourcesError && (
                                <p className="text-[11px] text-error">{resourcesError}</p>
                            )}
                            <EditFieldRow label="RAM (MB)">
                                <EditInput
                                    type="number"
                                    value={editRamMb}
                                    onChange={(e) => onChangeRamMb(Number(e.target.value))}
                                    min={512}
                                    step={256}
                                />
                            </EditFieldRow>
                            <EditFieldRow label="CPU Shares">
                                <EditInput
                                    type="number"
                                    value={editCpuShares}
                                    onChange={(e) => onChangeCpuShares(Number(e.target.value))}
                                    min={0}
                                />
                                <p className="text-[10px] text-text-muted mt-1">0 = unlimited</p>
                            </EditFieldRow>
                            <EditFieldRow label="itzg Image Tag">
                                <EditInput
                                    value={editItzgTag}
                                    onChange={(e) => onChangeItzgTag(e.target.value)}
                                    placeholder="latest"
                                />
                            </EditFieldRow>
                            <p className="text-[10px] text-text-muted">All changes require a restart to take effect.</p>
                            <SaveCancelRow onSave={onSaveResources} onCancel={onCancelResources} saving={savingResources}/>
                        </div>
                    )}
                </div>
            )}

            {/* Players online panel — hidden when STOPPED or no player data (phase 3) */}
            {/* TODO (phase 3): show when player_count > 0, render player_list chips */}
        </div>
    );
}
