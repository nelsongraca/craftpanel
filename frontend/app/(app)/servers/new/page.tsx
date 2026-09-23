"use client";

import {useEffect, useRef, useState} from "react";
import {useRouter, useSearchParams} from "next/navigation";
import Link from "next/link";
import {ChevronLeft} from "lucide-react";
import {cloneServer, createServer, getServer, listNetworks, listNodes} from "@/lib/generated/sdk.gen";
import {useAuth} from "@/lib/auth-context";
import {hasPermission} from "@/lib/permissions";
import {SelectField, TextAreaField, TextField} from "@/components/ui/form-elements";
import {McVersionSelect} from "@/components/ui/mc-version";
import {Skeleton} from "@/components/ui/skeleton";
import type {Network, Node} from "@/lib/types";
import {isCustomType, isPicolimboType, PROXY_TYPES} from "@/lib/server-types";
import {allocatable} from "@/lib/utils/format";

// ── Constants ─────────────────────────────────────────────────────────────────

const GAME_SERVER_TYPES = [
    "CUSTOM",
    "VANILLA",
    "PAPER",
    "FABRIC",
    "FOLIA",
    "FORGE",
    "NEOFORGE",
    "QUILT",
    "SPIGOT",
    "LIMBO",
    "PICOLIMBO",
] as const;

// ── Field component helpers ───────────────────────────────────────────────────

function Label({children, required, htmlFor}: {children: React.ReactNode; required?: boolean; htmlFor?: string}) {
    return (
        <label
            htmlFor={htmlFor}
            className="mb-1.5 block font-heading text-xs font-bold tracking-wider text-text-muted uppercase"
        >
            {children}
            {required && <span className="ml-1 text-error">*</span>}
        </label>
    );
}

function FieldInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
    return <TextField {...props} surface="surface" fieldSize="md" />;
}

function FieldSelect(props: React.SelectHTMLAttributes<HTMLSelectElement>) {
    return <SelectField {...props} surface="surface" fieldSize="md" className="w-full" />;
}

function FieldTextarea(props: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
    return <TextAreaField {...props} surface="surface" fieldSize="md" />;
}

function SectionHeading({children}: {children: React.ReactNode}) {
    return (
        <p className="mt-6 mb-3 font-heading text-xs font-bold tracking-widest text-text-muted uppercase first:mt-0">
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
    const [loadingData, setLoadingData] = useState(true);
    const latestVersionsRef = useRef<string[]>([]);

    const [name, setName] = useState("");
    const [displayName, setDisplayName] = useState("");
    const [description, setDescription] = useState("");
    const [serverType, setServerType] = useState("PAPER");
    const [mcVersion, setMcVersion] = useState("");
    const [itzgImageTag, setItzgImageTag] = useState("latest");
    const [customServerJar, setCustomServerJar] = useState("");
    const [containerListenPort, setContainerListenPort] = useState("");
    const [containerProtocol, setContainerProtocol] = useState("TCP");
    const [disableHealthcheck, setDisableHealthcheck] = useState(false);
    const [forceRedownload, setForceRedownload] = useState(false);
    const [nodeId, setNodeId] = useState("");
    const [networkId, setNetworkId] = useState("");
    const [ramMb, setRamMb] = useState(2048);
    const [cpuCores, setCpuCores] = useState(0);
    const [expiresAt, setExpiresAt] = useState("");

    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const isCustom = isCustomType(serverType);
    const isPicolimbo = isPicolimboType(serverType);
    const canSetExpiry = hasPermission(permissions, "server.expires");

    function toExpiresAtIso(local: string): string | undefined {
        if (!local) return undefined;
        const d = new Date(local);
        return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
    }

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
        ];

        const loadClone = cloneId
            ? getServer({path: {id: cloneId}})
                  .then(({data}) => {
                      if (!data) return;
                      setDisplayName(data.display_name);
                      setDescription(data.description ?? "");
                      setServerType(data.server_type);
                      if (data.server_type !== "CUSTOM" && data.server_type !== "PICOLIMBO") {
                          setMcVersion(
                              data.mc_version === "LATEST" ? (latestVersionsRef.current[0] ?? "") : data.mc_version,
                          );
                      }
                      setItzgImageTag(data.itzg_image_tag || "latest");
                      setCustomServerJar(data.custom_server_jar ?? "");
                      setContainerListenPort(data.container_listen_port ? String(data.container_listen_port) : "");
                      setContainerProtocol(data.container_protocol ?? "TCP");
                      setDisableHealthcheck(data.disable_healthcheck ?? false);
                      setForceRedownload(data.force_redownload ?? false);
                      setNodeId(data.node_id);
                      setNetworkId(data.network_id ?? "");
                      setRamMb(data.memory_mb);
                      setCpuCores(data.cpu_limit_millicores / 1000);
                  })
                  .catch(() => {})
            : Promise.resolve();

        Promise.all([...loadBase, loadClone]).finally(() => setLoadingData(false));
    }, [cloneId]);

    if (!hasPermission(permissions, "server.create")) {
        return (
            <div className="px-6 py-10 text-center text-sm text-text-muted">
                You do not have permission to create servers.{" "}
                <Link href="/servers" className="text-accent hover:underline">
                    Back to servers
                </Link>
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
                mc_version: isCustom || isPicolimbo ? "LATEST" : mcVersion,
                itzg_image_tag: itzgImageTag || "latest",
                custom_server_jar: isCustom ? customServerJar || undefined : undefined,
                container_listen_port: isCustom && containerListenPort ? Number(containerListenPort) : undefined,
                container_protocol: isCustom ? containerProtocol : undefined,
                disable_healthcheck: isCustom ? disableHealthcheck : undefined,
                force_redownload: isCustom ? forceRedownload : undefined,
                node_id: nodeId,
                network_id: networkId || undefined,
                memory_mb: ramMb,
                cpu_limit_millicores: Math.round(cpuCores * 1000),
                expires_at: canSetExpiry ? toExpiresAtIso(expiresAt) : undefined,
            });

            const {data, error: apiError} = cloneId
                ? await cloneServer({
                      path: {id: cloneId},
                      body: {name, display_name: displayName || undefined, description: description || undefined},
                  })
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
        <div className="mx-auto max-w-2xl px-6 py-8">
            {/* Header */}
            <div className="mb-8">
                <Link
                    href="/servers"
                    className="mb-4 inline-flex items-center gap-1.5 font-heading text-xs font-bold tracking-wider text-text-muted uppercase transition-colors hover:text-text-primary"
                >
                    <ChevronLeft size={11} strokeWidth={2.5} />
                    Servers
                </Link>
                <h1 className="font-heading text-[22px] leading-none font-bold tracking-wide text-text-primary uppercase">
                    {cloneId ? "Clone Server" : "New Server"}
                </h1>
                {cloneId && (
                    <p className="mt-1.5 text-xs text-text-muted">
                        Cloning configuration from an existing server. Enter a new unique name; the source&apos;s
                        software, resources, environment variables and mods will be copied.
                    </p>
                )}
            </div>

            {error && (
                <div className="mb-6 rounded border border-error/30 bg-error/10 px-3 py-2 text-xs text-error">
                    {error}
                </div>
            )}

            <form onSubmit={(e) => void handleSubmit(e)} className="space-y-4">
                {/* Identity */}
                <SectionHeading>Identity</SectionHeading>

                <div className="space-y-4 rounded border border-border bg-surface p-4">
                    <div>
                        <Label required htmlFor="server-name">
                            Name
                        </Label>
                        <FieldInput
                            id="server-name"
                            value={name}
                            onChange={(e) => setName(e.target.value)}
                            placeholder="survival-1"
                            required
                            maxLength={63}
                            pattern="[a-z0-9][a-z0-9\-]*"
                            title="Lowercase letters, numbers and hyphens, up to 63 characters"
                        />
                        <p className="mt-1 text-xs text-text-muted">
                            Unique slug used internally, for container naming and as the server hostname.
                        </p>
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

                <div className="space-y-4 rounded border border-border bg-surface p-4">
                    <div>
                        <Label required htmlFor="server-type">
                            Server Type
                        </Label>
                        <FieldSelect
                            id="server-type"
                            value={serverType}
                            onChange={(e) => setServerType(e.target.value)}
                        >
                            <optgroup label="Game Servers">
                                {GAME_SERVER_TYPES.map((t) => (
                                    <option key={t} value={t}>
                                        {t}
                                    </option>
                                ))}
                            </optgroup>
                            <optgroup label="Proxies">
                                {PROXY_TYPES.map((t) => (
                                    <option key={t} value={t}>
                                        {t}
                                    </option>
                                ))}
                            </optgroup>
                        </FieldSelect>
                    </div>

                    {!isCustom && !isPicolimbo && (
                        <div>
                            <Label required htmlFor="mc-version">
                                Minecraft Version
                            </Label>
                            <McVersionSelect
                                id="mc-version"
                                value={mcVersion}
                                onChange={setMcVersion}
                                placeholder="1.21.4"
                                required
                                onLoaded={(vs) => {
                                    latestVersionsRef.current = vs;
                                    setMcVersion((prev) => prev || vs[0] || prev);
                                }}
                            />
                            <p className="mt-1 text-xs text-text-muted">
                                Release versions from Mojang. Passed to itzg as VERSION env var.
                            </p>
                        </div>
                    )}

                    {isCustom && (
                        <>
                            <div>
                                <Label required htmlFor="custom-server-jar">
                                    Custom Server Jar
                                </Label>
                                <FieldInput
                                    id="custom-server-jar"
                                    value={customServerJar}
                                    onChange={(e) => setCustomServerJar(e.target.value)}
                                    placeholder="/data/server.jar or https://example.com/server.jar"
                                    required
                                />
                                <p className="mt-1 text-xs text-text-muted">
                                    Absolute path in the data volume (e.g. /data/MyServer.jar) or download URL. Passed
                                    to itzg as CUSTOM_SERVER.
                                </p>
                            </div>
                            <div className="grid grid-cols-2 gap-4">
                                <div>
                                    <Label htmlFor="container-listen-port">Container Port</Label>
                                    <FieldInput
                                        id="container-listen-port"
                                        type="number"
                                        value={containerListenPort}
                                        onChange={(e) => setContainerListenPort(e.target.value)}
                                        placeholder="25565"
                                        min={1}
                                        max={65535}
                                    />
                                    <p className="mt-1 text-xs text-text-muted">
                                        Internal port your server listens on. Empty = 25565.
                                    </p>
                                </div>
                                <div>
                                    <Label htmlFor="container-protocol">Protocol</Label>
                                    <FieldSelect
                                        id="container-protocol"
                                        value={containerProtocol}
                                        onChange={(e) => setContainerProtocol(e.target.value)}
                                    >
                                        <option value="TCP">TCP</option>
                                        <option value="UDP">UDP</option>
                                    </FieldSelect>
                                    <p className="mt-1 text-xs text-text-muted">
                                        UDP servers cannot be routed by mc-router.
                                    </p>
                                </div>
                            </div>
                            <div className="flex flex-wrap gap-4">
                                <label className="flex items-center gap-2 text-xs text-text-primary">
                                    <input
                                        type="checkbox"
                                        className="h-4 w-4"
                                        checked={disableHealthcheck}
                                        onChange={(e) => setDisableHealthcheck(e.target.checked)}
                                    />
                                    Disable healthcheck
                                </label>
                                <label className="flex items-center gap-2 text-xs text-text-primary">
                                    <input
                                        type="checkbox"
                                        className="h-4 w-4"
                                        checked={forceRedownload}
                                        onChange={(e) => setForceRedownload(e.target.checked)}
                                    />
                                    Force redownload on start
                                </label>
                            </div>
                        </>
                    )}

                    <div>
                        <Label required htmlFor="itzg-image-tag">
                            itzg Image Tag
                        </Label>
                        <FieldInput
                            id="itzg-image-tag"
                            value={itzgImageTag}
                            onChange={(e) => setItzgImageTag(e.target.value)}
                            placeholder="latest"
                            required
                            list="itzg-tags"
                        />
                        <datalist id="itzg-tags">
                            <option value="latest" />
                            <option value="java21" />
                            <option value="java21-jdk" />
                            <option value="java17" />
                            <option value="java17-jdk" />
                            <option value="java11" />
                            <option value="java8" />
                        </datalist>
                        <p className="mt-1 text-xs text-text-muted">
                            Docker image tag for itzg/minecraft-server or itzg/mc-proxy.
                        </p>
                    </div>
                </div>

                {/* Infrastructure */}
                <SectionHeading>Infrastructure</SectionHeading>

                <div className="space-y-4 rounded border border-border bg-surface p-4">
                    <div>
                        <Label required htmlFor="node">
                            Node
                        </Label>
                        {loadingData ? (
                            <Skeleton className="h-9 bg-surface-high" />
                        ) : (
                            <FieldSelect id="node" value={nodeId} onChange={(e) => setNodeId(e.target.value)} required>
                                <option value="">Select a node…</option>
                                {nodes
                                    .filter((n) => n.status === "ACTIVE")
                                    .map((n) => {
                                        const ram = allocatable(n.total_ram_mb, n.reserved_ram_mb);
                                        return (
                                            <option key={n.id} value={n.id}>
                                                {n.display_name} - {ram ? `${Math.round(ram / 1024)} GB` : "unknown"}{" "}
                                                RAM
                                            </option>
                                        );
                                    })}
                            </FieldSelect>
                        )}
                    </div>

                    <div>
                        <Label htmlFor="network">Network</Label>
                        <FieldSelect id="network" value={networkId} onChange={(e) => setNetworkId(e.target.value)}>
                            <option value="">None</option>
                            {networks.map((n) => (
                                <option key={n.id} value={n.id}>
                                    {n.name}
                                </option>
                            ))}
                        </FieldSelect>
                    </div>
                </div>

                {/* Resources */}
                <SectionHeading>Resources</SectionHeading>

                <div className="space-y-4 rounded border border-border bg-surface p-4">
                    <div>
                        <Label required htmlFor="ram-mb">
                            RAM (MB)
                        </Label>
                        <FieldInput
                            id="ram-mb"
                            type="number"
                            value={ramMb}
                            onChange={(e) => setRamMb(Number(e.target.value))}
                            min={64}
                            step={64}
                            required
                        />
                    </div>
                    <div>
                        <Label htmlFor="cpu-cores">CPU Limit (cores)</Label>
                        <FieldInput
                            id="cpu-cores"
                            type="number"
                            value={cpuCores}
                            onChange={(e) => setCpuCores(Number(e.target.value))}
                            min={0}
                            step="any"
                        />
                        <p className="mt-1 text-xs text-text-muted">Hard CPU cap in cores. 0 = unlimited.</p>
                    </div>
                </div>

                {canSetExpiry && (
                    <>
                        <SectionHeading>Expiration</SectionHeading>
                        <div className="space-y-4 rounded border border-border bg-surface p-4">
                            <div>
                                <Label htmlFor="expires-at">Expires At</Label>
                                <FieldInput
                                    id="expires-at"
                                    type="datetime-local"
                                    value={expiresAt}
                                    onChange={(e) => setExpiresAt(e.target.value)}
                                />
                                <p className="mt-1 text-xs text-text-muted">
                                    After this date, the server can no longer be started and running instances will be
                                    stopped.
                                </p>
                            </div>
                        </div>
                    </>
                )}

                {/* Submit */}
                <div className="flex items-center justify-end gap-3 pt-2">
                    <Link
                        href="/servers"
                        className="px-4 py-2 font-heading text-xs font-bold tracking-widest text-text-muted uppercase transition-colors hover:text-text-primary"
                    >
                        Cancel
                    </Link>
                    <button
                        type="submit"
                        disabled={submitting || loadingData}
                        className="rounded bg-accent px-5 py-2 font-heading text-xs font-bold tracking-widest text-bg uppercase transition-colors hover:bg-accent-bright disabled:opacity-50"
                    >
                        {submitting ? "Creating…" : cloneId ? "Clone Server" : "Create Server"}
                    </button>
                </div>
            </form>
        </div>
    );
}
