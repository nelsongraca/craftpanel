import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
    id("com.bmuschko.docker-remote-api") version "10.0.0"
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

// ---------------------------------------------------------------------------
// Docker GID detection for the agent (needs docker socket access)
// ---------------------------------------------------------------------------
val dockerGid: String = (findProperty("dockerGid")?.toString())
    ?: run {
        val result = providers.exec {
            commandLine("getent", "group", "docker")
        }
        // getent output: "docker:x:GID:..."
        result.standardOutput.asText.get().trim().split(":").getOrNull(2) ?: "999"
    }

dependencies {
    // gRPC client stubs
    implementation(libs.bundles.grpc.server)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Docker Java client
    implementation(libs.docker.java.api)
    implementation(libs.docker.java.core)
    implementation(libs.docker.java.transport.httpclient5)

    // Logging
    implementation(libs.logback.classic)
}

// ---------------------------------------------------------------------------
// Protobuf code generation
// ---------------------------------------------------------------------------
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val protocVersion = catalog.findVersion("protobuf").get().requiredVersion
val grpcVersion = catalog.findVersion("grpc").get().requiredVersion
val grpcKotlinVersion = catalog.findVersion("grpc-kotlin").get().requiredVersion

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protocVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                create("grpc")
                create("grpckt")
            }
            builtins {
                create("kotlin")
            }
        }
    }
    sourceSets {
        main {
            proto {
                srcDir("${rootProject.projectDir}/proto")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Docker
// ---------------------------------------------------------------------------
val imageRegistry: String = rootProject.findProperty("imageRegistry")?.toString() ?: "ghcr.io/nelsongraca/craftpanel"
val imageVersion: String = rootProject.findProperty("imageVersion")?.toString() ?: "latest"
val imageName = "$imageRegistry/agent:$imageVersion"

tasks.register<DockerBuildImage>("dockerBuildImage") {
    group = "docker"
    description = "Builds the Docker image for agent"
    dependsOn(tasks.installDist)
    inputDir.set(projectDir)
    dockerFile.set(file("Dockerfile"))
    images.add(imageName)
    buildArgs.put("DOCKER_GID", dockerGid)
}

tasks.register<DockerPushImage>("dockerPushImage") {
    group = "docker"
    description = "Pushes the Docker image for agent"
    dependsOn("dockerBuildImage")
    images.add(imageName)
}