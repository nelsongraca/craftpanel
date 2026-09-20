"use client";

import {useEffect, useMemo, useState} from "react";
import {useRouter, useSearchParams} from "next/navigation";
import Link from "next/link";
import {Plus, Upload, X} from "lucide-react";
import PageHeader from "@/app/components/PageHeader";
import {importServer, listNetworks, listNodes, listServers} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import type {Network, Node} from "@/lib/types";
import {useResourceList} from "@/lib/hooks/useResourceList";
import {ServerList} from "@/components/servers/server-list";
import {useServerActions} from "@/components/servers/server-actions";
import {SelectField} from "@/components/ui/form-elements";
import {BTN_GHOST, BTN_PRIMARY, Modal, Field} from "@/components/ui/form-elements";

// Filter option → backend statuses that match
const FILTER_MATCHES: Record<string, string[]> = {
    HEALTHY: ["HEALTHY"],
    UNHEALTHY: ["UNHEALTHY"],
    STARTING: ["STARTING", "STOPPING"],
    STOPPED: ["STOPPED"],
};

const FILTER_OPTIONS = [
    {label: "All Statuses", value: ""},
    {label: "Healthy", value: "HEALTHY"},
    {label: "Unhealthy", value: "UNHEALTHY"},
    {label: "Starting", value: "STARTING"},
    {label: "Stopped", value: "STOPPED"},
];

export default function ServersPage() {
    const router = useRouter();
    const searchParams = useSearchParams();
    const {user} = useAuth();
    const permissions = user?.permissions ?? [];

    const {data: servers, initialLoad, reload: reloadServers} = useResourceList(listServers, []);
    const [nodes, setNodes] = useState<Node[]>([]);
    const [networks, setNetworks] = useState<Network[]>([]);
    const {renderActions, actionError, setActionError, dialog} = useServerActions({
        permissions,
        serverPermissionsMap: user?.server_permissions ?? {},
        onChanged: reloadServers,
    });
    const [showImport, setShowImport] = useState(false);
    const [importFile, setImportFile] = useState<File | null>(null);
    const [importNode, setImportNode] = useState("");
    const [importing, setImporting] = useState(false);
    const [importError, setImportError] = useState("");

    const [search, setSearch] = useState("");
    const [filterStatus, setFilterStatus] = useState("");
    const [filterNetwork, setFilterNetwork] = useState(searchParams.get("network") ?? "");
    const [filterNode, setFilterNode] = useState("");
    const [filterType, setFilterType] = useState("");

    const typeOptions = useMemo(() => {
        const types = Array.from(new Set(servers.map((s) => s.server_type))).sort();
        return types;
    }, [servers]);

    useEffect(() => {
        void Promise.all([listNodes(), listNetworks()]).then(([nRes, netRes]) => {
            if (nRes.data) setNodes(nRes.data);
            if (netRes.data) setNetworks(netRes.data);
        });
    }, []);

    const uniqueNodeCount = new Set(servers.map((s) => s.node_id)).size;
    const subtitle = initialLoad
        ? undefined
        : `${servers.length} server${servers.length !== 1 ? "s" : ""} across ${uniqueNodeCount} node${uniqueNodeCount !== 1 ? "s" : ""}`;

    const filteredServers = servers.filter((s) => {
        if (search) {
            const q = search.toLowerCase();
            if (
                !s.display_name.toLowerCase().includes(q) &&
                !s.name.toLowerCase().includes(q) &&
                !(s.public_subdomain?.toLowerCase().includes(q))
            ) return false;
        }
        if (filterStatus) {
            const allowed = FILTER_MATCHES[filterStatus] ?? [];
            if (!allowed.includes(s.status)) return false;
        }
        if (filterNetwork && s.network_id !== filterNetwork) return false;
        if (filterNode && s.node_id !== filterNode) return false;
        if (filterType && s.server_type !== filterType) return false;
        return true;
    });

    async function doImportServer() {
        if (!importFile) return;
        setImportError("");
        setImporting(true);
        try {
            const text = await importFile.text();
            const data = JSON.parse(text);
            const {error} = await importServer({body: {data, node_id: importNode}});
            if (error) {
                setImportError((error as { message?: string }).message ?? "Failed to import server");
            } else {
                setShowImport(false);
                setImportFile(null);
                setImportNode("");
                reloadServers();
            }
        } catch {
            setImportError("Invalid JSON file");
        }
        setImporting(false);
    }

    const canCreate = hasPermission(permissions, "server.create");

    return (
        <>
            <div>
                <PageHeader
                    title="Servers"
                    subtitle={subtitle}
                    action={
                        <div className="flex items-center gap-2">
                            {hasPermission(permissions, "server.create") && (
                                <button onClick={() => setShowImport(true)} className="flex items-center gap-1.5 bg-surface-higher hover:bg-surface-higher/80 text-text-primary font-heading font-bold text-xs uppercase tracking-widest px-3 py-1.5 rounded transition-colors border border-border">
                                    <Upload size={12} strokeWidth={3}/>
                                    Import
                                </button>
                            )}
                            {canCreate ? (
                                <Link
                                    href="/servers/new"
                                    className="flex items-center gap-1.5 bg-accent hover:bg-accent-bright text-bg font-heading font-bold text-xs uppercase tracking-widest px-3 py-1.5 rounded transition-colors hover:shadow-[0_0_16px_var(--accent-glow)]"
                                >
                                    <Plus size={12} strokeWidth={3}/>
                                    New Server
                                </Link>
                            ) : undefined}
                        </div>
                    }
                />

                {/* Filter bar */}
                <div className="flex flex-wrap items-center gap-2 px-6 py-3 border-b border-border bg-surface">
                    <input
                        type="text"
                        placeholder="Search servers…"
                        value={search}
                        onChange={(e) => setSearch(e.target.value)}
                        className="h-7 bg-surface-higher border border-border rounded px-2.5 text-xs font-mono text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent w-full sm:w-48"
                    />
                    <SelectField
                        surface="surface-higher"
                        fieldSize="sm"
                        className="h-7 w-full sm:w-40 font-heading"
                        value={filterStatus}
                        onChange={(e) => setFilterStatus(e.target.value)}
                    >
                        {FILTER_OPTIONS.map((o) => (
                            <option key={o.value} value={o.value}>{o.label}</option>
                        ))}
                    </SelectField>
                    {networks.length > 0 && (
                        <SelectField
                            surface="surface-higher"
                            fieldSize="sm"
                            className="h-7 w-full sm:w-40 font-heading"
                            value={filterNetwork}
                            onChange={(e) => setFilterNetwork(e.target.value)}
                        >
                            <option value="">All Networks</option>
                            {networks.map((n) => (
                                <option key={n.id} value={n.id}>{n.name}</option>
                            ))}
                        </SelectField>
                    )}
                    {nodes.length > 0 && (
                        <SelectField
                            surface="surface-higher"
                            fieldSize="sm"
                            className="h-7 w-full sm:w-40 font-heading"
                            value={filterNode}
                            onChange={(e) => setFilterNode(e.target.value)}
                        >
                            <option value="">All Nodes</option>
                            {nodes.map((n) => (
                                <option key={n.id} value={n.id}>{n.display_name}</option>
                            ))}
                        </SelectField>
                    )}
                    {typeOptions.length > 0 && (
                        <SelectField
                            surface="surface-higher"
                            fieldSize="sm"
                            className="h-7 w-full sm:w-40 font-heading"
                            value={filterType}
                            onChange={(e) => setFilterType(e.target.value)}
                        >
                            <option value="">All Types</option>
                            {typeOptions.map((t) => (
                                <option key={t} value={t}>{t}</option>
                            ))}
                        </SelectField>
                    )}
                </div>

                {/* Error banner */}
                {actionError && (
                    <div className="mx-6 mt-4 flex items-center justify-between bg-error/10 border border-error/30 text-error rounded px-3 py-2 text-xs">
                        <span>{actionError}</span>
                        <button onClick={() => setActionError(null)} className="ml-4 hover:opacity-70" aria-label="Dismiss">
                            <X size={13}/>
                        </button>
                    </div>
                )}

                {/* Server list */}
                <div className="px-6 py-4">
                    <ServerList
                        servers={filteredServers}
                        nodes={nodes}
                        loading={initialLoad}
                        empty={
                            servers.length === 0
                                ? "No servers yet - create one to get started"
                                : "No servers match the current filters"
                        }
                        onRowClick={(server) => router.push(`/servers/${server.id}`)}
                        renderActions={renderActions}
                        actionsHeader="Actions"
                    />
                </div>
            </div>
            {showImport && (
                <Modal title="Import Server" onClose={() => { setShowImport(false); setImportError(""); setImportFile(null); }}>
                    <div className="space-y-4">
                        <input
                            type="file"
                            accept=".json"
                            onChange={(e) => setImportFile(e.target.files?.[0] ?? null)}
                            className="block w-full text-xs text-text-muted file:mr-3 file:py-1.5 file:px-3 file:rounded file:border-0 file:text-xs file:font-heading file:font-bold file:uppercase file:tracking-wider file:bg-surface-high file:text-text-primary hover:file:bg-surface-higher"
                        />
                        {importFile && (
                            <Field label="Destination Node" htmlFor="import-node">
                                <SelectField id="import-node" value={importNode} onChange={(e) => setImportNode(e.target.value)}>
                                    <option value="">Select a node…</option>
                                    {nodes.map((n) => (
                                        <option key={n.id} value={n.id}>{n.display_name}</option>
                                    ))}
                                </SelectField>
                            </Field>
                        )}
                        {importError && <p className="text-xs text-error">{importError}</p>}
                        <div className="flex justify-end gap-2 pt-1">
                            <button className={BTN_GHOST} onClick={() => { setShowImport(false); setImportError(""); setImportFile(null); }}>Cancel</button>
                            <button className={BTN_PRIMARY} disabled={!importFile || !importNode || importing} onClick={doImportServer}>
                                {importing ? "Importing…" : "Import"}
                            </button>
                        </div>
                    </div>
                </Modal>
            )}
            {dialog}
        </>
    );
}
