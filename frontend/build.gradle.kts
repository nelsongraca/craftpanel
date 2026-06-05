import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage

plugins {
    alias(libs.plugins.frontend)
    id("com.bmuschko.docker-remote-api") version "10.0.0"
}

frontend {
    nodeVersion.set("22.14.0")
    nodeInstallDirectory.set(layout.projectDirectory.dir(".node"))
    packageJsonDirectory.set(layout.projectDirectory)
    assembleScript.set("run build")
    checkScript.set("run lint")
}

// ---------------------------------------------------------------------------
// Clean: delete Next.js build output directories
// ---------------------------------------------------------------------------
tasks.register<Delete>("cleanFrontend") {
    delete(layout.projectDirectory.dir(".next"))
    delete(layout.projectDirectory.dir("out"))
}

tasks.named("clean") {
    dependsOn("cleanFrontend")
}

// ---------------------------------------------------------------------------
// Test: run vitest via pnpm
// ---------------------------------------------------------------------------
tasks.register<Exec>("testFrontend") {
    group = "verification"
    description = "Runs frontend unit tests via vitest"
    dependsOn("installFrontend")
    workingDir = layout.projectDirectory.asFile
    commandLine(layout.projectDirectory.file(".node/bin/pnpm").asFile, "run", "test")
}

tasks.named("check") {
    dependsOn("testFrontend")
}

// ---------------------------------------------------------------------------
// API type generation: run openapi-typescript after backend spec is generated
// ---------------------------------------------------------------------------
tasks.register<Exec>("generateApiTypes") {
    group = "build"
    description = "Generates lib/generated/api.ts from the backend OpenAPI spec"
    dependsOn("installFrontend", ":master:generateOpenApiSpec")
    workingDir = layout.projectDirectory.asFile
    // Use pnpm from the node distribution installed by the frontend plugin
    commandLine(layout.projectDirectory.file(".node/bin/pnpm").asFile, "run", "generate-api")
    inputs.files(
        rootProject.layout.buildDirectory.file("openapi.json"),
        layout.projectDirectory.file("openapi-ts.config.ts"),
    )
    outputs.dir(layout.projectDirectory.dir("lib/generated"))
}

// ---------------------------------------------------------------------------
// Wire into Gradle lifecycle
// ---------------------------------------------------------------------------
tasks.named("assembleFrontend") {
    dependsOn("generateApiTypes")
}

tasks.named("assemble") {
    dependsOn("assembleFrontend")
}

// ---------------------------------------------------------------------------
// Docker
// ---------------------------------------------------------------------------
val imageRegistry: String = rootProject.findProperty("imageRegistry")
    ?.toString() ?: "ghcr.io/nelsongraca/craftpanel"
val imageVersion: String = rootProject.findProperty("imageVersion")
    ?.toString() ?: "latest"
val imageName = "$imageRegistry/frontend:$imageVersion"

tasks.register<DockerBuildImage>("dockerBuildImage") {
    group = "docker"
    description = "Builds the Docker image for frontend"
    dependsOn("assembleFrontend")
    mustRunAfter(tasks.named("check"))
    inputDir.set(projectDir)
    dockerFile.set(file("Dockerfile"))
    images.add(imageName)
}

tasks.register<DockerPushImage>("dockerPushImage") {
    group = "docker"
    description = "Pushes the Docker image for frontend"
    dependsOn("dockerBuildImage")
    images.add(imageName)
}