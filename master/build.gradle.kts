import craftpanel.dockerCacheEnabled
import craftpanel.dockerImageBase
import craftpanel.dockerImageTag
import craftpanel.dockerPushEnabled

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kover)
    alias(libs.plugins.flowkode.buildx)
    id("craftpanel.protobuf-convention")
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
    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.exposed)
    implementation(libs.exposed.migration.jdbc)
    implementation(libs.bundles.grpc.server)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.openapi)
    implementation(libs.ktor.swagger.ui)
    implementation(libs.schema.kenerator.core)
    implementation(libs.schema.kenerator.serialization)
    implementation(libs.schema.kenerator.swagger)
    implementation(libs.cron.utils)
    implementation(libs.bcprov.jdk18on)
    implementation(libs.bcpkix.jdk18on)
    implementation(libs.docker.java.api)
    implementation(libs.docker.java.core)
    implementation(libs.docker.java.transport.httpclient5)
    implementation(libs.logback.classic)
    implementation(libs.bundles.koin.ktor)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.framework.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.h2)
    testImplementation(libs.koin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>()
    .configureEach {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
        }
    }

sourceSets.main {
    proto.srcDir("${rootProject.projectDir}/proto")
}

tasks.register<Copy>("stageDocker") {
    dependsOn(tasks.installDist)
    from(layout.buildDirectory.dir("install/master")) { into("build/install/master") }
    from(layout.projectDirectory.file("Dockerfile"))
    from(layout.projectDirectory.file("docker-entrypoint.sh"))
    into(layout.buildDirectory.dir("docker"))
}

@Suppress("UNCHECKED_CAST")
val gitVersion = rootProject.extra["gitVersion"] as Provider<String>

val pushEnabled = dockerPushEnabled(project)

buildx {
    imageName = dockerImageBase(project, "master")
    tags = listOf(dockerImageTag(project))
    context = layout.buildDirectory.dir("docker")
        .get()
    dockerfile = layout.buildDirectory.file("docker/Dockerfile")
        .get().asFile
    buildArgs { put("APP_VERSION", gitVersion.get()) }
    labels { put("org.opencontainers.image.version", gitVersion.get()) }
    push = pushEnabled
    load = !pushEnabled
    if (dockerCacheEnabled(project)) {
        cacheFrom.set("type=gha,scope=master")
        cacheTo.set("type=gha,scope=master,mode=max")
    }
}

tasks.named("buildxBuild") {
    dependsOn("stageDocker")
    mustRunAfter(tasks.named("assemble"), tasks.named("check"))
}

val openApiOutputFile = rootProject.layout.buildDirectory.file("openapi.json")

tasks.withType<Test> {
    useJUnitPlatform()
}

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

if (project.hasProperty("withCoverage")) {
    tasks.named("test") {
        finalizedBy("koverHtmlReport", "koverXmlReport")
    }
}

kover {
    if (!project.hasProperty("withCoverage")) {
        currentProject {
            instrumentation {
                disabledForTestTasks.add("test")
            }
        }
    }
    reports {
        filters {
            excludes {
                packages("io.craftpanel.proto")
                classes("*Grpc*", "*OuterClass")
                classes("io.craftpanel.master.MainKt")
            }
        }
        total {
            html { title = "CraftPanel Master" }
            xml { xmlFile = layout.buildDirectory.file("reports/kover/report.xml") }
        }
    }
}
