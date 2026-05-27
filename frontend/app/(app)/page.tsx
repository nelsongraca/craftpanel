"use client";

import { useState, useEffect, useCallback } from "react";
import Link from "next/link";
import { AlertTriangle, Clock } from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import { listServers, listNodes } from "@/lib/generated/sdk.gen";
import type { Server, Node } from "@/lib/types";

// ── Helpers ───────────────────────────────────────────────────────────────────

function timeAgo(iso: string): string {
  const secs = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
  if (secs < 60)    return `${secs}s ago`;
  if (secs < 3600)  return `${Math.floor(secs / 60)}m ago`;
  if (secs < 86400) return `${Math.floor(secs / 3600)}h ago`;
  return `${Math.floor(secs / 86400)}d ago`;
}

const NODE_STATUS_CLASSES: Record<string, string> = {
  ACTIVE:         "text-healthy  border border-healthy/30  bg-healthy/10",
  PENDING:        "text-warning  border border-warning/30  bg-warning/10",
  DEGRADED:       "text-error    border border-error/30    bg-error/10",
  REJECTED:       "text-text-muted border border-border   bg-surface-high",
  DECOMMISSIONED: "text-text-muted border border-border   bg-surface-high",
};

const SERVER_STATUS_CLASSES: Record<string, string> = {
  HEALTHY:   "text-healthy  border border-healthy/30  bg-healthy/10",
  UNHEALTHY: "text-error    border border-error/30    bg-error/10",
  STARTING:  "text-warning  border border-warning/30  bg-warning/10",
  STOPPING:  "text-warning  border border-warning/30  bg-warning/10",
  STOPPED:   "text-text-muted border border-border   bg-surface-high",
};

function StatusBadge({ status, classes }: { status: string; classes: Record<string, string> }) {
  const cls = classes[status] ?? "text-text-muted border border-border bg-surface-high";
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-[10px] font-heading font-bold uppercase tracking-wider ${cls}`}>
      {status}
    </span>
  );
}

function RamBar({ used, total }: { used: number; total: number }) {
  const pct = total > 0 ? Math.min(100, (used / total) * 100) : 0;
  const color = pct >= 86 ? "var(--error)" : pct >= 66 ? "var(--warning)" : "var(--accent)";
  return (
    <div className="flex items-center gap-2">
      <div className="w-16 h-1 rounded-full bg-border">
        <div className="h-full rounded-full" style={{ width: `${pct}%`, background: color }} />
      </div>
      <span className="font-mono text-[10px] text-text-muted">{Math.round(pct)}%</span>
    </div>
  );
}

// ── Stat card ─────────────────────────────────────────────────────────────────

function StatCard({
  label, value, sub, href, accent,
}: {
  label: string; value: number; sub: string; href: string; accent?: boolean;
}) {
  return (
    <Link href={href} className="block bg-surface border border-border rounded-md p-5 hover:border-accent/40 transition-colors">
      <p className="text-[11px] font-heading font-bold uppercase tracking-widest text-text-muted mb-2">{label}</p>
      <p className={`text-3xl font-heading font-bold tabular-nums ${accent ? "text-error" : "text-text-primary"}`}>{value}</p>
      <p className="text-[11px] text-text-muted mt-1">{sub}</p>
    </Link>
  );
}

// ── Main ──────────────────────────────────────────────────────────────────────

export default function Dashboard() {
  const [servers, setServers] = useState<Server[]>([]);
  const [nodes, setNodes] = useState<Node[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    const [sRes, nRes] = await Promise.all([listServers(), listNodes()]);
    if (sRes.data) setServers(sRes.data);
    if (nRes.data) setNodes(nRes.data);
    setLoading(false);
  }, []);

  useEffect(() => {
    load();
    const interval = setInterval(load, 30_000);
    return () => clearInterval(interval);
  }, [load]);

  const totalServers   = servers.length;
  const healthyServers = servers.filter((s) => s.status === "HEALTHY").length;
  const unhealthy      = servers.filter((s) => s.status === "UNHEALTHY").length;
  const totalNodes     = nodes.length;
  const activeNodes    = nodes.filter((n) => n.status === "ACTIVE").length;
  const pendingNodes   = nodes.filter((n) => n.status === "PENDING").length;

  const recentServers = [...servers]
    .sort((a, b) => new Date(b.updated_at).getTime() - new Date(a.updated_at).getTime())
    .slice(0, 20);

  return (
    <div>
      <PageHeader title="Dashboard" subtitle="Platform overview" />
      <div className="p-6 space-y-6">

        {/* Stat cards */}
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          <StatCard label="Servers"          value={totalServers}  sub={`${healthyServers} healthy`}       href="/servers" />
          <StatCard label="Nodes"            value={totalNodes}    sub={`${activeNodes} active`}           href="/nodes" />
          <StatCard label="Unhealthy"        value={unhealthy}     sub="servers need attention"             href="/servers" accent={unhealthy > 0} />
          <StatCard label="Pending Nodes"    value={pendingNodes}  sub="awaiting approval"                 href="/nodes" accent={pendingNodes > 0} />
        </div>

        {/* Split panels */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">

          {/* Node health overview */}
          <div className="bg-surface border border-border rounded-md overflow-hidden">
            <div className="px-5 py-3 border-b border-border">
              <h2 className="text-[11px] font-heading font-bold uppercase tracking-widest text-text-muted">Node Health</h2>
            </div>
            {loading ? (
              <div className="p-6 text-[12px] text-text-muted">Loading…</div>
            ) : nodes.length === 0 ? (
              <div className="p-6 text-[12px] text-text-muted">No nodes registered.</div>
            ) : (
              <table className="w-full text-[12px]">
                <thead>
                  <tr className="border-b border-border">
                    <th className="text-left px-5 py-2 text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">Node</th>
                    <th className="text-left px-3 py-2 text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">Status</th>
                    <th className="text-left px-3 py-2 text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">RAM</th>
                    <th className="text-left px-3 py-2 text-[10px] font-heading font-bold uppercase tracking-widest text-text-muted">Last seen</th>
                  </tr>
                </thead>
                <tbody>
                  {nodes.map((node) => (
                    <tr key={node.id} className="border-b border-border/50 hover:bg-surface-high/40">
                      <td className="px-5 py-2.5 text-text-primary font-medium truncate max-w-[120px]">
                        <Link href={`/nodes/${node.id}`} className="hover:text-accent transition-colors">
                          {node.display_name}
                        </Link>
                      </td>
                      <td className="px-3 py-2.5">
                        <StatusBadge status={node.status} classes={NODE_STATUS_CLASSES} />
                      </td>
                      <td className="px-3 py-2.5">
                        <RamBar used={node.allocated_ram_mb} total={node.total_ram_mb} />
                      </td>
                      <td className="px-3 py-2.5 text-text-muted font-mono text-[11px]">
                        {node.last_seen_at ? timeAgo(node.last_seen_at) : "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          {/* Recent server events */}
          <div className="bg-surface border border-border rounded-md overflow-hidden">
            <div className="px-5 py-3 border-b border-border">
              <h2 className="text-[11px] font-heading font-bold uppercase tracking-widest text-text-muted">Recent Server Activity</h2>
            </div>
            {loading ? (
              <div className="p-6 text-[12px] text-text-muted">Loading…</div>
            ) : recentServers.length === 0 ? (
              <div className="p-6 text-[12px] text-text-muted">No servers found.</div>
            ) : (
              <ul className="divide-y divide-border/50">
                {recentServers.map((s) => (
                  <li key={s.id} className="flex items-center justify-between px-5 py-2.5 hover:bg-surface-high/40">
                    <div className="flex items-center gap-2 min-w-0">
                      {s.status === "UNHEALTHY" && (
                        <AlertTriangle size={12} className="text-error shrink-0" />
                      )}
                      <Link href={`/servers/${s.id}`} className="text-[12px] text-text-primary hover:text-accent truncate transition-colors">
                        {s.display_name}
                      </Link>
                    </div>
                    <div className="flex items-center gap-3 shrink-0 ml-3">
                      <StatusBadge status={s.status} classes={SERVER_STATUS_CLASSES} />
                      <span className="flex items-center gap-1 text-[10px] text-text-muted font-mono">
                        <Clock size={10} />
                        {timeAgo(s.updated_at)}
                      </span>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>

        </div>
      </div>
    </div>
  );
}
