# Parallelize System Tests in GitHub Actions

## Problem

`system-tests/` runs ~34 Kotest spec classes in one sequential job (`system.yml`), spinning up Postgres + master + agent + fake-server via Testcontainers for each. Single job, 30 min timeout, gets slower as suite grows.

## Goal

Split system tests across parallel GH Actions jobs by domain, cutting wall-clock time, without duplicating Docker image builds or losing coverage reporting.

## Design

### 1. Sharding: Kotest `@Tags`, 7 groups

Domain-based tags added to each spec class (`io.kotest.core.annotation.Tags`), not filename globs — future test files must opt in explicitly.

| Tag | Classes |
|---|---|
| `ServerCore` | ServerLifecycleTest, ServerConsoleTest, ServerFilesTest, FileUploadTest, ServerUpdateTest, ServerEdgeCasesTest, ServerMetricsTest, PlayerCountTest |
| `ServerOps` | ServerMigrationTest, MigrationSecurityTest, ServerModsTest, SearchModsTest, ModrinthInjectionTest, ServerUpgradeTest |
| `Node` | NodeOperationsTest, NodeResourcesTest, NodeShutdownTest, NodeIpTest, NodeMetricsTest, NodeRegistrationTest, MultiNodeTest, TokenRotationTest |
| `Auth` | AuthTest, AuthSecurityTest, PermissionResolutionTest, PermissionsTest |
| `BackupAlerts` | BackupTest, BackupDownloadTest, AlertTest, AlertEventsTest |
| `Misc` | ConfigTest, ProxyBackendTest, DashboardWsTest, AdminTest, McRouterRoutingTest, NetworkTest, SystemSettingsTest |

`server` package (14 classes) was the long pole if left as one shard, so it's split into `ServerCore`/`ServerOps` (~7 each) to keep shards roughly balanced (~4-8 classes each).

Harness/helper files (`BaseSystemTest`, `CraftPanelStack`, `*Helper.kt`, `PortBandAllocator`, `PollUtil`, `TimingListener`, `SystemTestConfig`) are untagged infra, unaffected.

### 2. Docker images: build once, share via artifact

Current workflow runs `dockerBuildAll` inline in the single job. With 7 parallel shard jobs, rebuilding per-shard wastes ~6x compile/build time.

New `build-images` job:
- Runs `./gradlew dockerBuildAll` once
- `docker save` all 3 images (master, agent, fake-server) to a tarball
- `actions/upload-artifact` uploads the tarball

Each shard job (`needs: build-images`):
- Downloads the artifact
- `docker load`s the images before running tests
- No image registry involved — this is CI-internal, not published anywhere

### 3. Workflow structure (`system.yml`)

```yaml
jobs:
  build-images:
    # dockerBuildAll, docker save, upload-artifact

  system-tests:
    needs: build-images
    strategy:
      fail-fast: false
      matrix:
        tag: [ServerCore, ServerOps, Node, Auth, BackupAlerts, Misc]
    steps:
      # checkout, setup-java, gradle cache
      # download-artifact + docker load
      - run: ./gradlew :system-tests:test -PwithCoverage --configuration-cache-problems=warn -PkotestTags=${{ matrix.tag }}
      # upload test-results (per-tag artifact name) on failure
      # upload to Codecov: test-results + kover XML, flags: system

  e2e-tests:
    # unchanged
```

Each matrix job runs on its own runner with its own Docker daemon — `CraftPanelStack` is a plain class (not a singleton), `PortBandAllocator` draws a random per-JVM port band — no cross-shard port/container collision. No infra changes needed there.

Failure-report artifacts and Codecov upload names must be suffixed with `matrix.tag` to avoid collisions across shards uploading concurrently.

### 4. Gradle wiring for tag filtering

Kotest reads the `kotest.tags` **system property** inside the forked test JVM, not a Gradle project property. `system-tests/build.gradle.kts` needs:

```kotlin
tasks.named<Test>("test") {
    val kotestTags = project.findProperty("kotestTags") as String?
    if (kotestTags != null) {
        systemProperty("kotest.tags", kotestTags)
    }
}
```

Invoked as `-PkotestTags=ServerCore` (Gradle project property, config-cache safe — captured at configuration time, not read inside `onlyIf`/execution-time lambdas, matching the project's config-cache rules).

### 5. Coverage merge: no merge job

Each shard uploads its own `report.xml` fragment to Codecov with `flags: system`. Codecov merges multiple uploads under the same flag server-side — no Kover CLI merge job required. Matches existing single-job upload pattern, just repeated per-shard.

## Testing

- Verify `-Dkotest.tags` filtering actually narrows the run: `./gradlew :system-tests:test -PkotestTags=Auth --tests "*"` locally, confirm only Auth-tagged specs execute (check `build/test-results/test/*.xml` count).
- Verify `docker save`/`load` round-trip preserves images `dockerBuildAll` produces (image IDs match, containers start).
- One full CI run on a branch to confirm all 7 shards go green, artifacts don't collide, Codecov shows combined `system` flag coverage.

## Out of scope

- Auto-balancing/rebalancing shards as new tests are added (manual tag assignment for now)
- Splitting `master`/`agent`/`frontend` unit test jobs (already fast, not the target)
- Registry-based image sharing (GHCR push/pull) — artifact-based is sufficient for CI-internal use
