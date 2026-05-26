# CraftPanel — Agent Context

Self-hosted multi-user multi-node Minecraft server management platform.

## Base Instructions
Always use Context7 when I need library/API documentation, code generation, setup or configuration steps without me having to explicitly ask.
If an idea MCP tool is adequate consider using it most relevant ones, reading files, searching, and running code analysis.

Before starting any task, assess complexity:
- Straightforward implementation (schema, CRUD endpoints, mechanical wiring):
  proceed directly with Sonnet
- Ambiguous architecture, cross-module coordination, new proto changes,
  state machine logic: pause and flag for advisor review before proceeding

## Module Structure

```
craftpanel/
├── master/        # Kotlin + Ktor REST/WebSocket backend, gRPC server, PostgreSQL
├── agent/         # Minimal Kotlin gRPC client, manages Docker containers on remote nodes
├── frontend/      # Next.js 16 frontend
├── proto/         # Shared protobuf definitions (consumed by master and agent)
├── docs/          # MkDocs documentation
└── CLAUDE.md
```

## Key Versions

```toml
kotlin = "2.3.20"
ktor = "3.4.0"
exposed = "1.2.0"          # NOTE: 1.0+ uses org.jetbrains.exposed.v1.* imports
grpc = "1.75.0"
grpc-kotlin = "1.4.3"
protobuf = "4.32.0"
coroutines = "1.10.2"
hikari = "6.3.0"
postgresql = "42.7.5"
jdk = "25"
node = "22.14.0"
next = "16"
```

All versions are pinned in `gradle/libs.versions.toml`. Never hardcode dependency versions inline.

## Architecture Rules

- **Host builds only** — Docker packages pre-built output, never builds inside containers
- **No Makefile** — everything is a Gradle task
- **No multi-stage Dockerfiles** — single-stage, COPY pre-built artifacts in
- **No deployment secrets in the database** — credentials live in config files or mounted secrets
- **No Keycloak** — JWT auth is rolled natively in Ktor

## Build & Docker

### JVM modules (master, agent)
```bash
./gradlew :master:installDist      # builds distribution
./gradlew :master:dockerBuildImage # packages into Docker image
```
Dockerfile COPYs `installDist` output into `eclipse-temurin:25-jdk-alpine`.

### Frontend
```bash
./gradlew :frontend:assembleFrontend  # runs pnpm build via frontend-gradle-plugin
./gradlew :frontend:dockerBuildImage  # packages into Docker image
```
Dockerfile COPYs `.next/standalone`, `.next/static`, and `public/` into `node:22-alpine`.

### API codegen
```bash
./gradlew :master:generateOpenApiSpec   # boots testApplication, writes openapi.json at repo root
./gradlew :frontend:generateApiTypes    # runs @hey-api/openapi-ts → frontend/lib/generated/
```
Both run automatically as part of `:frontend:assembleFrontend`. `lib/generated/` is gitignored.
The spec task is in `master/src/test/kotlin/.../OpenApiSpecTask.kt` and excluded from `:master:test`.

### Aggregation tasks
```bash
./gradlew dockerBuildAll   # builds all three images
./gradlew dockerPushAll    # pushes all three images
```

### Image naming
```bash
./gradlew dockerBuildAll -PimageRegistry=ghcr.io/nelsongraca -PimageVersion=1.0.0
```

## CI

GitHub Actions builds images and pushes to GHCR (`ghcr.io/nelsongraca`).
Registry and version passed via `-PimageRegistry` and `-PimageVersion` Gradle properties.
CI config not yet written.

## gRPC

Proto files live at repo root `/proto/`, shared by master and agent.
Two services:

- **ControlService** — persistent bidirectional stream, agent-initiated, lives for the lifetime of the connection. Handles container lifecycle commands, backups, migration, metrics, player updates.
- **DataService** — on-demand per-operation connections. Handles console streaming (bidirectional), file operations, file upload/download.

Agent sends `NodeStateSnapshot` as the **first message** on every (re)connect so master can reconcile DB state before issuing commands.

Node authentication over gRPC:
- **First registration**: agent calls `RegisterNode` with bootstrap token → master returns 256-bit node key
- **Subsequent connections**: agent calls `IdentifyNode` with node key → master returns `ACTIVE`, `PENDING`, or `REJECTED`
- Node keys stored as SHA-256 hashes in DB, never raw
- TLS on gRPC channel; node key is the auth mechanism (not mTLS client certs)

### Agent registry (`ControlServiceImpl`)
`ConcurrentHashMap<String, SendChannel<MasterMessage>>` tracks connected agents by nodeId.
Registered on first message inside `channelFlow`, deregistered in `finally` using `remove(nodeId, channel)` (atomic — safe against reconnect races).
`sendToNode(nodeId, msg)` uses `trySend` — returns `false` if agent disconnected.

## Auth (REST/WebSocket)

### JWT

Short-lived access tokens, HS256 signed, **15 minute lifetime**.

```json
{
  "sub": "<user-uuid>",
  "name": "John Doe",
  "email": "john@example.com",
  "groups": ["Server Admin"],
  "iat": 1234567890,
  "exp": 1234568790
}
```

- **No permission nodes in JWT** — permissions always resolved from DB per request
- `is_active` checked as part of every permission resolution query
- Groups in token are informational/UI only

### Refresh tokens

- Long-lived, stored as SHA-256 hashes in DB
- Transmitted as `HttpOnly; Secure; SameSite=Strict; Path=/api/auth` cookies
- Rotated on every use — old token revoked, new one issued
- `logout-all` revokes all tokens for a user

### Password storage

Argon2id, never plaintext.

## Permission System

### Permission nodes

```
system.settings  system.users     system.nodes
server.create    server.delete    server.start      server.stop
server.restart   server.configure server.resources  server.files
server.mods      server.console   server.export     server.backup
server.upgrade   server.migrate   server.view
```

Wildcards supported at runtime (`*`, `server.*`, `system.*`). Only explicit nodes stored in DB.
Permissions are **additive only** — no deny rules.

### Assignment scopes

Permissions are granted via group assignments, scoped to:
- `GLOBAL` — all servers and networks
- `SERVER` — one specific server
- `NETWORK` — all servers in one network

A user can have multiple assignments simultaneously (e.g. Server Admin on Network A, Viewer on Network B).

### Permission resolution

For a given user + resource:
1. Fetch all GLOBAL group assignments for the user
2. Fetch SERVER-scoped assignments matching the server ID
3. Fetch NETWORK-scoped assignments matching the server's network ID
4. Union all permission nodes from matched groups
5. Check `is_active` on user — reject if false

Always hits DB. Cache may be added later.

### System groups (is_system = true, cannot be deleted or renamed)

| Group | Permissions |
|---|---|
| Super Admin | `*` (all) |
| Server Admin | All except `system.settings`, `system.users`, `system.nodes`, `server.resources`, `server.migrate` |
| Operator | `server.restart`, `server.console`, `server.view`, `server.backup` |
| Viewer | `server.view` |

## Frontend Conventions

- Package manager: **pnpm** with `node-linker=hoisted` in `.npmrc` (required for Next.js standalone tracing)
- Styling: Tailwind CSS v4 + shadcn/ui (base-nova style, uses `@base-ui/react`)
- Fonts: Barlow (body, `--font-sans`), Barlow Condensed (headings, `--font-condensed`), JetBrains Mono (data values, `--font-mono`)
- Dark-only — no light theme, `dark` class hardcoded on `<html>`

### API client

- Codegen: `@hey-api/openapi-ts` (devDep). Config in `frontend/openapi-ts.config.ts`. Generates `lib/generated/`:
  - `sdk.gen.ts` — one named function per endpoint: `listServers()`, `startServer({ path: { id } })`, `authLogin({ body: { email, password } })`, …
  - `types.gen.ts` — TypeScript types (named `IoCraftpanelMaster…`, re-exported with friendly aliases from `lib/types.ts`)
  - `client.gen.ts` + `client/` — the bundled fetch client
- `lib/client.ts` — configures the generated client singleton: sets `baseUrl: ""` and `credentials: "include"`, registers request interceptor (Bearer token), and response interceptor (401 → refresh + retry). The bare `fetch` inside `refreshToken()` is intentional — using the SDK client there would cause infinite recursion.
- All routes must have `operationId` set in their ktor-openapi doc block — it controls the generated function name.
- Response shape: `{ data?, error?, response? }`. `data` is populated on success; `error` on API error (has `.message`); `response` may be undefined on network failure — always use `response?.status`.

## CraftPanel Colour Tokens

```css
--bg: #0e0d0c          /* page background */
--surface: #1c1917     /* card/sidebar background */
--surface-high: #27241f
--surface-higher: #312d27
--border: #2e2a24
--accent: #d97706      /* amber — primary action colour */
--accent-bright: #f59e0b
--text-primary: #f5f0e8
--text-dim: #a89880
--text-muted: #665e52
--healthy: #4ade80
--error: #f87171
--warning: #fbbf24
```

Use `bg-accent`, `text-accent`, `border-accent` for amber. Use `bg-surface`, `bg-bg` for backgrounds. Never use raw hex in components — always use token classes.

## Domain Features

- Permission-node-based multi-user access control with assignment scopes
- Server lifecycle management via `itzg/minecraft-server` and `itzg/mc-proxy` Docker images
- Player ingress via `itzg/mc-router` with Cloudflare DNS (per-server A records)
- Modrinth mod integration
- Live console streaming and file exploration over WebSocket (proxied through master from agent DataService)
- Backup scheduling and retention
- Live rsync-based server migration between nodes
- Node registration: agent-initiated via bootstrap token, requires admin approval

## Testing (master)

Tests live in `master/src/test/kotlin/`. Run with `./gradlew :master:test`. Coverage via Kover: `./gradlew :master:koverHtmlReport`.

### TestDatabase (`io.craftpanel.master.TestDatabase`)
Singleton H2 in-memory DB shared across all tests. Call `initIfNeeded()` once and `reset()` in `@BeforeTest`.
- Creates: `Users`, `RefreshTokens`, `Groups`, `GroupPermissions`, `UserGroupAssignments`, `ServerNetworks`, `Nodes`, `Servers`, `ServerEnvVars`, `NodeMetrics`, `PortRegistry`, `ServerMigrations`
- Seeds system groups on first init
- `reset()` deletes in FK-safe order: `ServerMigrations → PortRegistry → NodeMetrics → ServerEnvVars → Servers → Nodes → ServerNetworks → RefreshTokens → UserGroupAssignments → Users`

### Route test conventions
- `configureTest()` is an `Application.()` extension (not `ApplicationTestBuilder.()`), called via `application { configureTest() }` — avoids implicit receiver ambiguity with `install()`
- Server/client `ContentNegotiation` are disambiguated with `as` import aliases
- Generate JWTs directly with `jwtManager.generate(TokenClaims(...))` — no need to go through the login endpoint
- Inject lambdas for dependencies that require external state (e.g. `sendToNode: (String, MasterMessage) -> Boolean`)

## Database

PostgreSQL via Exposed ORM 1.0 and HikariCP.
Use `org.jetbrains.exposed.v1.*` import paths (changed from 0.x).
Schema migrations via `exposed-migration-jdbc`.

## What NOT to Do

- Don't add a Makefile
- Don't use multi-stage Dockerfiles
- Don't put build logic inside Docker
- Don't hardcode image registry or version values
- Don't use `org.jetbrains.exposed.*` imports — use `org.jetbrains.exposed.v1.*`
- Don't add a light theme
- Don't use raw hex colours in components — use token classes
- Don't put permission nodes in the JWT
- Don't skip DB lookup for permission checks — no shortcut based on JWT claims alone
- Don't login via email **and** username — login is email-only (`LoginRequest.email`)
- Don't use `revokeAll` with `deleteWhere` — it uses `UPDATE SET revoked=true` (soft delete)
- Don't store `image_type`/`mc_image` in the Servers schema — it was removed; derive Docker image from `server_type` at runtime in the agent
- Don't use `putAllEnvVars(map)` in proto DSL builders — use `envVars.putAll(map)` (extension function on the DslMap property)
- Don't use `eq`/`neq`/`inList` from `SqlExpressionBuilder.*` imports — use `import org.jetbrains.exposed.v1.core.*` and only call them inside `where {}` lambdas
- Don't read `defaultExpression` columns (e.g. `createdAt`) from an `InsertStatement` — SELECT the row after insert instead
- Don't install Python packages with system pip — use `uv` or `pipx`
- Don't use `openapi-fetch` or `openapi-typescript` — replaced by `@hey-api/openapi-ts`
- Don't call API endpoints with inline URL strings (e.g. `api.GET("/api/v1/servers")`) — use the generated named functions from `lib/generated/sdk.gen`
- Don't add new Ktor routes without an `operationId` in the doc block — it's required for the codegen to produce a usable function name
