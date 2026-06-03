import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    id("com.bmuschko.docker-remote-api") version "10.0.0"
}

group = "craftpanel"

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("craftpanel.fakeserver.MainKt")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}

tasks.jar {
    archiveBaseName.set("craftpanel-fake-server")
    archiveVersion.set("test")
    manifest {
        attributes["Main-Class"] = "craftpanel.fakeserver.MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// ---------------------------------------------------------------------------
// Docker tasks — tagged :test, never pushed to production registry
// ---------------------------------------------------------------------------
val imageRegistry: String? = findProperty("imageRegistry") as String?

fun imageTag(suffix: String) =
    if (imageRegistry != null) "$imageRegistry/$suffix:test" else "$suffix:test"

val dockerBuildFakeServer by tasks.registering(DockerBuildImage::class) {
    dependsOn(tasks.jar)
    inputDir.set(projectDir)
    dockerFile.set(file("Dockerfile"))
    images.add(imageTag("craftpanel-fake-server"))
}

val dockerBuildFakeProxy by tasks.registering(DockerBuildImage::class) {
    dependsOn(tasks.jar)
    mustRunAfter(dockerBuildFakeServer)
    inputDir.set(projectDir)
    dockerFile.set(file("Dockerfile.proxy"))
    images.add(imageTag("craftpanel-fake-proxy"))
}

// Single entry point picked up by root dockerBuildAll aggregation
val dockerBuildImage by tasks.registering {
    group = "docker"
    dependsOn(dockerBuildFakeServer, dockerBuildFakeProxy)
}
