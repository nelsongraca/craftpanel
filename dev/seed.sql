-- CraftPanel dev seed data
-- Passwords: admin@craftpanel.dev → "admin", all others → "password"
-- Run after the master has started (which creates the schema).

-- Users
INSERT INTO users (id, username, email, password_hash, is_active)
VALUES ('00000000-0000-0000-0000-000000000001', 'superadmin', 'admin@craftpanel.dev', '$argon2id$v=19$m=65536,t=3,p=4$c2FsdGFkbWluYWRtaW4wMQ$3rE0bYUAYMjXcC1MsezIzikDvSViyqsKKokCQn9QS1s', true),
       ('00000000-0000-0000-0000-000000000002', 'serveradmin', 'serveradmin@craftpanel.dev', '$argon2id$v=19$m=65536,t=3,p=4$c2FsdHVzZXJwYXNzd29yZA$30QlFoQ0LZCe9EBIXiPFoagE1C2JVXp3AQFncnOFTxc', true),
       ('00000000-0000-0000-0000-000000000003', 'operator', 'operator@craftpanel.dev', '$argon2id$v=19$m=65536,t=3,p=4$c2FsdHVzZXJwYXNzd29yZA$30QlFoQ0LZCe9EBIXiPFoagE1C2JVXp3AQFncnOFTxc', true),
       ('00000000-0000-0000-0000-000000000004', 'viewer', 'viewer@craftpanel.dev', '$argon2id$v=19$m=65536,t=3,p=4$c2FsdHVzZXJwYXNzd29yZA$30QlFoQ0LZCe9EBIXiPFoagE1C2JVXp3AQFncnOFTxc',
        true) ON CONFLICT DO NOTHING;

-- Assign users to system groups (groups were seeded by the app on startup)
INSERT INTO user_group_assignments (id, user_id, group_id, scope_type, scope_id)
SELECT gen_random_uuid(),
       u.id,
       g.id,
       'GLOBAL',
       NULL
FROM (VALUES ('00000000-0000-0000-0000-000000000001', 'Super Admin'),
             ('00000000-0000-0000-0000-000000000002', 'Server Admin'),
             ('00000000-0000-0000-0000-000000000003', 'Operator'),
             ('00000000-0000-0000-0000-000000000004', 'Viewer')) AS mapping(user_id, group_name)
         JOIN users u ON u.id = mapping.user_id::uuid
JOIN groups g
ON g.name = mapping.group_name
    ON CONFLICT DO NOTHING;

-- Node
INSERT INTO nodes (id, display_name, hostname, public_ip, private_ip, token_hash, status, total_ram_mb, total_cpu_shares, port_range_start, port_range_end, data_path, agent_version)
VALUES ('10000000-0000-0000-0000-000000000001', 'Primary Node', 'node1.local', '192.168.1.10', '10.0.0.10',
        'a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3',
        'ACTIVE', 32768, 1024, 25570, 26070, '/data', '1.0.0') ON CONFLICT DO NOTHING;

-- Server network
INSERT INTO server_networks (id, name, type, proxy_type, proxy_port, description)
VALUES ('20000000-0000-0000-0000-000000000001', 'Main Network', 'PROXY', 'VELOCITY', 25565, 'Primary dev network') ON CONFLICT DO NOTHING;

-- Servers
INSERT INTO servers (id, name, display_name, description, node_id, network_id, server_type, status, host_port, memory_mb, cpu_shares, exposed_externally, config_mode)
VALUES ('30000000-0000-0000-0000-000000000001', 'survival', 'Survival', 'Main survival world', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'PAPER', 'HEALTHY',
        25571, 4096, 0, false, 'MANAGED'),
       ('30000000-0000-0000-0000-000000000002', 'creative', 'Creative', 'Creative building', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'PAPER', 'STOPPED', 25572,
        2048, 0, false, 'MANAGED'),
       ('30000000-0000-0000-0000-000000000003', 'minigames', 'Minigames', 'Mini-game hub', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'PAPER', 'STOPPED', 25573,
        2048, 0, false, 'MANAGED'),
       ('30000000-0000-0000-0000-000000000004', 'vanilla', 'Vanilla', 'Vanilla test server', '10000000-0000-0000-0000-000000000001', NULL, 'VANILLA', 'UNHEALTHY', 25574, 1024, 0, false,
        'MANAGED') ON CONFLICT DO NOTHING;

-- Env vars for survival server
INSERT INTO server_env_vars (id, server_id, key, value)
VALUES (gen_random_uuid(), '30000000-0000-0000-0000-000000000001', 'DIFFICULTY', 'hard'),
       (gen_random_uuid(), '30000000-0000-0000-0000-000000000001', 'MAX_PLAYERS', '50'),
       (gen_random_uuid(), '30000000-0000-0000-0000-000000000001', 'VIEW_DISTANCE', '10') ON CONFLICT DO NOTHING;
