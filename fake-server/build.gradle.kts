import craftpanel.dockerCacheEnabled

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.flowkode.buildx)
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
// Docker tasks — tagged :test, never pushed to production registry.
// Two images share one Dockerfile, so they're built via BuildxBuildTask
// directly rather than the single-image `buildx {}` extension block.
// ---------------------------------------------------------------------------
val imageRegistry: String? = findProperty("imageRegistry") as String?

fun imageName(suffix: String) = if (imageRegistry != null) "$imageRegistry/$suffix" else suffix
val imageTag = if (imageRegistry != null) "test" else "latest"

val stageDocker by tasks.registering(Copy::class) {
    group = "docker"
    description = "Stages files for Docker build"
    dependsOn(tasks.installDist)
    from(layout.buildDirectory.dir("install/fake-server")) { into("build/install/fake-server") }
    from(layout.projectDirectory.file("docker-entrypoint.sh"))
    from(layout.projectDirectory.file("Dockerfile"))
    into(layout.buildDirectory.dir("docker"))
}

fun com.flowkode.buildx.BuildxBuildTask.configureFakeImage(suffix: String) {
    dependsOn(stageDocker)
    mustRunAfter(tasks.named("check"))
    context.set(layout.buildDirectory.dir("docker"))
    dockerfile.set(layout.buildDirectory.file("docker/Dockerfile"))
    imageName.set(imageName(suffix))
    tags.set(listOf(imageTag))
    platforms.set(emptyList())
    labels.set(emptyMap())
    secrets.set(emptyMap())
    sbom.set(false)
    extraArgs.set(emptyList())
    push.set(false)
    load.set(true)
    if (dockerCacheEnabled(project)) {
        cacheFrom.set("type=gha,scope=fake-server")
        cacheTo.set("type=gha,scope=fake-server,mode=max")
    }
}

val dockerBuildFakeServer by tasks.registering(com.flowkode.buildx.BuildxBuildTask::class) {
    configureFakeImage("craftpanel-fake-server")
    buildArgs.set(emptyMap())
}

val dockerBuildFakeProxy by tasks.registering(com.flowkode.buildx.BuildxBuildTask::class) {
    configureFakeImage("craftpanel-fake-proxy")
    mustRunAfter(dockerBuildFakeServer)
    buildArgs.set(
        mapOf(
            "SERVER_NAME" to "CraftPanel Fake Proxy",
            "MOTD" to "A fake Minecraft proxy",
            "MAX_PLAYERS" to "100",
            "STOP_COMMAND" to "end"
        )
    )
}

// Single entry point picked up by root dockerBuildAll aggregation
val dockerBuildImage by tasks.registering {
    group = "docker"
    dependsOn(dockerBuildFakeServer, dockerBuildFakeProxy)
}
