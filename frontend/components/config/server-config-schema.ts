import type {Section} from "./field-types";

export const SECTIONS: Section[] = [
    {
        title: "Gameplay",
        collapsible: true,
        fields: [
            {key: "DIFFICULTY", label: "Difficulty", type: "select", options: ["peaceful", "easy", "normal", "hard"], serverPropertiesMapped: true},
            {key: "MODE", label: "Default Game Mode", type: "select", options: ["survival", "creative", "adventure", "spectator"], serverPropertiesMapped: true},
            {key: "HARDCORE", label: "Hardcore Mode", type: "toggle", serverPropertiesMapped: true},
            {key: "PVP", label: "PvP", type: "toggle", serverPropertiesMapped: true},
            {key: "ALLOW_NETHER", label: "Allow Nether", type: "toggle", serverPropertiesMapped: true},
            {key: "FORCE_GAMEMODE", label: "Force Game Mode on Join", type: "toggle", serverPropertiesMapped: true},
            {key: "SPAWN_ANIMALS", label: "Spawn Animals", type: "toggle", serverPropertiesMapped: true},
            {key: "SPAWN_MONSTERS", label: "Spawn Monsters", type: "toggle", serverPropertiesMapped: true},
            {key: "SPAWN_NPCS", label: "Spawn Villagers", type: "toggle", serverPropertiesMapped: true},
            {key: "SPAWN_PROTECTION", label: "Spawn Protection Radius", type: "number", serverPropertiesMapped: true},
            {key: "ALLOW_FLIGHT", label: "Allow Flight", type: "toggle", serverPropertiesMapped: true},
        ],
    },
    {
        title: "World",
        collapsible: true,
        fields: [
            {
                key: "SEED",
                label: "World Seed",
                type: "text",
                hint: "Leave blank for a random seed. Changing after creation has no effect on the existing world.",
                serverPropertiesMapped: true,
                omitIfEmpty: true
            },
            {key: "LEVEL", label: "World Save Name", type: "text", serverPropertiesMapped: true},
            {
                key: "LEVEL_TYPE",
                label: "World Type",
                type: "select",
                options: ["DEFAULT", "FLAT", "AMPLIFIED", "BIOMESOP", "LARGEBIOMES", "CUSTOMIZED", "SUNSETSKYLANDS"],
                serverPropertiesMapped: true
            },
            {
                key: "GENERATOR_SETTINGS",
                label: "Generator Settings",
                type: "textarea",
                hint: "Only used when World Type is FLAT.",
                serverPropertiesMapped: true,
                omitIfEmpty: true,
                showWhen: {key: "LEVEL_TYPE", value: "FLAT"}
            },
            {key: "GENERATE_STRUCTURES", label: "Generate Structures", type: "toggle", serverPropertiesMapped: true},
            {key: "MAX_WORLD_SIZE", label: "Max World Size", type: "number", serverPropertiesMapped: true},
            {key: "MAX_BUILD_HEIGHT", label: "Max Build Height", type: "number", serverPropertiesMapped: true, omitIfEmpty: true},
        ],
    },
    {
        title: "Players & Access",
        collapsible: true,
        fields: [
            {key: "MOTD", label: "Message of the Day", type: "text", hint: "Leave blank to let itzg generate its own MOTD.", serverPropertiesMapped: true, omitIfEmpty: true},
            {key: "MAX_PLAYERS", label: "Max Players", type: "number", serverPropertiesMapped: true},
            {key: "ONLINE_MODE", label: "Online Mode", type: "toggle", hint: "Disabling bypasses Mojang authentication.", serverPropertiesMapped: true},
            {key: "ENABLE_WHITELIST", label: "Enable Whitelist", type: "toggle", serverPropertiesMapped: true},
            {key: "WHITELIST", label: "Whitelisted Players", type: "tag-input", hint: "Comma-separated usernames or UUIDs.", serverPropertiesMapped: true, omitIfEmpty: true},
            {key: "OPS", label: "Operator Players", type: "tag-input", hint: "Comma-separated usernames or UUIDs.", serverPropertiesMapped: true, omitIfEmpty: true},
            {key: "PLAYER_IDLE_TIMEOUT", label: "Player Idle Timeout (min)", type: "number", hint: "0 = disabled.", serverPropertiesMapped: true},
            {key: "ENFORCE_SECURE_PROFILE", label: "Enforce Secure Chat", type: "toggle", hint: "1.19+", serverPropertiesMapped: true},
            {key: "PREVENT_PROXY_CONNECTIONS", label: "Prevent Proxy Connections", type: "toggle", serverPropertiesMapped: true},
        ],
    },
    {
        title: "Performance",
        collapsible: true,
        fields: [
            {key: "VIEW_DISTANCE", label: "View Distance (chunks)", type: "number", serverPropertiesMapped: true},
            {key: "SIMULATION_DISTANCE", label: "Simulation Distance (chunks)", type: "number", serverPropertiesMapped: true},
            {key: "MAX_TICK_TIME", label: "Max Tick Time (ms)", type: "number", hint: "-1 to disable watchdog.", serverPropertiesMapped: true},
            {key: "NETWORK_COMPRESSION_THRESHOLD", label: "Compression Threshold (bytes)", type: "number", hint: "-1 to disable.", serverPropertiesMapped: true},
            {key: "SYNC_CHUNK_WRITES", label: "Sync Chunk Writes", type: "toggle", serverPropertiesMapped: true},
        ],
    },
    {
        title: "Resource Pack",
        collapsible: true,
        fields: [
            {key: "RESOURCE_PACK", label: "Resource Pack URL", type: "text", serverPropertiesMapped: true, omitIfEmpty: true},
            {key: "RESOURCE_PACK_SHA1", label: "Resource Pack SHA1", type: "text", serverPropertiesMapped: true, omitIfEmpty: true},
            {key: "RESOURCE_PACK_ENFORCE", label: "Enforce Resource Pack", type: "toggle", serverPropertiesMapped: true, omitIfEmpty: true, showWhen: {key: "RESOURCE_PACK", nonEmpty: true}},
        ],
    },
    {
        title: "Advanced",
        collapsible: true,
        defaultOpen: false,
        fields: [
            {key: "ENABLE_COMMAND_BLOCK", label: "Enable Command Blocks", type: "toggle", serverPropertiesMapped: true},
            {key: "OP_PERMISSION_LEVEL", label: "Op Permission Level", type: "number", hint: "1\u20134", serverPropertiesMapped: true},
            {key: "FUNCTION_PERMISSION_LEVEL", label: "Function Permission Level", type: "number", serverPropertiesMapped: true},
            {key: "BROADCAST_CONSOLE_TO_OPS", label: "Broadcast Console to Ops", type: "toggle", serverPropertiesMapped: true},
            {key: "ICON", label: "Server Icon URL", type: "text", hint: "Downloaded and scaled by itzg on start.", serverPropertiesMapped: true, omitIfEmpty: true},
            {key: "TZ", label: "Timezone", type: "text", hint: "e.g. Europe/London", serverPropertiesMapped: true},
            {
                key: "CUSTOM_SERVER_PROPERTIES",
                label: "Custom Server Properties",
                type: "textarea",
                hint: "Newline-delimited key=value pairs for plugin-specific properties.",
                serverPropertiesMapped: true,
                omitIfEmpty: true
            },
        ],
    },
    {
        title: "JVM Options",
        collapsible: true,
        fields: [
            {key: "USE_AIKAR_FLAGS", label: "Aikar's GC Flags", type: "toggle", hint: "Recommended for production.", serverPropertiesMapped: false},
            {key: "USE_MEOWICE_FLAGS", label: "MeowIce JVM Flags", type: "toggle", hint: "Aikar's flags updated for Java 21+. Mutually exclusive with Aikar's flags.", serverPropertiesMapped: false},
            {key: "JVM_OPTS", label: "Extra JVM Arguments", type: "text", hint: "Space-delimited raw JVM flags.", serverPropertiesMapped: false, omitIfEmpty: true},
            {key: "JVM_XX_OPTS", label: "Extra JVM -XX Arguments", type: "text", hint: "Space-delimited -XX options only.", serverPropertiesMapped: false, omitIfEmpty: true},
        ],
    },
];

export const SCHEMA_KEYS = new Set(SECTIONS.flatMap((s) => s.fields.map((f) => f.key)));
