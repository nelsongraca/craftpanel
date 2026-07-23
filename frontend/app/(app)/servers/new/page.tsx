"use client";

import {useEffect, useState} from "react";
import {useRouter, useSearchParams} from "next/navigation";
import Link from "next/link";
import {ChevronLeft} from "lucide-react";
import {cloneServer, createServer, getServer, listNetworks, listNodes} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import {SelectField, TextAreaField, TextField} from "@/components/ui/form-elements";
import type {Network, Node} from "@/lib/types";

// ── Mojang version manifest ───────────────────────────────────────────────────

type MojangVersion = { id: string; type: string; releaseTime: string };

async function fetchReleaseVersions(): Promise<string[]> {
    const res = await fetch(
        "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
    );
    const json = await res.json() as { versions: MojangVersion[] };
    return json.versions
        .filter((v) => v.type === "release")
        .map((v) => v.id);
}

// ── Constants ─────────────────────────────────────────────────────────────────

const GAME_SERVER_TYPES = [
    "VANILLA", "PAPER", "FABRIC", "FOLIA", "FORGE",
    "NEOFORGE", "QUILT", "SPIGOT", "LIMBO",
] as const;

const PROXY_TYPES = ["VELOCITY", "BUNGEECORD", "WATERFALL"] as const;

// ── Field component helpers ───────────────────────────────────────────────────

function Label({children, required, htmlFor}: { children: React.ReactNode; required?: boolean; htmlFor?: string }) {
    return (
        <label htmlFor={htmlFor} className="block text-xs font-heading font-bold uppercase tracking-wider text-text-muted mb-1.5">
            {children}
            {required && <span className="text-error ml-1">*</span>}
        </label>
    );
}

function FieldInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
    return <TextField {...props} surface="surface" fieldSize="md"/>;
}

function FieldSelect(props: React.SelectHTMLAttributes<HTMLSelectElement>) {
    return <SelectField {...props} surface="surface" fieldSize="md"/>;
}

function FieldTextarea(props: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
    return <TextAreaField {...props} surface="surface" fieldSize="md"/>;
}

function SectionHeading({children}: { children: React.ReactNode }) {
    return (
        <p className="text-xs font-heading font-bold uppercase tracking-widest text-text-muted mb-3 mt-6 first:mt-0">
            {children}
        </p>
    );
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function NewServerPage() {
    const router = useRouter();
    const searchParams = useSearchParams();
    const cloneId = searchParams.get("clone");
    const {user} = useAuth();
    const permissions = user?.permissions ?? [];

    const [nodes, setNodes] = useState<Node[]>([]);
    const [networks, setNetworks] = useState<Network[]>([]);
    const [versions, setVersions] = useState<string[]>([]);
    const [loadingData, setLoadingData] = useState(true);

    const [name, setName] = useState("");
    const [displayName, setDisplayName] = useState("");
    const [description, setDescription] = useState("");
    const [serverType, setServerType] = useState("PAPER");
    const [mcVersion, setMcVersion] = useState("");
    const [itzgImageTag, setItzgImageTag] = useState("latest");
    const [nodeId, setNodeId] = useState("");
    const [networkId, setNetworkId] = useState("");
    const [ramMb, setRamMb] = useState(2048);
    const [cpuShares, setCpuShares] = useState(0);

    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const isProxy = (PROXY_TYPES as readonly string[]).includes(serverType);

    useEffect(() => {
        const loadBase = [
            listNodes().then(({data}) => {
                if (data) {
                    setNodes(data);
                    if (data.length > 0 && !cloneId) setNodeId(data[0].id);
                }
            }),
            listNetworks().then(({data}) => {
                if (data) setNetworks(data);
            }),
            fetchReleaseVersions().then((vs) => {
                setVersions(vs);
                if (vs.length > 0) setMcVersion(vs[0]);
            }).catch(() => {
            }),
        ];

        const loadClone = cloneId
            ? getServer({path: {id: cloneId}}).then(({data}) => {
                if (!data) return;
                setDisplayName(data.display_name);
                setDescription(data.description ?? "");
                setServerType(data.server_type);
                if (!data.server_type.startsWith("VELOCITY") && !data.server_type.startsWith("BUNGEE") && !data.server_type.startsWith("WATERFALL")) {
                    setMcVersion(data.mc_version === "LATEST" ? versions[0] ?? "" : data.mc_version);
                }
                setItzgImageTag(data.itzg_image_tag || "latest");
                setNodeId(data.node_id);
                setNetworkId(data.network_id ?? "");
                setRamMb(data.memory_mb);
                setCpuShares(data.cpu_shares);
            }).catch(() => {
            })
            : Promise.resolve();

        Promise.all([...loadBase, loadClone]).finally(() => setLoadingData(false));
    }, [cloneId]);

    if (!hasPermission(permissions, "server.create")) {
        return (
            <div className="px-6 py-10 text-center text-text-muted text-sm">
                You do not have permission to create servers.{" "}
                <Link href="/servers" className="text-accent hover:underline">Back to servers</Link>
            </div>
        );
    }

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        if (!nodeId) {
            setError("Please select a node.");
            return;
        }
        setSubmitting(true);
        setError(null);
        try {
            const buildBody = () => ({
                name,
                display_name: displayName || undefined,
                description: description || undefined,
                server_type: serverType,
                mc_version: isProxy ? "LATEST" : mcVersion,
                itzg_image_tag: itzgImageTag || "latest",
                node_id: nodeId,
                network_id: networkId || undefined,
                memory_mb: ramMb,
                cpu_shares: cpuShares,
            });

            const {data, error: apiError} = cloneId
                ? await cloneServer({path: {id: cloneId}, body: {name, display_name: displayName || undefined, description: description || undefined}})
                : await createServer({body: buildBody()});
            if (apiError) {
                setError(apiError.message ?? "Failed to create server");
            } else if (data) {
                router.push(`/servers/${data.id}`);
            }
        } catch {
            setError("Failed to create server");
        } finally {
            setSubmitting(false);
        }
    }

    return (
        <div className="max-w-2xl mx-auto px-6 py-8">
            {/* Header */}
            <div className="mb-8">
                <Link
                    href="/servers"
                    className="inline-flex items-center gap-1.5 text-xs font-heading font-bold uppercase tracking-wider text-text-muted hover:text-text-primary transition-colors mb-4"
                >
                    <ChevronLeft size={11} strokeWidth={2.5}/>
                    Servers
                </Link>
                <h1 className="text-[22px] font-heading font-bold uppercase tracking-wide text-text-primary leading-none">
                    {cloneId ? "Clone Server" : "New Server"}
                </h1>
                {cloneId && (
                    <p className="mt-1.5 text-xs text-text-muted">
                        Cloning configuration from an existing server. Enter a new unique name; the source&apos;s software, resources, environment variables and mods will be copied.
                    </p>
                )}
            </div>

            {error && (
                <div className="mb-6 bg-error/10 border border-error/30 text-error rounded px-3 py-2 text-xs">
                    {error}
                </div>
            )}

            <form onSubmit={(e) => void handleSubmit(e)} className="space-y-4">

                {/* Identity */}
                <SectionHeading>Identity</SectionHeading>

                <div className="bg-surface border border-border rounded p-4 space-y-4">
                    <div>
                        <Label required htmlFor="server-name">Name</Label>
                        <FieldInput
                            id="server-name"
                            value={name}
                            onChange={(e) => setName(e.target.value)}
                            placeholder="survival-1"
                            required
                            pattern="[a-z0-9][a-z0-9\-]*"
                            title="Lowercase letters, numbers and hyphens"
                        />
                        <p className="mt-1 text-xs text-text-muted">Unique slug used internally and for container naming.</p>
                    </div>
                    <div>
                        <Label htmlFor="display-name">Display Name</Label>
                        <FieldInput
                            id="display-name"
                            value={displayName}
                            onChange={(e) => setDisplayName(e.target.value)}
                            placeholder="Survival SMP"
                        />
                    </div>
                    <div>
                        <Label htmlFor="description">Description</Label>
                        <FieldTextarea
                            id="description"
                            value={description}
                            onChange={(e) => setDescription(e.target.value)}
                            placeholder="Optional description"
                        />
                    </div>
                </div>

                {/* Server software */}
                <SectionHeading>Software</SectionHeading>

                <div className="bg-surface border border-border rounded p-4 space-y-4">
                    <div>
                        <Label required htmlFor="server-type">Server Type</Label>
                        <FieldSelect id="server-type" value={serverType} onChange={(e) => setServerType(e.target.value)}>
                            <optgroup label="Game Servers">
                                {GAME_SERVER_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                            </optgroup>
                            <optgroup label="Proxies">
                                {PROXY_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                            </optgroup>
                        </FieldSelect>
                    </div>

                    {!isProxy && (
                        <div>
                            <Label required htmlFor="mc-version">Minecraft Version</Label>
                            {loadingData ? (
                                <div className="h-9 bg-surface-high rounded animate-pulse"/>
                            ) : versions.length > 0 ? (
                                <FieldSelect id="mc-version" value={mcVersion} onChange={(e) => setMcVersion(e.target.value)}>
                                    {versions.map((v) => <option key={v} value={v}>{v}</option>)}
                                </FieldSelect>
                            ) : (
                                <FieldInput
                                    id="mc-version"
                                    value={mcVersion}
                                    onChange={(e) => setMcVersion(e.target.value)}
                                    placeholder="1.21.4"
                                    required
                                />
                            )}
                            <p className="mt-1 text-xs text-text-muted">Release versions from Mojang. Passed to itzg as VERSION env var.</p>
                        </div>
                    )}

                    <div>
                        <Label required htmlFor="itzg-image-tag">itzg Image Tag</Label>
                        <FieldInput
                            id="itzg-image-tag"
                            value={itzgImageTag}
                            onChange={(e) => setItzgImageTag(e.target.value)}
                            placeholder="latest"
                            required
                            list="itzg-tags"
                        />
                        <datalist id="itzg-tags">
                            <option value="latest"/>
                            <option value="java21"/>
                            <option value="java21-jdk"/>
                            <option value="java17"/>
                            <option value="java17-jdk"/>
                            <option value="java11"/>
                            <option value="java8"/>
                        </datalist>
                        <p className="mt-1 text-xs text-text-muted">Docker image tag for itzg/minecraft-server or itzg/mc-proxy.</p>
                    </div>
                </div>

                {/* Infrastructure */}
                <SectionHeading>Infrastructure</SectionHeading>

                <div className="bg-surface border border-border rounded p-4 space-y-4">
                    <div>
                        <Label required htmlFor="node">Node</Label>
                        {loadingData ? (
                            <div className="h-9 bg-surface-high rounded animate-pulse"/>
                        ) : (
                            <FieldSelect id="node" value={nodeId} onChange={(e) => setNodeId(e.target.value)} required>
                                <option value="">Select a node…</option>
                                {nodes.filter((n) => n.status === "ACTIVE").map((n) => (
                                    <option key={n.id} value={n.id}>
                                        {n.display_name} - {n.total_ram_mb ? `${Math.round(n.total_ram_mb / 1024)} GB` : "unknown"} RAM
                                    </option>
                                ))}
                            </FieldSelect>
                        )}
                    </div>

                    <div>
                        <Label htmlFor="network">Network</Label>
                        <FieldSelect id="network" value={networkId} onChange={(e) => setNetworkId(e.target.value)}>
                            <option value="">None</option>
                            {networks.map((n) => (
                                <option key={n.id} value={n.id}>{n.name}</option>
                            ))}
                        </FieldSelect>
                    </div>
                </div>

                {/* Resources */}
                <SectionHeading>Resources</SectionHeading>

                <div className="bg-surface border border-border rounded p-4 space-y-4">
                    <div>
                        <Label required htmlFor="ram-mb">RAM (MB)</Label>
                        <FieldInput
                            id="ram-mb"
                            type="number"
                            value={ramMb}
                            onChange={(e) => setRamMb(Number(e.target.value))}
                            min={512}
                            step={256}
                            required
                        />
                    </div>
                    <div>
                        <Label htmlFor="cpu-shares">CPU Shares</Label>
                        <FieldInput
                            id="cpu-shares"
                            type="number"
                            value={cpuShares}
                            onChange={(e) => setCpuShares(Number(e.target.value))}
                            min={0}
                        />
                        <p className="mt-1 text-xs text-text-muted">Docker CPU share value. 0 = unlimited.</p>
                    </div>
                </div>

                {/* Submit */}
                <div className="flex items-center justify-end gap-3 pt-2">
                    <Link
                        href="/servers"
                        className="px-4 py-2 text-xs font-heading font-bold uppercase tracking-widest text-text-muted hover:text-text-primary transition-colors"
                    >
                        Cancel
                    </Link>
                    <button
                        type="submit"
                        disabled={submitting || loadingData}
                        className="px-5 py-2 rounded bg-accent text-bg text-xs font-heading font-bold uppercase tracking-widest hover:bg-accent-bright transition-colors disabled:opacity-50"
                    >
                        {submitting ? "Creating…" : cloneId ? "Clone Server" : "Create Server"}
                    </button>
                </div>
            </form>
        </div>
    );
}
