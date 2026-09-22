"use client";

import {InfoRow} from "@/components/edit/info-row";
import {EditGeneral} from "./edit-general";
import {EditResources} from "./edit-resources";
import {EditExposure} from "./edit-exposure";
import {LiveMetricsCard, LiveMetricsStatCards} from "./live-metrics";
import {PlayersPanel} from "./players-panel";
import {hasPermission} from "@/lib/permissions";
import {timeAgo} from "@/lib/utils/format";
import type {Network, Node, Server} from "@/lib/types";
import {isCustomType, isProxyType} from "@/lib/server-types";

type LiveMetrics = { cpuPercent: number; ramUsedMb: number; netInBytes: number; netOutBytes: number };
type LivePlayers = { count: number; list: string[] };

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

            {canResources && (
                <EditResources server={server} onSaved={onSaved}/>
            )}

            {canConfigure && (
                <EditExposure server={server} onSaved={onSaved}/>
            )}
        </div>
    );

    const infoColumn = (
        <div className="space-y-6">
            <LiveMetricsCard liveMetrics={liveMetrics} server={server}/>

            <div className="bg-surface border border-border rounded p-4">
                <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-2">
                    Server Info
                </p>
                <InfoRow label="Type" value={server.server_type}/>
                <InfoRow label="Version" value={(isProxyType(server.server_type) || isCustomType(server.server_type)) ? "-" : server.mc_version}/>
                <InfoRow label="Config" value={server.config_mode}/>
                <InfoRow label="Node" value={node?.display_name ?? server.node_id.slice(0, 8) + "\u2026"}/>
                <InfoRow label="Network" value={network?.name ?? "-"}/>
                <InfoRow label="Port" value={server.host_port}/>
                <InfoRow
                    label="Hostname"
                    value={
                        server.exposed_externally && server.public_subdomain
                            ? server.public_subdomain
                            : "-"
                    }
                />
                <InfoRow
                    label="Last seen"
                    value={node?.last_seen_at ? timeAgo(node.last_seen_at) : "-"}
                />
                <InfoRow label="Created" value={new Date(server.created_at).toLocaleDateString()}/>
            </div>

            <PlayersPanel livePlayers={livePlayers}/>
        </div>
    );

    return (
        <div className="px-6 py-6 space-y-6">
            <LiveMetricsStatCards
                liveMetrics={liveMetrics}
                livePlayers={livePlayers}
                server={server}
                node={node}
            />

            {hasForms ? (
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
                    {formsColumn}
                    {infoColumn}
                </div>
            ) : (
                infoColumn
            )}
        </div>
    );
}
