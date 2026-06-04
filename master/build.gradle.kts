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

    // OpenAPI spec generation
    implementation(libs.ktor.openapi)
    implementation(libs.ktor.swagger.ui)
    // Expose schema-kenerator on compile classpath so SchemaGenerator.kotlinx() resolves
    implementation(libs.schema.kenerator.core)
    implementation(libs.schema.kenerator.serialization)
    implementation(libs.schema.kenerator.swagger)

    // Ktor HTTP client (Cloudflare DNS API)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Cron expression parsing
    implementation(libs.cron.utils)

    // Argon2id password hashing + X.509 cert generation
    implementation(libs.bcprov.jdk18on)
    implementation(libs.bcpkix.jdk18on)

    // Logging
    implementation(libs.logback.classic)

    // Test
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.h2)
}

// ---------------------------------------------------------------------------
// Protobuf code generation
// ---------------------------------------------------------------------------
val catalog = extensions.getByType<VersionCatalogsExtension>()
    .named("libs")
val protocVersion = catalog.findVersion("protobuf")
    .get().requiredVersion
val grpcVersion = catalog.findVersion("grpc")
    .get().requiredVersion
val grpcKotlinVersion = catalog.findVersion("grpc-kotlin")
    .get().requiredVersion

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
val imageRegistry: String = rootProject.findProperty("imageRegistry")
    ?.toString() ?: "ghcr.io/nelsongraca/craftpanel"
val imageVersion: String = rootProject.findProperty("imageVersion")
    ?.toString() ?: "latest"
val imageName = "$imageRegistry/master:$imageVersion"

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>()
    .configureEach {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
        }
    }

val dockerContextDir = layout.buildDirectory.dir("docker-context")

val prepareDockerContext by tasks.registering(Copy::class) {
    group = "docker"
    description = "Stages files needed for the Docker build into an isolated context directory"
    dependsOn(tasks.installDist)
    from(file("Dockerfile"))
    from(file("docker-entrypoint.sh"))
    from(layout.buildDirectory.dir("install/master")) {
        into("build/install/master")
    }
    into(dockerContextDir)
}

tasks.register<DockerBuildImage>("dockerBuildImage") {
    group = "docker"
    description = "Builds the Docker image for master"
    dependsOn(prepareDockerContext)
    inputDir.set(dockerContextDir)
    dockerFile.set(dockerContextDir.map { it.file("Dockerfile") })
    images.add(imageName)
}

tasks.register<DockerPushImage>("dockerPushImage") {
    group = "docker"
    description = "Pushes the Docker image for master"
    dependsOn("dockerBuildImage")
    images.add(imageName)
}

// ---------------------------------------------------------------------------
// OpenAPI spec generation
// ---------------------------------------------------------------------------
val openApiOutputFile = rootProject.layout.buildDirectory.file("openapi.json")

tasks.named<Test>("test") {
    exclude("**/OpenApiSpecTask*")
}

tasks.register<Test>("generateOpenApiSpec") {
    group = "build"
    description = "Generates openapi.json at the repo root via a Ktor testApplication"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("io.craftpanel.master.OpenApiSpecTask") }
    systemProperty("openapi.output", openApiOutputFile.get().asFile.absolutePath)
    outputs.file(openApiOutputFile)
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