import craftpanel.dockerCacheEnabled
import craftpanel.dockerImageBase
import craftpanel.dockerImageTag
import craftpanel.dockerPushEnabled
import org.siouan.frontendgradleplugin.infrastructure.gradle.AssembleTask

plugins {
    alias(libs.plugins.frontend)
    alias(libs.plugins.flowkode.buildx)
}

frontend {
    nodeVersion.set("22.14.0")
    nodeInstallDirectory.set(layout.projectDirectory.dir(".node"))
    packageJsonDirectory.set(layout.projectDirectory)
    assembleScript.set("run build")
    checkScript.set("run check")
}

tasks.register<Delete>("cleanFrontend") {
    delete(layout.projectDirectory.dir(".next"))
    delete(layout.projectDirectory.dir("out"))
}

tasks.named("clean") {
    dependsOn("cleanFrontend")
}

val withCoverage = project.hasProperty("withCoverage")

// Optional Playwright shard for CI, e.g. -Pe2eShard=1/4. Captured at configuration time so the
// Exec task stays configuration-cache safe.
val e2eShard = (project.findProperty("e2eShard") as String?)?.takeIf { it.isNotBlank() }

tasks.register<Exec>("typecheckFrontend") {
    group = "verification"
    description = "Runs TypeScript type checking"
    dependsOn("installFrontend", "generateApiTypes")
    workingDir = layout.projectDirectory.asFile
    commandLine(layout.projectDirectory.file(".node/bin/pnpm").asFile, "exec", "tsc", "--noEmit")
}

tasks.register<Exec>("testFrontend") {
    group = "verification"
    description = "Runs frontend unit tests via vitest"
    dependsOn("generateApiTypes")
    workingDir = layout.projectDirectory.asFile
    val pnpm = layout.projectDirectory.file(".node/bin/pnpm").asFile
    if (withCoverage) {
        commandLine(pnpm, "run", "test:coverage")
    } else {
        commandLine(pnpm, "run", "test")
    }
}

tasks.register<Exec>("formatFrontend") {
    group = "build"
    description = "Formats frontend files changed since origin/master (ratchet, mirrors Spotless)"
    dependsOn("installFrontend")
    workingDir = layout.projectDirectory.asFile
    commandLine(layout.projectDirectory.file(".node/bin/pnpm").asFile, "run", "format")
}

tasks.register<Exec>("testE2eMocked") {
    group = "verification"
    description = "Runs MSW-backed Playwright E2E tests (no live backend required)"
    dependsOn("generateApiTypes")
    workingDir = layout.projectDirectory.asFile
    commandLine(layout.projectDirectory.file(".node/bin/pnpm").asFile, "run", "test:e2e")
    environment("CI", "true")
}

tasks.register<Exec>("testE2eCoverage") {
    group = "verification"
    description = "Runs MSW-backed Playwright E2E tests with V8 code coverage (optionally one -Pe2eShard=N/4 shard)"
    dependsOn("generateApiTypes")
    workingDir = layout.projectDirectory.asFile
    val pnpm = layout.projectDirectory.file(".node/bin/pnpm").asFile
    commandLine(
        buildList {
            add(pnpm)
            add("run")
            add("test:e2e")
            e2eShard?.let {
                add("--")
                add("--shard=$it")
            }
        }
    )
    environment("CI", "true")
    environment("E2E_COVERAGE", "true")
    if (e2eShard != null) {
        // Shards upload their raw cache; the merge job generates the combined report. Generating
        // here would purge the cache MCR needs for the merge.
        environment("E2E_COVERAGE_REPORT", "false")
    }
}

tasks.register<Exec>("mergeE2eCoverage") {
    group = "verification"
    description = "Merges raw E2E coverage caches from all shards into a single report"
    dependsOn("installFrontend")
    workingDir = layout.projectDirectory.asFile
    commandLine(layout.projectDirectory.file(".node/bin/pnpm").asFile, "run", "merge:e2e-coverage")
}

tasks.named("check") {
    dependsOn("typecheckFrontend", "testFrontend", "testE2eMocked")
}

tasks.register<Exec>("generateApiTypes") {
    group = "build"
    description = "Generates lib/generated/api.ts from the backend OpenAPI spec"
    dependsOn("installFrontend", ":master:generateOpenApiSpec")
    workingDir = layout.projectDirectory.asFile
    commandLine(layout.projectDirectory.file(".node/bin/pnpm").asFile, "run", "generate-api")
    inputs.files(
        rootProject.layout.buildDirectory.file("openapi.json"),
        layout.projectDirectory.file("openapi-ts.config.ts")
    )
    outputs.dir(layout.projectDirectory.dir("lib/generated"))
}

tasks.named("assembleFrontend") {
    dependsOn("generateApiTypes")
}

tasks.named("assemble") {
    dependsOn("assembleFrontend")
}

@Suppress("UNCHECKED_CAST")
val gitVersion = rootProject.extra["gitVersion"] as Provider<String>
val pushEnabled = dockerPushEnabled(project)

// Expose the git build hash to `next build` so Next inlines it into the server bundle
// (NEXT_PUBLIC_ vars are hardcoded at build time). Gradle is the single source of the hash.
tasks.named<AssembleTask>("assembleFrontend") {
    inputs.property("craftpanelBuildVersion", gitVersion)
    environmentVariables.put("NEXT_PUBLIC_CRAFTPANEL_BUILD_VERSION", gitVersion)
}

buildx {
    imageName = dockerImageBase(project, "frontend")
    tags = listOf(dockerImageTag(project))
    context = layout.projectDirectory
    dockerfile = file("Dockerfile")
    labels { put("org.opencontainers.image.version", gitVersion.get()) }
    push = pushEnabled
    load = !pushEnabled
    if (dockerCacheEnabled(project)) {
        cacheFrom.set("type=gha,scope=frontend")
        cacheTo.set("type=gha,scope=frontend,mode=max")
    }
}

tasks.named("buildxBuild") {
    dependsOn("assembleFrontend")
    mustRunAfter(tasks.named("check"))
}
