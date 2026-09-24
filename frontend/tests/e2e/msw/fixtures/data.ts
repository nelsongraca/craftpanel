import type {
    ServerResponse,
    NodeResponse,
    NetworkResponse,
    MeResponse,
    ModResponse,
    MigrationResponse,
    LoginResponse,
    WsTicketResponse,
    BackupResponse,
    BackupScheduleResponse,
    EnvVarItem,
    ProxySettingsResponse,
    ProxyBackendItem,
    AlertThresholdResponse,
    AlertEventResponse,
    GroupResponse,
    UserResponse,
    AssignmentResponse,
    SystemSettingsResponse,
    NodeMetricsResponse,
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
    server_permissions: {},
    network_permissions: {},
    totp_enabled: false,
};

const NODE_DEFAULTS = {
    total_ram_mb: 8192,
    total_cpu_millicores: 4096,
    allocated_ram_mb: 2048,
    allocated_cpu_millicores: 1024,
    system_ram_used_mb: null,
    system_cpu_percent: null,
    reserved_ram_mb: 1024,
    reserved_cpu_millicores: 1024,
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
    server_count: 4,
    created_at: "2025-01-01T00:00:00Z",
};

const SERVER_DEFAULTS = {
    description: null,
    server_type: "PAPER",
    mc_version: "1.21.5",
    itzg_image_tag: "1.21.5",
    expires_at: null,
    disabled: false,
    cpu_limit_millicores: 1024,
    exposed_externally: false,
    public_subdomain: null,
    is_migrating: false,
    restart_pending: false,
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
        expires_at: null,
        status: "HEALTHY",
        node_id: "node-1",
        network_id: "net-1",
        host_port: 25565,
        memory_mb: 2048,
        cpu_limit_millicores: 1024,
        exposed_externally: true,
        public_subdomain: "survival",
        is_migrating: false,
        restart_pending: false,
        disabled: false,
        config_mode: "MANAGED",
        stop_command: "stop",
        last_player_count: 3,
        last_player_names: ["Steve", "Alex"],
        custom_hostname: null,
        canonical_hostname: "survival.mc.example.com",
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
        cpu_limit_millicores: 256,
    },
    {
        ...SERVER_DEFAULTS,
        id: "srv-5",
        name: "retired",
        display_name: "Retired World",
        status: "STOPPED",
        node_id: "node-1",
        network_id: null,
        host_port: 25569,
        memory_mb: 1024,
        expires_at: "2024-01-01T00:00:00Z",
    },
    {
        ...SERVER_DEFAULTS,
        id: "srv-6",
        name: "suspended",
        display_name: "Suspended World",
        status: "STOPPED",
        node_id: "node-1",
        network_id: null,
        host_port: 25570,
        memory_mb: 1024,
        disabled: true,
    },
    {
        ...SERVER_DEFAULTS,
        id: "srv-proxy",
        name: "velocity",
        display_name: "Velocity Proxy",
        server_type: "VELOCITY",
        mc_version: "1.21.5",
        itzg_image_tag: "latest",
        status: "HEALTHY",
        node_id: "node-1",
        network_id: "net-1",
        host_port: 25577,
        memory_mb: 1024,
        config_mode: "MANAGED",
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
            enabled: true,
            created_at: "2025-01-01T00:00:00Z",
            updated_at: "2025-01-01T00:00:00Z",
        },
    ],
};

export const fakeModSearchHits = [
    {
        project_id: "dynmap-id",
        slug: "dynmap",
        title: "Dynmap",
        description: "A Google Maps-like map for Minecraft servers.",
        author: "webbukkit",
        downloads: 1_200_000,
    },
    {
        project_id: "essentialsx-id",
        slug: "essentialsx",
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

// ── Backups ────────────────────────────────────────────────────────────────

export const fakeBackups: BackupResponse[] = [
    {
        id: "backup-1",
        server_id: "srv-1",
        node_id: "node-1",
        trigger: "MANUAL",
        status: "COMPLETED",
        file_path: "/backups/backup-1.tar.gz",
        size_bytes: 1048576,
        error_message: null,
        created_at: "2025-06-20T10:00:00Z",
        completed_at: "2025-06-20T10:05:00Z",
    },
    {
        id: "backup-2",
        server_id: "srv-1",
        node_id: "node-1",
        trigger: "SCHEDULED",
        status: "FAILED",
        file_path: null,
        size_bytes: null,
        error_message: "Disk full",
        created_at: "2025-06-21T02:00:00Z",
        completed_at: "2025-06-21T02:01:00Z",
    },
];

export const fakeBackupSchedule: BackupScheduleResponse = {
    backup_schedule: "0 2 * * *",
    backup_max_count: 10,
};

// ── Env vars / config ──────────────────────────────────────────────────────

export const fakeEnvVars: EnvVarItem[] = [
    {key: "DIFFICULTY", value: "normal"},
    {key: "PVP", value: "true"},
    {key: "CUSTOM_EXTRA", value: "hello"},
];

export const fakeProxySettings: ProxySettingsResponse = {
    motd: "A Minecraft Proxy",
    max_players: 20,
    forwarding_mode: "MODERN",
    proxy_protocol: false,
    forwarding_warnings: [],
};

export const fakeProxyBackends: ProxyBackendItem[] = [
    {
        id: "backend-1",
        backend_server_id: "srv-2",
        backend_name: "creative",
        order: 0,
    },
];

// ── Alerts ─────────────────────────────────────────────────────────────────

export const fakeAlertThresholds: AlertThresholdResponse[] = [
    {
        id: "threshold-1",
        scope_type: "NODE",
        scope_id: "node-1",
        metric: "cpu_percent",
        threshold_value: 90,
        threshold_state: null,
        created_at: "2025-01-01T00:00:00Z",
    },
];

export const fakeAlertEvents: AlertEventResponse[] = [
    {
        id: "event-1",
        threshold_id: "threshold-1",
        message: "node-1 CPU above 90%",
        fired_at: "2025-06-20T10:00:00Z",
        resolved_at: null,
    },
];

// ── Users / groups ─────────────────────────────────────────────────────────

export const fakeUsers: UserResponse[] = [
    {
        id: "user-1",
        username: "admin",
        email: "admin@craftpanel.test",
        is_active: true,
        created_at: "2025-01-01T00:00:00Z",
        must_change_password: false,
        groups: ["Super Admin"],
        last_login_at: "2025-06-20T10:00:00Z",
    },
    {
        id: "user-2",
        username: "viewer",
        email: "viewer@craftpanel.test",
        is_active: false,
        created_at: "2025-01-02T00:00:00Z",
        must_change_password: false,
        groups: ["Viewer"],
        last_login_at: null,
    },
];

export const fakeGroups: GroupResponse[] = [
    {
        id: "group-1",
        name: "Super Admin",
        is_system: true,
        permissions: ["*"],
        created_at: "2025-01-01T00:00:00Z",
    },
    {
        id: "group-2",
        name: "Operator",
        is_system: true,
        permissions: ["server.restart", "server.console", "server.view", "server.backup"],
        created_at: "2025-01-01T00:00:00Z",
    },
];

export const fakeAssignments: AssignmentResponse[] = [
    {
        id: "assign-1",
        group_id: "group-1",
        scope_type: "GLOBAL",
        scope_id: null,
    },
];

export const fakeSystemSettings: SystemSettingsResponse = {
    settings: {
        app_name: "CraftPanel",
        app_logo: null,
        metric_retention_days: 30,
        default_backup_max_count: 10,
        default_port_range_start: 25565,
        default_port_range_end: 25600,
        restart_max_attempts: 3,
        restart_window_seconds: 300,
        rate_limit_login_per_minute: 10,
        rate_limit_refresh_per_minute: 30,
        rate_limit_totp_verify_per_minute: 10,
        image_minecraft: "itzg/minecraft-server",
        image_proxy: "itzg/mc-proxy",
        console_tail_lines: 200,
        dns_domain_suffix: null,
        dns_zone_id: null,
    },
    updated_at: "2025-01-01T00:00:00Z",
    updated_by: "user-1",
};

export const fakeNodeMetrics: NodeMetricsResponse = {
    timestamps: [new Date(Date.now() - 10 * 60_000).toISOString(), new Date(Date.now() - 5 * 60_000).toISOString()],
    cpu_percent: [10, 20],
    ram_used_mb: [1024, 2048],
    ram_total_mb: [8192, 8192],
    net_in_bytes: [1000, 2000],
    net_out_bytes: [500, 1000],
    disk_used_bytes: [1073741824, 1073741824],
    disk_total_bytes: [10737418240, 10737418240],
};

export const fakeMigrationWithSteps: MigrationResponse = {
    id: "mig-1",
    server_id: "srv-1",
    source_node_id: "node-1",
    target_node_id: "node-2",
    status: "COMPLETED",
    steps: [
        {
            step_number: 1,
            description: "Syncing world data",
            status: "SUCCESS",
            started_at: "2025-06-20T10:00:00Z",
            completed_at: "2025-06-20T10:01:00Z",
            error_message: null,
        },
        {
            step_number: 2,
            description: "Cutting over DNS",
            status: "SUCCESS",
            started_at: "2025-06-20T10:01:00Z",
            completed_at: "2025-06-20T10:02:00Z",
            error_message: null,
        },
    ],
    created_at: "2025-06-20T10:00:00Z",
    completed_at: "2025-06-20T10:02:00Z",
};

export const fakeModrinthVersions = [
    {
        id: "we-7.3.0",
        version_number: "7.3.0",
        name: "WorldEdit 7.3.0",
        version_type: "release",
        date_published: "2025-01-01T00:00:00Z",
    },
    {
        id: "we-7.2.0",
        version_number: "7.2.0",
        name: "WorldEdit 7.2.0",
        version_type: "beta",
        date_published: "2024-12-01T00:00:00Z",
    },
];

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
