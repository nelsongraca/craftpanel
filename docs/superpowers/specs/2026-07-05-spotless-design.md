# Spotless (Kotlin formatting) — Design

## Goal

Enforce consistent Kotlin code style via Spotless + ktlint, without a
disruptive repo-wide reformat commit.

## Scope

- **In:** `master`, `agent`, `fake-server`, `system-tests` (Kotlin sourcesets),
  plus every `*.gradle.kts` in the repo (root and per-module).
- **Out:** `frontend` (already has eslint wired into its own `check` task —
  no prettier config exists, adding Spotless there would duplicate eslint
  for no gain) and `docs` (no Kotlin).

## Formatter

ktlint, default ruleset. Version pinned in `gradle/libs.versions.toml`
(`ktlint`), alongside a new `spotless` plugin version entry — consistent
with the repo's "never hardcode dependency versions inline" rule.

## Wiring

Root `build.gradle.kts` already has a `subprojects {}` block (currently only
configuring `Test` JVM args). Extend it with a `plugins.withId("org.jetbrains.kotlin.jvm")`
guard so Spotless is only applied/configured for actual Kotlin-JVM modules —
this automatically covers `master`, `agent`, `fake-server`, `system-tests`
and skips `docs`/`frontend` without an explicit module list.

```kotlin
plugins {
    alias(libs.plugins.spotless)
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "com.diffplug.spotless")
        configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            kotlin {
                ktlint(libs.versions.ktlint.get())
                ratchetFrom("origin/master")
            }
        }
        tasks.named("check") { dependsOn("spotlessCheck") }
    }
}

spotless {
    kotlinGradle {
        target("**/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
        ratchetFrom("origin/master")
    }
}
tasks.named("check") { dependsOn("spotlessCheck") }
```

- `kotlin { }` block per module uses ktlint's default target (`.kt`/`.kts` in
  Kotlin sourcesets) — no explicit `target(...)` override needed.
- `kotlinGradle { }` lives once at root, targets every `*.gradle.kts` in the
  repo (root and module-level) via a glob — no per-module duplication.
- `ratchetFrom("origin/master")` on both blocks — Spotless only enforces
  files that differ from `origin/master`. Avoids a big-bang reformat commit;
  existing untouched files are left alone until someone edits them.
- `spotlessCheck` wired into each affected module's `check` task (and root
  `check` for the `kotlinGradle` block) — already covered by the existing
  aggregate `check`/CI path, no new top-level task needed.
- `spotlessApply` is available standalone (auto-added by the plugin) for
  local auto-fix; not wired into any existing task.

## Risks / accepted limitations

- `ratchetFrom("origin/master")` requires `origin/master` to be fetchable
  (true in CI and in any normal local clone). If a shallow clone lacks it,
  Spotless throws a clear error — acceptable, not handled specially.
- No repo-wide reformat pass — existing files keep their current style until
  next edited. This is intentional per user decision, not an oversight.
