import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage
import craftpanel.dockerImageName
import craftpanel.dockerBuildEnabled

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.bmuschko.docker)
    id("craftpanel.protobuf-convention")
    application
}

application {
    mainClass.set("io.craftpanel.agent.MainKt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

val dockerGid: String = (findProperty("dockerGid")?.toString())
    ?: run {
        val result = providers.exec {
            commandLine("getent", "group", "docker")
        }
        result.standardOutput.asText.get()
            .trim()
            .split(":")
            .getOrNull(2) ?: "999"
    }

dependencies {
    implementation(libs.bundles.grpc.server)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.docker.java.api)
    implementation(libs.docker.java.core)
    implementation(libs.docker.java.transport.httpclient5)
    implementation(libs.logback.classic)
    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

sourceSets.main {
    proto.srcDir("${rootProject.projectDir}/proto")
}

val agentImageName = dockerImageName(project, "agent")

tasks.register<Copy>("stageDocker") {
    dependsOn(tasks.installDist)
    from(layout.buildDirectory.dir("install/agent")) { into("build/install/agent") }
    from(layout.projectDirectory.file("Dockerfile"))
    from(layout.projectDirectory.file("docker-entrypoint.sh"))
    into(layout.buildDirectory.dir("docker"))
}

tasks.register<DockerBuildImage>("dockerBuildImage") {
    group = "docker"
    description = "Builds the Docker image for agent"
    dependsOn("stageDocker")
    mustRunAfter(tasks.named("assemble"), tasks.named("check"))
    inputDir.set(layout.buildDirectory.dir("docker"))
    dockerFile.set(layout.buildDirectory.file("docker/Dockerfile"))
    images.add(agentImageName)
    buildArgs.put("DOCKER_GID", dockerGid)
    enabled = dockerBuildEnabled(project)
}

tasks.register<DockerPushImage>("dockerPushImage") {
    group = "docker"
    description = "Pushes the Docker image for agent"
    dependsOn("dockerBuildImage")
    images.add(agentImageName)
    enabled = dockerBuildEnabled(project)
}

kover {
    reports {
        filters {
            excludes {
                packages("com.craftpanel")
                classes("*Grpc*", "*OuterClass")
                classes("io.craftpanel.agent.MainKt")
            }
        }
        total {
            html { title = "CraftPanel Agent" }
            xml { xmlFile = layout.buildDirectory.file("reports/kover/report.xml") }
        }
    }
}
