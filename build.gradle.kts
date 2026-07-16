plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.bmuschko.docker) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.spotless)
}

// ---------------------------------------------------------------------------
// Project-wide properties
// ---------------------------------------------------------------------------
version = project.property("craftpanel_version") as String

val imageVersion: String =
    findProperty("imageVersion")?.toString()
        ?: findProperty("craftpanel_version")?.toString()
        ?: "latest"

// Version string surfaced at runtime (Docker label + /health): the git tag if HEAD is
// exactly on one, else the short sha, else "unknown" outside a git checkout.
// NOTE: isIgnoreExitValue leaves stdout as "" (a present-but-empty value) on failure, not
// an absent provider, so orElse() alone won't fall through — empty results must be mapped
// to null explicitly to make the provider chain skip to the next fallback.
val gitVersion: Provider<String> =
    providers.exec {
        commandLine("git", "describe", "--tags", "--exact-match", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }.map { it.ifEmpty { null } }
        .orElse(
            providers.exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
                isIgnoreExitValue = true
            }.standardOutput.asText.map { it.trim() }.map { it.ifEmpty { null } }
        )
        .orElse("unknown")

extra["gitVersion"] = gitVersion

// ---------------------------------------------------------------------------
// Kover merged reporting (aggregates master and agent subprojects)
// ---------------------------------------------------------------------------
dependencies {
    kover(project(":master"))
    kover(project(":agent"))
}

kover {
    reports {
        filters {
            excludes {
                packages("com.craftpanel")
                classes("*Grpc*", "*OuterClass")
                classes("io.craftpanel.master.MainKt", "io.craftpanel.agent.MainKt")
            }
        }
        total {
            html { title = "CraftPanel JVM (aggregated)" }
            xml { xmlFile = layout.buildDirectory.file("reports/kover/aggregated/report.xml") }
        }
    }
}

if (project.hasProperty("withCoverage")) {
    tasks.register("koverFullReport") {
        group = "verification"
        description = "Unit tests + all coverage reports (per-module + merged). Add -PsystemTest to include system tests."
        dependsOn(":master:test", ":agent:test", ":frontend:testFrontend", "koverHtmlReport", "koverXmlReport")
        if (project.hasProperty("systemTest")) {
            dependsOn(
                ":system-tests:test",
                ":system-tests:koverSystemTestReport",
                ":system-tests:koverMergedReport"
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Docker aggregation tasks
// ---------------------------------------------------------------------------
val dockerBuildAll by tasks.registering {
    group = "docker"
    description = "Builds all Docker images"
    dependsOn(
        ":master:dockerBuildImage",
        ":agent:dockerBuildImage",
        ":frontend:dockerBuildImage"
    )
}

val dockerPushAll by tasks.registering {
    group = "docker"
    description = "Pushes all Docker images"
    dependsOn(
        ":master:dockerPushImage",
        ":agent:dockerPushImage",
        ":frontend:dockerPushImage"
    )
}

tasks.named("check") {
    dependsOn(":frontend:testFrontend")
}

tasks.register("test") {
    group = "verification"
    description = "Runs all tests (JVM subprojects + frontend). Add -PsystemTest to include system-tests."
    dependsOn(":master:test", ":agent:test", ":frontend:typecheckFrontend", ":frontend:testFrontend")
    if (project.hasProperty("systemTest")) {
        dependsOn(":system-tests:test")
    }
}

subprojects {
    tasks.withType<Test>()
        .configureEach {
            jvmArgs("-Dnet.bytebuddy.experimental=true")
        }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "com.diffplug.spotless")
        configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            kotlin {
                ktlint(libs.versions.ktlint.get()).editorConfigOverride(
                    mapOf(
                        "max_line_length" to "200",
                        "ktlint_standard_no-wildcard-imports" to "disabled"
                    )
                )
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
