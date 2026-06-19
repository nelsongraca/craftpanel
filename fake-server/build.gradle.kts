import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.bmuschko.docker)
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

// ---------------------------------------------------------------------------
// Docker tasks — tagged :test, never pushed to production registry
// ---------------------------------------------------------------------------
val imageRegistry: String? = findProperty("imageRegistry") as String?

fun imageTag(suffix: String) =
    if (imageRegistry != null) "$imageRegistry/$suffix:test" else "$suffix:latest"

val stageDocker by tasks.registering(Copy::class) {
    group = "docker"
    description = "Stages files for Docker build"
    dependsOn(tasks.installDist)
    from(layout.buildDirectory.dir("install/fake-server")) { into("build/install/fake-server") }
    from(layout.projectDirectory.file("docker-entrypoint.sh"))
    from(layout.projectDirectory.file("Dockerfile"))
    into(layout.buildDirectory.dir("docker"))
}

val dockerBuildFakeServer by tasks.registering(DockerBuildImage::class) {
    dependsOn(stageDocker)
    mustRunAfter(tasks.named("check"))
    inputDir.set(layout.buildDirectory.dir("docker"))
    dockerFile.set(layout.buildDirectory.file("docker/Dockerfile"))
    images.add(imageTag("craftpanel-fake-server"))
}

val dockerBuildFakeProxy by tasks.registering(DockerBuildImage::class) {
    dependsOn(stageDocker)
    mustRunAfter(tasks.named("check"))
    mustRunAfter(dockerBuildFakeServer)
    inputDir.set(layout.buildDirectory.dir("docker"))
    dockerFile.set(layout.buildDirectory.file("docker/Dockerfile"))
    images.add(imageTag("craftpanel-fake-proxy"))
    buildArgs.set(mapOf(
        "SERVER_NAME" to "CraftPanel Fake Proxy",
        "MOTD" to "A fake Minecraft proxy",
        "MAX_PLAYERS" to "100",
        "STOP_COMMAND" to "end"
    ))
}

// Single entry point picked up by root dockerBuildAll aggregation
val dockerBuildImage by tasks.registering {
    group = "docker"
    dependsOn(dockerBuildFakeServer, dockerBuildFakeProxy)
}
