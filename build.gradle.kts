import craftpanel.ReleaseTask

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.flowkode.buildx) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.spotless)
}

// ---------------------------------------------------------------------------
// Project-wide properties
// ---------------------------------------------------------------------------
version = project.property("craftpanel_version") as String

// ---------------------------------------------------------------------------
// Release task — local: version from Conventional Commits (minor), changelog, tag, push.
// The tag push triggers :publish.yml (no PAT needed: it is a developer push).
// ---------------------------------------------------------------------------
val releaseChangelogTemplate = """
{{#tags}}
## [{{name}}] - {{releaseDate}}

{{#ifContainsType commits type='feat'}}
### Features

{{#commits}}{{#ifCommitType . type='feat'}}
- {{{commitDescription .}}} ({{hash}})
{{/ifCommitType}}{{/commits}}

{{/ifContainsType}}
{{#ifContainsType commits type='fix'}}
### Bug Fixes

{{#commits}}{{#ifCommitType . type='fix'}}
- {{{commitDescription .}}} ({{hash}})
{{/ifCommitType}}{{/commits}}

{{/ifContainsType}}
{{#ifContainsType commits type='perf'}}
### Performance

{{#commits}}{{#ifCommitType . type='perf'}}
- {{{commitDescription .}}} ({{hash}})
{{/ifCommitType}}{{/commits}}

{{/ifContainsType}}
{{#ifContainsType commits type='refactor'}}
### Refactoring

{{#commits}}{{#ifCommitType . type='refactor'}}
- {{{commitDescription .}}} ({{hash}})
{{/ifCommitType}}{{/commits}}

{{/ifContainsType}}
{{#ifContainsType commits type='docs'}}
### Documentation

{{#commits}}{{#ifCommitType . type='docs'}}
- {{{commitDescription .}}} ({{hash}})
{{/ifCommitType}}{{/commits}}

{{/ifContainsType}}
{{/tags}}
""".trimIndent()

tasks.register<ReleaseTask>("release") {
    group = "release"
    description = "Compute the next minor version, update CHANGELOG.md, then commit, tag, and push."
    repoPath.set(layout.projectDirectory.asFile.absolutePath)
    changelogFile.set(layout.projectDirectory.file("CHANGELOG.md"))
    changelogTemplate.set(releaseChangelogTemplate)
    releaseVersion.set(providers.gradleProperty("releaseVersion"))
    dryRun.set(providers.gradleProperty("releaseDryRun").map { it.toBoolean() }.orElse(false))
    prepareNext.set(providers.gradleProperty("releasePrepareNext").map { it.toBoolean() }.orElse(true))
}

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
    kover(project(":common"))
}

kover {
    reports {
        filters {
            excludes {
                packages("com.craftpanel")
                classes("io.craftpanel.proto.*", "*Grpc*", "*OuterClass")
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
        dependsOn(":master:test", ":agent:test", ":common:test", ":frontend:testFrontend", "koverHtmlReport", "koverXmlReport")
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
    description = "Builds all Docker images (loads into local daemon unless -Ppush=true)"
    dependsOn(
        ":master:buildxBuild",
        ":agent:buildxBuild",
        ":frontend:buildxBuild"
    )
}

val dockerPushAll by tasks.registering {
    group = "docker"
    description = "Builds and pushes all Docker images. Requires -Ppush=true."
    dependsOn(dockerBuildAll)
}

gradle.taskGraph.whenReady {
    if (hasTask(dockerPushAll.get())) {
        require(findProperty("push")?.toString().toBoolean() == true) {
            "dockerPushAll requires -Ppush=true (drives the buildx `push` flag on each module)"
        }
    }
}

tasks.named("check") {
    dependsOn(":frontend:testFrontend")
}

tasks.register("test") {
    group = "verification"
    description = "Runs all tests (JVM subprojects + frontend). Add -PsystemTest to include system-tests."
    dependsOn(":master:test", ":agent:test", ":common:test", ":frontend:typecheckFrontend", ":frontend:testFrontend")
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

// ---------------------------------------------------------------------------
// Security pins for transitive dependencies
// ---------------------------------------------------------------------------
// These libraries only arrive transitively — Jackson 2 via docker-java and java-jwt,
// Jackson 3 via ktor-openapi, Guava via jwks-rsa, httpclient5/httpcore5 via
// docker-java, Rhino via the Swagger v2 parser, BouncyCastle via docker-java — so
// declaring them as dependencies would misrepresent ownership. They are pinned to the
// first patched version on the four JVM classpaths only, which leaves Gradle's plugin
// and Kotlin compiler classpaths on the versions they ship with.
val securityPinnedModules: Map<String, String> =
    mapOf(
        "com.fasterxml.jackson.core:jackson-core" to libs.versions.jackson.get(),
        "com.fasterxml.jackson.core:jackson-databind" to libs.versions.jackson.get(),
        "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml" to libs.versions.jackson.get(),
        "com.fasterxml.jackson.datatype:jackson-datatype-jsr310" to libs.versions.jackson.get(),
        "tools.jackson.core:jackson-core" to libs.versions.jackson3.get(),
        "tools.jackson.core:jackson-databind" to libs.versions.jackson3.get(),
        "tools.jackson.module:jackson-module-kotlin" to libs.versions.jackson3.get(),
        "com.google.guava:guava" to libs.versions.guava.get(),
        "org.apache.httpcomponents.core5:httpcore5" to libs.versions.httpcore5.get(),
        "org.apache.httpcomponents.core5:httpcore5-h2" to libs.versions.httpcore5.get(),
        "org.apache.httpcomponents.client5:httpclient5" to libs.versions.httpclient5.get(),
        "org.mozilla:rhino" to libs.versions.rhino.get(),
        "org.bouncycastle:bcprov-jdk18on" to libs.versions.bouncycastle.get(),
        "org.bouncycastle:bcpkix-jdk18on" to libs.versions.bouncycastle.get(),
        "org.bouncycastle:bcutil-jdk18on" to libs.versions.bouncycastle.get()
    )

val jvmClasspaths = setOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")

subprojects {
    plugins.withId("java") {
        configurations.configureEach {
            if (name in jvmClasspaths) {
                resolutionStrategy.force(securityPinnedModules.map { (module, version) -> "$module:$version" })
            }
        }
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
