import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage
import craftpanel.dockerImageName

plugins {
    alias(libs.plugins.frontend)
    alias(libs.plugins.bmuschko.docker)
}

frontend {
    nodeVersion.set("22.14.0")
    nodeInstallDirectory.set(layout.projectDirectory.dir(".node"))
    packageJsonDirectory.set(layout.projectDirectory)
    assembleScript.set("run build")
    checkScript.set("run lint")
}

tasks.register<Delete>("cleanFrontend") {
    delete(layout.projectDirectory.dir(".next"))
    delete(layout.projectDirectory.dir("out"))
}

tasks.named("clean") {
    dependsOn("cleanFrontend")
}

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

tasks.register<Exec>("generateApiTypes") {
    group = "build"
    description = "Generates lib/generated/api.ts from the backend OpenAPI spec"
    dependsOn("installFrontend", ":master:generateOpenApiSpec")
    workingDir = layout.projectDirectory.asFile
    commandLine(layout.projectDirectory.file(".node/bin/pnpm").asFile, "run", "generate-api")
    inputs.files(
        rootProject.layout.buildDirectory.file("openapi.json"),
        layout.projectDirectory.file("openapi-ts.config.ts"),
    )
    outputs.dir(layout.projectDirectory.dir("lib/generated"))
}

tasks.named("assembleFrontend") {
    dependsOn("generateApiTypes")
}

tasks.named("assemble") {
    dependsOn("assembleFrontend")
}

val frontendImageName = dockerImageName(project, "frontend")

tasks.register<DockerBuildImage>("dockerBuildImage") {
    group = "docker"
    description = "Builds the Docker image for frontend"
    dependsOn("assembleFrontend")
    mustRunAfter(tasks.named("check"))
    inputDir.set(projectDir)
    dockerFile.set(file("Dockerfile"))
    images.add(frontendImageName)
}

tasks.register<DockerPushImage>("dockerPushImage") {
    group = "docker"
    description = "Pushes the Docker image for frontend"
    dependsOn("dockerBuildImage")
    images.add(frontendImageName)
}
