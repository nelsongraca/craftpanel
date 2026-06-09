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