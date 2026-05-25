import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage

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
val imageRegistry: String? = findProperty("imageRegistry")?.toString()
val imageVersion: String = findProperty("imageVersion")?.toString() ?: "latest"

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
        tasks.findByName("dockerBuildImage")?.let { dockerBuildAll.configure { dependsOn(it) } }
        tasks.findByName("dockerPushImage")?.let { dockerPushAll.configure { dependsOn(it) } }
    }
}