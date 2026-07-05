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
        description = "Unit tests + system tests + all coverage reports (per-module + merged)"
        dependsOn(
            ":master:test",
            ":agent:test",
            ":frontend:testFrontend",
            ":system-tests:test",
            ":system-tests:koverSystemTestReport",
            ":system-tests:koverMergedReport",
        )
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
        ":frontend:dockerBuildImage",
    )
}

val dockerPushAll by tasks.registering {
    group = "docker"
    description = "Pushes all Docker images"
    dependsOn(
        ":master:dockerPushImage",
        ":agent:dockerPushImage",
        ":frontend:dockerPushImage",
    )
}

tasks.named("check") {
    dependsOn(":frontend:testFrontend")
}

tasks.register("test") {
    group = "verification"
    description = "Runs all tests (JVM subprojects + frontend)"
    dependsOn(":master:test", ":agent:test", ":frontend:typecheckFrontend", ":frontend:testFrontend")
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
                        "ktlint_standard_no-wildcard-imports" to "disabled",
                    ),
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
