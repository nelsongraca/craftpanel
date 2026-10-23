"use client";

import {useState} from "react";
import {Check, Copy} from "lucide-react";
import {InfoRow} from "@/components/edit/info-row";
import {EditGeneral} from "./edit-general";
import {EditServerType} from "./edit-server-type";
import {EditResources} from "./edit-resources";
import {EditExposure} from "./edit-exposure";
import {LiveMetricsCard, LiveMetricsStatCards} from "./live-metrics";
import {PlayersPanel} from "./players-panel";
import {StatusHistoryPanel} from "./status-history";
import {hasPermission} from "@/lib/permissions";
import {timeAgo} from "@/lib/utils/format";
import type {Network, Node, Server} from "@/lib/types";
import {isCustomType, isProxyType} from "@/lib/server-types";

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

export interface OverviewTabProps {
    server: Server;
    node: Node | null;
    network: Network | null;
    permissions: string[];
    liveMetrics: LiveMetrics | null;
    livePlayers: LivePlayers | null;
    /** Bump to force the General Settings edit form open (e.g. a link from the Configuration tab). */
    forceOpenGeneralSignal?: number;
    onSaved: () => void;
}

export function OverviewTab({
    server,
    node,
    network,
    permissions,
    liveMetrics,
    livePlayers,
    forceOpenGeneralSignal,
    onSaved,
}: OverviewTabProps) {
    const canConfigure = hasPermission(permissions, "server.configure");
    const canResources = hasPermission(permissions, "server.resources");
    const [idCopied, setIdCopied] = useState(false);

    async function copyId() {
        try {
            await navigator.clipboard.writeText(server.id);
            setIdCopied(true);
            setTimeout(() => setIdCopied(false), 1500);
        } catch {
            // Clipboard unavailable (e.g. insecure context) — nothing to do.
        }
    }

    // On lg+ the overview is a two-column body: forms left, live/info right. With neither
    // permission the left column is empty, so fall back to a single full-width column.
    const hasForms = canConfigure || canResources;

    const formsColumn = (
        <div className="space-y-6">
            {canConfigure && (
                <EditGeneral
                    server={server}
                    permissions={permissions}
                    forceOpenSignal={forceOpenGeneralSignal}
                    onSaved={onSaved}
                />
            )}

            {canConfigure && <EditServerType server={server} onSaved={onSaved} />}

            {canResources && <EditResources server={server} onSaved={onSaved} />}

            {canConfigure && <EditExposure server={server} onSaved={onSaved} />}
        </div>
    );

    const infoColumn = (
        <div className="space-y-6">
            <LiveMetricsCard liveMetrics={liveMetrics} server={server} />

            <div className="rounded border border-border bg-surface p-4">
                <p className="mb-2 font-heading text-xs font-bold tracking-widest text-text-muted uppercase">
                    Server Info
                </p>
                <InfoRow label="Type" value={server.server_type} />
                <InfoRow
                    label="Version"
                    value={
                        isProxyType(server.server_type) || isCustomType(server.server_type) ? "-" : server.mc_version
                    }
                />
                <InfoRow label="Config" value={server.config_mode} />
                <InfoRow label="Node" value={node?.display_name ?? server.node_id.slice(0, 8) + "\u2026"} />
                <InfoRow label="Network" value={network?.name ?? "-"} />
                <InfoRow label="Port" value={server.host_port} />
                <InfoRow label="Hostname" value={server.canonical_hostname ?? "-"} />
                <InfoRow label="Last seen" value={node?.last_seen_at ? timeAgo(node.last_seen_at) : "-"} />
                <InfoRow label="Created" value={new Date(server.created_at).toLocaleDateString()} />
                <InfoRow
                    label="ID"
                    value={
                        <span className="inline-flex items-center justify-end gap-1.5">
                            <span className="break-all select-all">{server.id}</span>
                            <button
                                type="button"
                                onClick={() => void copyId()}
                                title={idCopied ? "Copied" : "Copy server ID"}
                                aria-label="Copy server ID"
                                className="shrink-0 text-text-muted transition-colors hover:text-text-primary"
                            >
                                {idCopied ? (
                                    <Check size={11} strokeWidth={2.5} />
                                ) : (
                                    <Copy size={11} strokeWidth={2.5} />
                                )}
                            </button>
                        </span>
                    }
                />
            </div>

            <PlayersPanel livePlayers={livePlayers} />

            <StatusHistoryPanel serverId={server.id} />
        </div>
    );

    return (
        <div className="space-y-6 px-4 py-4 lg:py-6">
            <LiveMetricsStatCards liveMetrics={liveMetrics} livePlayers={livePlayers} server={server} node={node} />

            {hasForms ? (
                <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-2">
                    {formsColumn}
                    {infoColumn}
                </div>
            ) : (
                infoColumn
            )}
        </div>
    );
}
