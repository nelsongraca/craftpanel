plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.bmuschko.docker) apply false
    alias(libs.plugins.kover) apply false
}

// ---------------------------------------------------------------------------
// Project-wide properties
// ---------------------------------------------------------------------------
version = project.property("craftpanel_version") as String

val imageVersion: String = findProperty("imageVersion")?.toString()
    ?: findProperty("craftpanel_version")?.toString()
    ?: "latest"

// ---------------------------------------------------------------------------
// Kover CLI for aggregation
// ---------------------------------------------------------------------------
val koverCli by configurations.registering {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    koverCli(libs.kover.cli)
}

val koverAggregateReport by tasks.registering(Exec::class) {
    group = "verification"
    description = "Merge .ic files from master, agent, and system-tests into aggregated report"
    dependsOn(":master:test", ":agent:test", ":master:installDist", ":agent:installDist")
    mustRunAfter(":system-tests:test")
    notCompatibleWithConfigurationCache(".ic files discovered at execution time")

    doFirst {
        val masterIc = file("master/build/kover/bin-reports")
        val agentIc = file("agent/build/kover/bin-reports")
        val systemIc = file("system-tests/build/tmp/kover-coverage")
        val aggregateDir = layout.buildDirectory.dir("reports/kover/aggregated").get().asFile
        aggregateDir.mkdirs()

        val cliJar = koverCli.get()
            .filter { it.name.startsWith("kover-cli") }.singleFile

        val icFiles = buildList {
            if (masterIc.exists()) addAll(fileTree(masterIc) { include("*.ic") }.files)
            if (agentIc.exists()) addAll(fileTree(agentIc) { include("*.ic") }.files)
            if (systemIc.exists()) addAll(fileTree(systemIc) { include("*.ic") }.files)
        }

        if (icFiles.isEmpty()) {
            throw GradleException("No .ic coverage files found anywhere")
        }

        commandLine(buildList {
            add("java"); add("-jar"); add(cliJar.absolutePath); add("report")
            icFiles.forEach { add(it.absolutePath) }
            add("--classfiles")
            add(file("master/build/install/master/lib/master.jar").absolutePath)
            add("--classfiles")
            add(file("agent/build/install/agent/lib/agent.jar").absolutePath)
            add("--exclude"); add("io.craftpanel.proto.*")
            add("--html"); add(File(aggregateDir, "html").absolutePath)
            add("--xml"); add(File(aggregateDir, "report.xml").absolutePath)
            add("--title"); add("CraftPanel JVM (aggregated)")
            add("--src"); add(file("master/src/main/kotlin").absolutePath)
            add("--src"); add(file("agent/src/main/kotlin").absolutePath)
        })
    }
}

// ---------------------------------------------------------------------------
// Docker aggregation tasks
// ---------------------------------------------------------------------------
val dockerBuildAll by tasks.registering {
    group = "docker"
    description = "Builds all Docker images"
    dependsOn(tasks.named("build"))
}

val dockerPushAll by tasks.registering {
    group = "docker"
    description = "Pushes all Docker images"
    dependsOn(dockerBuildAll)
}

subprojects {
    tasks.withType<Test>().configureEach {
        jvmArgs("-Dnet.bytebuddy.experimental=true")
    }
}

// Wire subproject docker tasks into the root aggregators once subprojects configure
subprojects {
    afterEvaluate {
        tasks.findByName("dockerBuildImage")
            ?.let { dockerBuildAll.configure { dependsOn(it) } }
        tasks.findByName("dockerPushImage")
            ?.let { dockerPushAll.configure { dependsOn(it) } }
    }
}