import type {
    ServerResponse,
    NodeResponse,
    NetworkResponse,
    MeResponse,
    ModResponse,
    MigrationResponse,
    LoginResponse,
    WsTicketResponse,
} from "@/lib/generated/types.gen";

export const FAKE_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test.sig";
export const FAKE_TICKET = "fake-ws-ticket-abc123";

export const loginResponse: LoginResponse = {
    access_token: FAKE_TOKEN,
    expires_in: 900,
};

export const wsTicketResponse: WsTicketResponse = {
    ticket: FAKE_TICKET,
    expires_in: 30,
};

export const fakeUser: MeResponse = {
    id: "user-1",
    username: "admin",
    email: "admin@craftpanel.test",
    groups: ["Super Admin"],
    permissions: ["*"],
};

const NODE_DEFAULTS = {
    total_ram_mb: 8192,
    total_cpu_shares: 4096,
    allocated_ram_mb: 2048,
    allocated_cpu_shares: 1024,
    system_ram_used_mb: null,
    port_range_start: 25565,
    port_range_end: 25600,
    agent_version: "1.0.0",
    description: null,
    domain_suffix: null,
    dns_zone_id: null,
    dns_domain_suffix: null,
    dns_provider_type: null,
    created_at: "2025-01-01T00:00:00Z",
    last_seen_at: "2025-01-01T00:00:00Z",
};

export const fakeNode: NodeResponse = {
    id: "node-1",
    display_name: "Primary Node",
    hostname: "node1.test",
    public_ip: "1.2.3.4",
    private_ip: "10.0.0.1",
    status: "ACTIVE",
    health: "HEALTHY",
    updated_at: "2025-01-01T00:00:00Z",
    ...NODE_DEFAULTS,
};

export const fakeNode2: NodeResponse = {
    id: "node-2",
    display_name: "Secondary Node",
    hostname: "node2.test",
    public_ip: "1.2.3.5",
    private_ip: "10.0.0.2",
    status: "ACTIVE",
    health: "HEALTHY",
    updated_at: "2025-01-01T00:00:00Z",
    ...NODE_DEFAULTS,
};

export const fakeNetwork: NetworkResponse = {
    id: "net-1",
    name: "default",
    proxy_port: null,
    description: null,
    domain_suffix: null,
    dns_zone_id: null,
    dns_domain_suffix: null,
    dns_provider_type: null,
    server_count: 4,
    created_at: "2025-01-01T00:00:00Z",
};

const SERVER_DEFAULTS = {
    description: null,
    server_type: "PAPER",
    mc_version: "1.21.5",
    itzg_image_tag: "1.21.5",
    cpu_shares: 1024,
    exposed_externally: false,
    public_subdomain: null,
    is_migrating: false,
    needs_recreate: false,
    config_mode: "MANUAL",
    stop_command: "stop",
    last_player_count: null,
    last_player_names: null,
    custom_hostname: null,
    canonical_hostname: null,
    created_at: "2025-01-01T00:00:00Z",
    updated_at: "2025-01-01T00:00:00Z",
} satisfies Partial<ServerResponse>;

export const fakeServers: ServerResponse[] = [
    {
        id: "srv-1",
        name: "survival",
        display_name: "Survival World",
        description: "Main survival server",
        server_type: "PAPER",
        mc_version: "1.21.5",
        itzg_image_tag: "1.21.5",
        status: "HEALTHY",
        node_id: "node-1",
        network_id: "net-1",
        host_port: 25565,
        memory_mb: 2048,
        cpu_shares: 1024,
        exposed_externally: true,
        public_subdomain: "survival",
        is_migrating: false,
        needs_recreate: false,
        config_mode: "MANUAL",
        stop_command: "stop",
        last_player_count: 3,
        last_player_names: ["Steve", "Alex"],
        custom_hostname: null,
        canonical_hostname: null,
        created_at: "2025-01-01T00:00:00Z",
        updated_at: "2025-01-01T00:00:00Z",
    },
    {
        ...SERVER_DEFAULTS,
        id: "srv-2",
        name: "creative",
        display_name: "Creative World",
        status: "STOPPED",
        node_id: "node-1",
        network_id: "net-1",
        host_port: 25566,
        memory_mb: 1024,
    },
    {
        ...SERVER_DEFAULTS,
        id: "srv-3",
        name: "skyblock",
        display_name: "Skyblock",
        status: "UNHEALTHY",
        node_id: "node-1",
        network_id: null,
        host_port: 25567,
        memory_mb: 1024,
    },
    {
        ...SERVER_DEFAULTS,
        id: "srv-4",
        name: "lobby",
        display_name: "Lobby",
        status: "STARTING",
        node_id: "node-2",
        network_id: "net-1",
        host_port: 25568,
        memory_mb: 512,
        cpu_shares: 256,
    },
];

export const fakeHealthyServer: ServerResponse = fakeServers[0];

// mods-tab reads res.data.mods — the bucket key is "mods"
export const fakeMods: Record<string, ModResponse[]> = {
    mods: [
        {
            id: "mod-1",
            server_id: "srv-1",
            modrinth_project_id: "worldedit-id",
            display_name: "WorldEdit",
            pin_strategy: "LATEST",
            pinned_version_id: null,
            installed_version_id: "we-7.3.0",
            created_at: "2025-01-01T00:00:00Z",
            updated_at: "2025-01-01T00:00:00Z",
        },
    ],
};

export const fakeModSearchHits = [
    {
        project_id: "dynmap-id",
        title: "Dynmap",
        description: "A Google Maps-like map for Minecraft servers.",
        author: "webbukkit",
        downloads: 1_200_000,
    },
    {
        project_id: "essentialsx-id",
        title: "EssentialsX",
        description: "The essential plugin for Spigot servers.",
        author: "EssentialsX",
        downloads: 3_000_000,
    },
];

export const fakeMigration: MigrationResponse = {
    id: "mig-1",
    server_id: "srv-1",
    source_node_id: "node-1",
    target_node_id: "node-2",
    status: "SYNCING",
    steps: [],
    created_at: "2025-06-20T10:00:00Z",
    completed_at: null,
};

export const dashboardSnapshot = {
    type: "snapshot",
    payload: {
        servers: fakeServers.map((s) => ({
            id: s.id,
            status: s.status,
            player_count: s.last_player_count ?? 0,
        })),
        nodes: [fakeNode, fakeNode2].map((n) => ({
            id: n.id,
            health: n.health,
            status: n.status,
        })),
    },
};
