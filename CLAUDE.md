# CraftPanel — Agent Context

Self-hosted multi-user multi-node Minecraft server management platform.

## Base Instructions

ALWAYS context7 when I need library/API documentation, code generation, setup or configuration steps without me having to explicitly ask.
When finishing a task always run get_file_problems from intellij/idea to find possible issues and fix them before submitting the code. If there are any warnings or errors that cannot be fixed, list them explicitly in the final answer.
Before committing any code always use reformat_code from intellij/idea to reformat the files.
If a method or class is deprecated avoid using it.

Before starting any task, assess complexity:

- Straightforward implementation (schema, CRUD endpoints, mechanical wiring):
  proceed directly with Haiku
- Ambiguous architecture, cross-module coordination, new proto changes,
  state machine logic: pause and flag for advisor review before proceeding

**Advisor calls require user confirmation.** Before calling `advisor()`, always ask the user: "Call advisor for [reason]?" and wait for approval. Never call advisor silently.

## Module Structure

```
craftpanel/
├── master/        # Kotlin + Ktor REST/WebSocket backend, gRPC server, PostgreSQL
├── agent/         # Minimal Kotlin gRPC client, manages Docker containers on remote nodes
├── fake-server/   # Minimal Minecraft server simulator used by system tests (TCP ping + stdin)
├── frontend/      # Next.js 16 frontend
├── proto/         # Shared protobuf definitions (consumed by master and agent)
├── system-tests/  # End-to-end Kotest + Testcontainers tests
├── docs/          # MkDocs documentation
├── plans/         # Development roadmap and per-phase implementation plans
└── CLAUDE.md
```

## Key Versions

```toml
kotlin = "2.4.0"
ktor = "3.5.0"
exposed = "1.3.0"          # NOTE: 1.0+ uses org.jetbrains.exposed.v1.* imports
grpc = "1.82.0"
grpc-kotlin = "1.5.0"
protobuf = "4.35.0"
coroutines = "1.11.0"
hikari = "7.0.2"
postgresql = "42.7.11"
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
./gradlew :master:generateOpenApiSpec   # generates openapi.json at build/openapi.json (smiley4 testApplication, runs OpenApiSpecTask)
./gradlew :frontend:generateApiTypes    # runs @hey-api/openapi-ts → frontend/lib/generated/
```

Both run automatically as part of `:frontend:assembleFrontend`. `lib/generated/` is gitignored.
Spec is generated at test time by `OpenApiSpecTask` (a `testApplication` that boots full routing and hits `/openapi.json`).
Routes are documented inline using the smiley4 DSL (`get("/path", { operationId = "..."; summary = "..."; request { ... }; response { ... } }) { ... }`).
All routes are registered via `registerAppRoutes()` in `AppRoutes.kt` — add new routes there only (shared by `Main.kt` and `OpenApiSpecTask`).
Schema generator uses `RefType.OPENAPI_SIMPLE` — do not change to `OPENAPI_FULL` (produces FQN-based schema names → ugly generated client class names).

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

- **ControlService** — persistent bidirectional stream, agent-initiated, lives for the lifetime of the connection. Handles container lifecycle commands, backups, migration, metrics, player updates. Console sessions and small file ops (list, read, write, delete, move, copy, mkdir) are multiplexed over this stream using `request_id` correlation.
- **BulkDataService** — agent-initiated on-demand connections to master for large file transfers only (upload, download). Isolated from the control stream to prevent head-of-line blocking. Agent authenticates with its node key.

Master never dials out to agents. Both services are agent-initiated. `DataServiceProxy` in master routes requests through `ControlServiceImpl` (for unary/console ops) or accepts incoming BulkDataService connections from the agent.

Agent sends `NodeStateSnapshot` as the **first message** on every (re)connect so master can reconcile DB state before issuing commands.

Node authentication over gRPC:

- **First registration**: agent calls `RegisterNode` with bootstrap token → master returns 256-bit node key
- **Subsequent connections**: agent calls `IdentifyNode` with node key → master returns `ACTIVE`, `PENDING`, or `REJECTED`
- Node keys stored as SHA-256 hashes in DB, never raw
- TLS on gRPC channel; node key is the auth mechanism (not mTLS client certs)
- `NODE_HOSTNAME` env var overrides the hostname the agent reports to master (useful when container hostname differs from actual node hostname)

### Agent registry (`ControlServiceImpl`)

`ConcurrentHashMap<String, SendChannel<MasterMessage>>` tracks connected agents by nodeId.
Registered on first message inside `channelFlow`, deregistered in `finally` using `remove(nodeId, channel)` (atomic — safe against reconnect races).
`sendToNode(nodeId, msg)` uses `trySend` — returns `false` if agent disconnected.

### ControlService stream resilience

Any uncaught exception inside `requests.collect { }` in `ControlServiceImpl.control()` propagates through `channelFlow` and tears down the entire bidirectional stream. Wrap every handler branch in `runCatching { }.onFailure { log.error(...) }`. Re-throw `CancellationException` if catching `Exception` in a `launch {}` body.

### Agent handler architecture (`agent/src/main/kotlin/io/craftpanel/agent/grpc/`)

`ControlStreamHandler` is a **pure dispatcher** — constructor + `run()` + `dispatch()` + `buildStateSnapshot()` only.
All logic lives in `handlers/` subpackage, one class per domain:

| Class | Handles |
|---|---|
| `ContainerHandler` | create/start/stop/restart/remove/pullImage/shutdown |
| `BackupHandler` | triggerBackup/deleteBackup |
| `MigrationHandler` | prepareRsyncReceive/startRsync/sendRcon |
| `FileHandler` | all file ops + bulk transfers (download/upload) |
| `ConsoleHandler` | stateful console session lifecycle (owns `consoleSessions` map + `DockerClient`) |
| `AgentUtils` | shared `nowTimestamp()` and `generateRsyncPassword()` helpers (package-internal) |

**`AgentOutbound`** wraps `SendChannel<AgentMessage>` + `nodeId`. Use:
- `out.send { ... }` — suspend send with nodeId pre-filled
- `out.trySend { ... }` — non-blocking send
- `out.tryConsoleOutput(reqId) { ... }` — non-blocking console frame
- `out.serverStatus(...)` / `out.tryServerStatus(...)` — server status updates

**Two-layer error handling in agent handlers (load-bearing):**
- Inner `runCatching` in each handler: `onSuccess` emits HEALTHY/STOPPED, `onFailure` emits UNHEALTHY
- Outer `dispatch { }` in `ControlStreamHandler`: catches unexpected throws, re-throws `CancellationException`

`ConsoleHandler` must be **per-connection** (not singleton) — owns `consoleSessions` map scoped to one gRPC stream.

## Console WebSocket

- Auth happens **after** WS upgrade (ticket query param checked post-handshake) — rejection uses WS close code **1008**, not HTTP 401. Never expect HTTP 401 in `onFailure`.
- Input must be JSON: `{"type":"console.input","data":"<cmd>\n"}` — raw text is silently dropped. The `\n` terminator is required; `StdinListener` uses `reader.lineSequence()`.

## Auth (REST/WebSocket)

### JWT

Short-lived access tokens, HS256 signed, **15 minute lifetime**.

```json
{
  "sub": "<user-uuid>",
  "name": "John Doe",
  "email": "john@example.com",
  "groups": [
    "Server Admin"
  ],
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
server.migrate   server.view
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

| Group        | Permissions                                                                                        |
|--------------|----------------------------------------------------------------------------------------------------|
| Super Admin  | `*` (all)                                                                                          |
| Server Admin | All except `system.settings`, `system.users`, `system.nodes`, `server.resources`, `server.migrate` |
| Operator     | `server.restart`, `server.console`, `server.view`, `server.backup`                                 |
| Viewer       | `server.view`                                                                                      |

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
- `lib/client.ts` — configures the generated client singleton: sets `baseUrl: ""` and `credentials: "include"`, registers request interceptor (Bearer token), and response interceptor (401 → refresh +
  retry). The bare `fetch` inside `refreshToken()` is intentional — using the SDK client there would cause infinite recursion.
- All routes must have `operationId` set in their `@KtorDescription` annotation — it controls the generated function name.
- Response shape: `{ data?, error?, response? }`. `data` is populated on success; `error` on API error (has `.message`); `response` may be undefined on network failure — always use `response?.status`.

## CraftPanel Colour Tokens

```css
--bg: #0e0d0c /* page background */
--surface: #1c1917 /* card/sidebar background */
--surface-high: #27241f
--surface-higher: #312d27
--border: #2e2a24
--accent: #d97706 /* amber — primary action colour */
--accent-bright: #f59e0b
--text-primary: #f5f0e8
--text-dim: #a89880
--text-muted: #665e52
--healthy: #4ade80
--error: #f87171
--warning: #fbbf24
```

Use `bg-accent`, `text-accent`, `border-accent` for amber. Use `bg-surface`, `bg-bg` for backgrounds. Never use raw hex in components — always use token classes.

## Testing (frontend)

Stack: **Vitest** + **React Testing Library** + **jsdom**. Config at `frontend/vitest.config.ts`, global setup at `frontend/vitest.setup.ts`.

```bash
cd frontend && .node/bin/pnpm test       # run all tests (use .node/bin/pnpm, not system pnpm)
./gradlew :frontend:testFrontend         # run via Gradle
./gradlew :frontend:check                # lint + test
```

- `next/navigation` is mocked globally in `vitest.setup.ts` — no per-file mock needed
- Mock generated SDK: `vi.mock('@/lib/generated', () => ({ fn: vi.fn(), ... }))`
- Mock client module: `vi.mock('@/lib/client', () => ({ setAccessToken: vi.fn(), getAccessToken: vi.fn(), client: { setConfig: vi.fn(), interceptors: { request: { use: vi.fn() }, response: { use: vi.fn() } } } }))`
- Test files co-located: `lib/foo.test.ts`, `lib/__tests__/bar.test.tsx`, `app/(route)/__tests__/page.test.tsx`

## Testing (master)

Tests live in `master/src/test/kotlin/`. Run with `./gradlew :master:test`. Coverage via Kover: `./gradlew :master:koverHtmlReport`.

### TestDatabase (`io.craftpanel.master.TestDatabase`)

Singleton H2 in-memory DB shared across all tests. Call `initIfNeeded()` once and `reset()` in `@BeforeTest`.

- Creates: `Users`, `RefreshTokens`, `Groups`, `GroupPermissions`, `UserGroupAssignments`, `ServerNetworks`, `Nodes`, `Servers`, `ServerEnvVars`, `NodeMetrics`, `PortRegistry`, `ServerMigrations`, `MigrationStepLog`, `Backups`, `AlertThresholds`, `AlertEvents`, `ContainerMetrics`, `ServerMods`, `SystemSettings`
- Seeds system groups on first init
- `reset()` deletes in FK-safe order: `AlertEvents → AlertThresholds → Backups → ServerMods → MigrationStepLog → ServerMigrations → PortRegistry → ContainerMetrics → NodeMetrics → ServerEnvVars → Servers → Nodes → ServerNetworks → SystemSettings → RefreshTokens → UserGroupAssignments → Users`

### Route test conventions

- `configureTest()` is an `Application.()` extension (not `ApplicationTestBuilder.()`), called via `application { configureTest() }` — avoids implicit receiver ambiguity with `install()`
- Server/client `ContentNegotiation` are disambiguated with `as` import aliases
- Generate JWTs directly with `jwtManager.generate(TokenClaims(...))` — no need to go through the login endpoint
- Inject lambdas for dependencies that require external state (e.g. `sendToNode: (String, MasterMessage) -> Boolean`)
- `startServer()` sends **3** gRPC messages when `containerId` is null or `needsRecreate` is true: `pullImage` → `createContainer` → `startContainer`. Tests asserting `sentCommands.size` must account for all three.

### System tests (`system-tests/`)

End-to-end tests using Testcontainers 2.0.5 + Kotest. Spin up real PostgreSQL + master + agent containers.

```bash
./gradlew :system-tests:test
./gradlew :system-tests:test --tests "craftpanel.systemtest.server.ServerLifecycleTest"  # run one class
```

Requires Docker daemon running. Tests spin up PostgreSQL, master, agent, and fake-server containers.

- `CraftPanelStack` — singleton that starts/stops the full stack via Testcontainers
- `BaseSystemTest` — Kotest `ShouldSpec` base; handles `CraftPanelStack.start()`, auth login, and trusting first pending node in `beforeSpec`/`afterSpec`
- Helper classes: `AuthHelper`, `NodeHelper`, `ServerHelper`, `MultiNodeHelper` in `harness/`
- `MultiNodeHelper.trustAllPendingNodes(n)` — used by `SystemTestConfig` when stack starts with 2 agents
- `listMods` returns `Map<String, List<ModResponse>>` (bucketed by loader); `.isEmpty()` checks map keys, not entries — use `.values.flatten().isEmpty()`
- **Kotest 6.x: all lifecycle hooks must be registered at the `init {}` level**, never inside a `context {}` lambda — `beforeSpec`, `afterSpec`, `beforeContainer`, `beforeEach`, etc. are silently ignored when registered inside a container scope. Declare `lateinit var` state and hooks directly in `init`, then reference them from nested `context`/`should` lambdas via closure.
- Shared servers in system tests: use `beforeSpec`/`afterSpec` with `lateinit var serverId` (and `serverId2` if two configs are needed) rather than per-test `try/finally` creation — reduces container spin-up count significantly.
- `system-tests/build/generated/` is regenerated at build time via `:master:generateOpenApiSpec` → `:system-tests:generateApiClient`. Manual edits there survive until the next build that re-runs codegen.

#### System test coverage (`-PwithCoverage`)

```bash
./gradlew :system-tests:test -PwithCoverage   # runs tests + auto-generates HTML/XML report
```

- Testcontainers 2.x `ResourceReaper` sends **SIGKILL** directly — JVM shutdown hooks (including Kover's flush) never run. `CraftPanelStack.gracefulStop()` sends `stopContainerCmd(timeout=30)` first in coverage mode to let the JVM flush `.ic` files before cleanup.
- Kover CLI `--classfiles` needs the specific JAR (`master.jar`, `agent.jar`), not the `lib/` directory — the CLI cannot scan a directory of JARs.
- Coverage runs use `outputs.cacheIf { false }` on the test task — without it, Gradle restores test results from the build cache without running the JVM, leaving the coverage directory empty.
- `koverSystemTestReport` uses `notCompatibleWithConfigurationCache` because `.ic` files don't exist at configuration time and must be discovered at execution time.

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
- Don't use raw hex codes or `rgba(...)` literals in component `style` props or Tailwind arbitrary value brackets — use the corresponding CSS variable token (`var(--token)`) or Tailwind token class
  instead. Raw values are only permitted in `globals.css` and `tailwind.config.js` where the tokens are defined
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
- Don't call API endpoints with inline URL strings (e.g. `api.GET("/api/servers")`) — use the generated named functions from `lib/generated/sdk.gen`
- Don't add new Ktor routes without an `operationId` in the doc block — it's required for the codegen to produce a usable function name
- Don't use fully qualified names on code, always use imports.
- Don't use static imports for Enum constants.
- Don't use `apply` for testcontainers configuration
- Don't use `JsonObject` as a route request body type — leaks internal kotlinx-serialization type schemas into OpenAPI spec; use a typed `@Serializable` DTO instead
- Don't access `project` inside `onlyIf {}` lambdas — runs at execution time, breaks Gradle config cache; capture value at configuration time or use `enabled = <bool>`
- Don't use `dependsOn(test)` in a report task triggered via `finalizedBy` — use `mustRunAfter(test)` to avoid double-execution; use a string `finalizedBy("taskName")` not a task-provider reference to avoid forward-reference compile errors
- Don't use `afterEvaluate {}` closures that capture script-level objects — fail config cache serialization; use plain `tasks.named(...).configure {}` blocks instead
- Don't use `respondBytesWriter` before verifying file existence — it commits HTTP 200 immediately; call `proxy.downloadFile` (throws on 404) before opening the writer
- Don't return `application/octet-stream` binary bodies from endpoints consumed by the system-test generated client (jvm-okhttp4+Gson maps binary response as `body.bytes() as? T` → null → NPE) — return JSON `List<Int>` for byte arrays instead, or a typed DTO
- Don't conflate `itzgImageTag` (Docker image tag e.g. `"1.21.5"`) with `mcVersion` (the `VERSION` env var passed to `itzg/minecraft-server`) — they are separate fields with separate meanings
- Don't use `java.util.UUID` — use `kotlin.uuid.Uuid` (`Uuid.random()`, `Uuid.parse()`, no `.toJavaUuid()`/`.toKotlinUuid()` conversions; Exposed 1.3.0 `uuid()` columns return `Uuid` natively)
- Don't use `kotlin.io.encoding.Base64.encode()` for WS ticket tokens — produces standard base64 with `+`/`/`/`=` that are URL-unsafe in `?ticket=` query params; use `java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)`
## Agent skills

### Issue tracker

Issues live as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default label vocabulary (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context — one `CONTEXT.md` + `docs/adr/` at repo root. See `docs/agents/domain.md`.
