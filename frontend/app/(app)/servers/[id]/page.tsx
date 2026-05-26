"use client";

// TODO (phase 3): replace 30s polling with WebSocket subscription to live
// agent metrics (ram_used_mb, cpu_percent, player_count, player_list).

import { useState, useEffect, useCallback } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import {
  Play,
  Square,
  RotateCcw,
  MoreHorizontal,
  ChevronRight,
  X,
  ArrowUpCircle,
  Shuffle,
} from "lucide-react";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { hasPermission } from "@/lib/permissions";
import type { Server, Node, Network } from "@/lib/types";

// ── Status helpers ────────────────────────────────────────────────────────────

type DisplayStatus = "HEALTHY" | "UNHEALTHY" | "STARTING" | "STOPPING" | "STOPPED";

function toDisplayStatus(status: string): DisplayStatus {
  switch (status) {
    case "RUNNING":  return "HEALTHY";
    case "ERROR":    return "UNHEALTHY";
    case "STARTING": return "STARTING";
    case "STOPPING": return "STOPPING";
    default:         return "STOPPED";
  }
}

const DISPLAY_LABELS: Record<DisplayStatus, string> = {
  HEALTHY:   "Healthy",
  UNHEALTHY: "Unhealthy",
  STARTING:  "Starting",
  STOPPING:  "Stopping",
  STOPPED:   "Stopped",
};

const DISPLAY_CLASSES: Record<DisplayStatus, string> = {
  HEALTHY:   "text-healthy  border border-healthy/30  bg-healthy/10",
  UNHEALTHY: "text-error    border border-error/30    bg-error/10",
  STARTING:  "text-warning  border border-warning/30  bg-warning/10",
  STOPPING:  "text-warning  border border-warning/30  bg-warning/10",
  STOPPED:   "text-text-muted border border-border    bg-surface-high",
};

// ── Utilities ─────────────────────────────────────────────────────────────────

function timeAgo(iso: string): string {
  const secs = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
  if (secs < 60)        return `${secs}s ago`;
  if (secs < 3600)      return `${Math.floor(secs / 60)}m ago`;
  if (secs < 86400)     return `${Math.floor(secs / 3600)}h ago`;
  return `${Math.floor(secs / 86400)}d ago`;
}

function fmtMb(mb: number): string {
  if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`;
  return `${mb} MB`;
}

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

function RamBarInline({ usedMb, totalMb }: { usedMb: number | null; totalMb: number }) {
  if (usedMb === null) {
    return (
      <div className="flex flex-col gap-1.5">
        <p className="font-mono text-[20px] text-text-muted leading-none">—</p>
        <p className="font-mono text-[11px] text-text-muted">— / {fmtMb(totalMb)} alloc</p>
        <div className="h-1.5 rounded-full bg-surface-higher w-full" />
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
        <div className={`h-full rounded-full ${barColor}`} style={{ width: `${pct}%` }} />
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
    green:  "text-healthy  border-healthy/40  hover:bg-healthy/10",
    red:    "text-error    border-error/40    hover:bg-error/10",
    yellow: "text-warning  border-warning/40  hover:bg-warning/10",
  }[variant];

  return (
    <button
      onClick={onClick}
      disabled={loading}
      className={`flex items-center gap-1.5 px-3 py-1.5 rounded border text-[11px] font-heading font-bold uppercase tracking-widest transition-colors disabled:opacity-40 ${cls}`}
    >
      {loading ? (
        <span className="w-3 h-3 border border-current border-t-transparent rounded-full animate-spin" />
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

function ComingSoon({ tab }: { tab: string }) {
  return (
    <div className="px-6 py-10">
      <div className="border-2 border-dashed border-border rounded-md py-10 text-center text-text-muted text-[13px]">
        {tab} — coming soon
      </div>
    </div>
  );
}

// ── Info row helper ───────────────────────────────────────────────────────────

function InfoRow({ label, value }: { label: string; value: React.ReactNode }) {
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
  const params  = useParams();
  const id      = params.id as string;
  const router  = useRouter();
  const { user } = useAuth();
  const permissions = user?.permissions ?? [];

  const [server, setServer]   = useState<Server  | null>(null);
  const [node,   setNode]     = useState<Node    | null>(null);
  const [network,setNetwork]  = useState<Network | null>(null);
  const [loading,   setLoading]    = useState(true);
  const [notFound,  setNotFound]   = useState(false);
  const [activeTab, setActiveTab]  = useState<Tab>("Overview");
  const [actionError, setActionError] = useState<string | null>(null);
  const [pending, setPending]      = useState<string | null>(null);
  const [menuOpen, setMenuOpen]    = useState(false);

  // ── Data fetching ──────────────────────────────────────────────────────────

  const fetchServer = useCallback(async () => {
    const res = await api.get(`/servers/${id}`);
    if (res.status === 404) { setNotFound(true); return; }
    if (res.ok) setServer(await res.json());
  }, [id]);

  useEffect(() => {
    fetchServer().then(() => setLoading(false));
  }, [fetchServer]);

  const nodeId    = server?.node_id;
  const networkId = server?.network_id;

  useEffect(() => {
    if (!nodeId) return;
    api.get(`/nodes/${nodeId}`).then(r => { if (r.ok) r.json().then(setNode); });
  }, [nodeId]);

  useEffect(() => {
    if (!networkId) return;
    api.get(`/networks/${networkId}`).then(r => { if (r.ok) r.json().then(setNetwork); });
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

  // ── Actions ────────────────────────────────────────────────────────────────

  async function doAction(action: string) {
    setPending(action);
    setActionError(null);
    try {
      const res = await api.post(`/servers/${id}/${action}`);
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        setActionError(body.message ?? `Failed to ${action} server`);
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
      const res = await api.delete(`/servers/${id}`);
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        setActionError(body.message ?? "Failed to delete server");
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
      const res = await api.post(`/servers/${id}/upgrade`, { itzg_image_tag: tag.trim() });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        setActionError(body.message ?? "Failed to upgrade server");
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
        <div className="h-4 w-40 bg-surface rounded animate-pulse" />
        <div className="h-8 w-64 bg-surface rounded animate-pulse" />
        <div className="h-4 w-48 bg-surface rounded animate-pulse" />
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
              <ChevronRight size={11} strokeWidth={2.5} />
              <Link
                href={`/networks/${network.id}`}
                className="hover:text-text-primary transition-colors"
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
                icon={<Play size={12} strokeWidth={2.5} />}
                label="Start"
                loading={pending === "start"}
                onClick={() => doAction("start")}
                variant="green"
              />
            )}
            {(ds === "HEALTHY" || ds === "STARTING") && hasPermission(permissions, "server.stop") && (
              <HeaderActionButton
                icon={<Square size={12} strokeWidth={2.5} />}
                label="Stop"
                loading={pending === "stop"}
                onClick={() => doAction("stop")}
                variant="red"
              />
            )}
            {ds === "HEALTHY" && hasPermission(permissions, "server.restart") && (
              <HeaderActionButton
                icon={<RotateCcw size={12} strokeWidth={2.5} />}
                label="Restart"
                loading={pending === "restart"}
                onClick={() => doAction("restart")}
                variant="yellow"
              />
            )}

            {/* ··· overflow menu */}
            <div className="relative">
              <button
                onClick={(e) => { e.stopPropagation(); setMenuOpen((o) => !o); }}
                className="flex items-center justify-center w-8 h-8 rounded border border-border text-text-muted hover:text-text-primary hover:bg-surface-high transition-colors"
              >
                <MoreHorizontal size={14} strokeWidth={2} />
              </button>

              {menuOpen && (
                <div
                  className="absolute right-0 top-full mt-1 z-50 bg-surface-higher border border-border rounded shadow-xl min-w-[160px] py-1"
                  onClick={(e) => e.stopPropagation()}
                >
                  {hasPermission(permissions, "server.migrate") && (
                    <button
                      onClick={() => { setMenuOpen(false); window.alert("Migration UI coming in phase 3."); }}
                      className="flex items-center gap-2 w-full text-left px-3 py-2 text-[12px] font-heading font-bold uppercase tracking-wider text-text-primary hover:bg-surface-high transition-colors"
                    >
                      <Shuffle size={12} strokeWidth={2} />
                      Migrate
                    </button>
                  )}
                  {hasPermission(permissions, "server.upgrade") && (
                    <button
                      onClick={() => { setMenuOpen(false); doUpgrade(); }}
                      className="flex items-center gap-2 w-full text-left px-3 py-2 text-[12px] font-heading font-bold uppercase tracking-wider text-text-primary hover:bg-surface-high transition-colors"
                    >
                      <ArrowUpCircle size={12} strokeWidth={2} />
                      Upgrade
                    </button>
                  )}
                  {ds === "STOPPED" && hasPermission(permissions, "server.delete") && (
                    <button
                      onClick={() => { setMenuOpen(false); doDelete(); }}
                      className="flex items-center gap-2 w-full text-left px-3 py-2 text-[12px] font-heading font-bold uppercase tracking-wider text-error hover:bg-surface-high transition-colors"
                    >
                      <X size={12} strokeWidth={2} />
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
            <X size={13} />
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
          </button>
        ))}
      </div>

      {/* ── Tab content ── */}
      {activeTab !== "Overview" ? (
        <ComingSoon tab={activeTab} />
      ) : (
        <OverviewTab server={server} node={node} network={network} />
      )}
    </div>
  );
}

// ── Overview tab ──────────────────────────────────────────────────────────────

function OverviewTab({
  server,
  node,
  network,
}: {
  server: Server;
  node: Node | null;
  network: Network | null;
}) {
  const ds = toDisplayStatus(server.status);

  return (
    <div className="px-6 py-6 space-y-6">

      {/* Stat cards */}
      <div className="grid grid-cols-4 gap-4">

        {/* PLAYERS ONLINE — TODO (phase 3): populate from WebSocket player_count */}
        <StatCard label="Players Online">
          <p className="font-mono text-[20px] text-text-muted leading-none">—/—</p>
          <p className="text-[11px] text-text-muted">No live data yet</p>
        </StatCard>

        {/* RAM USAGE — TODO (phase 3): populate ram_used_mb from WebSocket */}
        <StatCard label="RAM Usage">
          <RamBarInline usedMb={null} totalMb={server.memory_mb} />
        </StatCard>

        {/* CPU USAGE — TODO (phase 3): populate cpu_percent from WebSocket */}
        <StatCard label="CPU Usage">
          <p className="font-mono text-[20px] text-text-muted leading-none">—%</p>
          <p className="font-mono text-[11px] text-text-muted">{server.cpu_shares} shares alloc</p>
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

        {/* Live metrics — TODO (phase 3): hook up WebSocket agent metrics */}
        <div className="bg-surface border border-border rounded p-4">
          <div className="flex items-center justify-between mb-4">
            <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">
              Live Metrics
            </p>
            <span className="text-[10px] font-heading text-text-muted italic">
              Phase 3 — WebSocket
            </span>
          </div>
          <div className="space-y-3">
            {[
              { label: "CPU",     value: "—%"  },
              { label: "RAM",     value: "—%"  },
              { label: "Net ↓",   value: "— KB/s" },
              { label: "Net ↑",   value: "— KB/s" },
            ].map(({ label, value }) => (
              <div key={label} className="flex items-center justify-between">
                <span className="text-[11px] font-heading font-bold uppercase tracking-wider text-text-muted">
                  {label}
                </span>
                <span className="font-mono text-[12px] text-text-muted">{value}</span>
              </div>
            ))}
          </div>
          <button className="mt-4 text-[11px] font-heading font-bold uppercase tracking-wider text-text-muted hover:text-accent transition-colors">
            View history →
          </button>
        </div>

        {/* Server info */}
        <div className="bg-surface border border-border rounded p-4">
          <p className="text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted mb-2">
            Server Info
          </p>
          <InfoRow label="Type"    value={server.server_type} />
          <InfoRow label="Config"  value={server.config_mode} />
          <InfoRow label="Node"    value={node?.display_name ?? server.node_id.slice(0, 8) + "…"} />
          <InfoRow label="Network" value={network?.name ?? "—"} />
          <InfoRow label="Port"    value={server.game_port} />
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
          <InfoRow label="Created" value={new Date(server.created_at).toLocaleDateString()} />
        </div>
      </div>

      {/* Players online panel — hidden when STOPPED or no player data (phase 3) */}
      {/* TODO (phase 3): show when player_count > 0, render player_list chips */}
    </div>
  );
}
