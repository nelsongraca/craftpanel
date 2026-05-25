import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.kover)
    id("com.bmuschko.docker-remote-api") version "10.0.0"
    application
}

application {
    mainClass.set("io.craftpanel.master.MainKt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // Ktor server
    implementation(libs.bundles.ktor.server)

    // Exposed
    implementation(libs.bundles.exposed)
    implementation(libs.exposed.migration.jdbc)

    // gRPC (server side)
    implementation(libs.bundles.grpc.server)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Serialization & datetime
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // Database
    implementation(libs.hikaricp)
    implementation(libs.postgresql)

    // Argon2id password hashing
    implementation(libs.bcprov.jdk18on)

    // Logging
    implementation(libs.logback.classic)

    // Test
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.h2)
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
val imageRegistry: String? = rootProject.findProperty("imageRegistry")?.toString()
val imageVersion: String = rootProject.findProperty("imageVersion")?.toString() ?: "latest"
val imageName = buildString {
    if (imageRegistry != null) append("$imageRegistry/")
    append("craftpanel-master:$imageVersion")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

tasks.register<DockerBuildImage>("dockerBuildImage") {
    group = "docker"
    description = "Builds the Docker image for master"
    dependsOn(tasks.installDist)
    inputDir.set(projectDir)
    dockerFile.set(file("Dockerfile"))
    images.add(imageName)
}

tasks.register<DockerPushImage>("dockerPushImage") {
    group = "docker"
    description = "Pushes the Docker image for master"
    dependsOn("dockerBuildImage")
    images.add(imageName)
}

// ---------------------------------------------------------------------------
// Coverage
// ---------------------------------------------------------------------------
kover {
    reports {
        filters {
            excludes {
                // Protobuf/gRPC generated classes (com.craftpanel.* package from proto files)
                packages("com.craftpanel")
                // gRPC generated stubs
                classes("*Grpc*", "*OuterClass")
                // Application entry point
                classes("io.craftpanel.master.MainKt")
            }
        }
        total {
            html { title = "CraftPanel Master" }
            xml { xmlFile = layout.buildDirectory.file("reports/kover/report.xml") }
        }
    }
}