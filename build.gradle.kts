plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.protobuf) apply false
    id("com.bmuschko.docker-remote-api") version "10.0.0" apply false
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
}

val dockerPushAll by tasks.registering {
    group = "docker"
    description = "Pushes all Docker images"
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