"use client";

import {InfoRow} from "./server-info";
import {EditGeneral} from "./edit-general";
import {EditResources} from "./edit-resources";
import {EditExposure} from "./edit-exposure";
import {LiveMetricsPanel} from "./live-metrics";
import {PlayersPanel} from "./players-panel";
import {hasPermission} from "@/lib/permissions";
import {timeAgo} from "@/lib/utils/format";
import type {Network, Node, Server} from "@/lib/types";

type LiveMetrics = { cpuPercent: number; ramUsedMb: number; netInBytes: number; netOutBytes: number };
type LivePlayers = { count: number; list: string[] };

export interface GeneralEditState {
    editing: boolean;
    displayName: string;
    description: string;
    networkId: string;
    mcVersion: string;
    saving: boolean;
    error: string | null;
    onOpen: () => void;
    onSave: () => void;
    onCancel: () => void;
    onChangeName: (v: string) => void;
    onChangeDesc: (v: string) => void;
    onChangeNetwork: (v: string) => void;
    onChangeMcVersion: (v: string) => void;
}

export interface ResourcesEditState {
    editing: boolean;
    ramMb: number;
    cpuShares: number;
    itzgTag: string;
    saving: boolean;
    error: string | null;
    onOpen: () => void;
    onSave: () => void;
    onCancel: () => void;
    onChangeRamMb: (v: number) => void;
    onChangeCpuShares: (v: number) => void;
    onChangeItzgTag: (v: string) => void;
}

export interface ExposureEditState {
    editing: boolean;
    exposedExternally: boolean;
    publicSubdomain: string;
    customHostname: string;
    saving: boolean;
    error: string | null;
    onOpen: () => void;
    onSave: () => void;
    onCancel: () => void;
    onChangeExposedExternally: (v: boolean) => void;
    onChangePublicSubdomain: (v: string) => void;
    onChangeCustomHostname: (v: string) => void;
}

export interface OverviewTabProps {
    server: Server;
    node: Node | null;
    network: Network | null;
    permissions: string[];
    liveMetrics: LiveMetrics | null;
    livePlayers: LivePlayers | null;
    networks: Network[];
    mcVersions: string[];
    generalEdit: GeneralEditState;
    resourcesEdit: ResourcesEditState;
    exposureEdit: ExposureEditState;
}

export function OverviewTab({
                                server,
                                node,
                                network,
                                permissions,
                                liveMetrics,
                                livePlayers,
                                networks,
                                mcVersions,
                                generalEdit,
                                resourcesEdit,
                                exposureEdit,
                            }: OverviewTabProps) {
    const canConfigure = hasPermission(permissions, "server.configure");
    const canResources = hasPermission(permissions, "server.resources");

    return (
        <div className="px-6 py-6 space-y-6">
            <LiveMetricsPanel
                liveMetrics={liveMetrics}
                livePlayers={livePlayers}
                server={server}
                node={node}
            />

            {/* Panels row */}
            <div className="grid grid-cols-[1fr_1fr] gap-4">
                {/* Server info */}
                <div className="bg-surface border border-border rounded p-4">
                    <p className="text-[12px] font-heading font-bold uppercase tracking-widest text-text-muted mb-2">
                        Server Info
                    </p>
                    <InfoRow label="Type" value={server.server_type}/>
                    <InfoRow label="Version" value={["VELOCITY", "BUNGEECORD", "WATERFALL"].includes(server.server_type) ? "\u2014" : server.mc_version}/>
                    <InfoRow label="Config" value={server.config_mode}/>
                    <InfoRow label="Node" value={node?.display_name ?? server.node_id.slice(0, 8) + "\u2026"}/>
                    <InfoRow label="Network" value={network?.name ?? "\u2014"}/>
                    <InfoRow label="Port" value={server.host_port}/>
                    <InfoRow
                        label="Hostname"
                        value={
                            server.exposed_externally && server.public_subdomain
                                ? server.public_subdomain
                                : "\u2014"
                        }
                    />
                    <InfoRow
                        label="Last seen"
                        value={node?.last_seen_at ? timeAgo(node.last_seen_at) : "\u2014"}
                    />
                    <InfoRow label="Created" value={new Date(server.created_at).toLocaleDateString()}/>
                </div>
            </div>

            {/* General settings */}
            {canConfigure && (
                <EditGeneral
                    server={server}
                    networks={networks}
                    mcVersions={mcVersions}
                    editing={generalEdit.editing}
                    displayName={generalEdit.displayName}
                    description={generalEdit.description}
                    networkId={generalEdit.networkId}
                    mcVersion={generalEdit.mcVersion}
                    saving={generalEdit.saving}
                    error={generalEdit.error}
                    onOpen={generalEdit.onOpen}
                    onSave={generalEdit.onSave}
                    onCancel={generalEdit.onCancel}
                    onChangeName={generalEdit.onChangeName}
                    onChangeDesc={generalEdit.onChangeDesc}
                    onChangeNetwork={generalEdit.onChangeNetwork}
                    onChangeMcVersion={generalEdit.onChangeMcVersion}
                />
            )}

            {/* Resources */}
            {canResources && (
                <EditResources
                    server={server}
                    editing={resourcesEdit.editing}
                    ramMb={resourcesEdit.ramMb}
                    cpuShares={resourcesEdit.cpuShares}
                    itzgTag={resourcesEdit.itzgTag}
                    saving={resourcesEdit.saving}
                    error={resourcesEdit.error}
                    onOpen={resourcesEdit.onOpen}
                    onSave={resourcesEdit.onSave}
                    onCancel={resourcesEdit.onCancel}
                    onChangeRamMb={resourcesEdit.onChangeRamMb}
                    onChangeCpuShares={resourcesEdit.onChangeCpuShares}
                    onChangeItzgTag={resourcesEdit.onChangeItzgTag}
                />
            )}

            {/* Public Access */}
            {canConfigure && (
                <EditExposure
                    server={server}
                    editing={exposureEdit.editing}
                    exposedExternally={exposureEdit.exposedExternally}
                    publicSubdomain={exposureEdit.publicSubdomain}
                    customHostname={exposureEdit.customHostname}
                    saving={exposureEdit.saving}
                    error={exposureEdit.error}
                    onOpen={exposureEdit.onOpen}
                    onSave={exposureEdit.onSave}
                    onCancel={exposureEdit.onCancel}
                    onChangeExposedExternally={exposureEdit.onChangeExposedExternally}
                    onChangePublicSubdomain={exposureEdit.onChangePublicSubdomain}
                    onChangeCustomHostname={exposureEdit.onChangeCustomHostname}
                />
            )}

            <PlayersPanel livePlayers={livePlayers}/>
        </div>
    );
}
