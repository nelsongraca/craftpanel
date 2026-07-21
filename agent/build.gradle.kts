import craftpanel.dockerImageBase
import craftpanel.dockerImageTag
import craftpanel.dockerPushEnabled

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.flowkode.buildx)
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

val dockerGidProvider = providers.provider {
    (findProperty("dockerGid")?.toString())
        ?: run {
            val result = providers.exec {
                commandLine("getent", "group", "docker")
            }
            result.standardOutput.asText.get()
                .trim()
                .split(":")
                .getOrNull(2) ?: "999"
        }
}

dependencies {
    implementation(libs.bundles.grpc.server)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.docker.java.api)
    implementation(libs.docker.java.core)
    implementation(libs.docker.java.transport.httpclient5)
    implementation(libs.logback.classic)
    implementation(libs.koin.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.framework.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

sourceSets.main {
    proto.srcDir("${rootProject.projectDir}/proto")
}

tasks.register<Copy>("stageDocker") {
    dependsOn(tasks.installDist)
    from(layout.buildDirectory.dir("install/agent")) { into("build/install/agent") }
    from(layout.projectDirectory.file("Dockerfile"))
    from(layout.projectDirectory.file("docker-entrypoint.sh"))
    into(layout.buildDirectory.dir("docker"))
}

@Suppress("UNCHECKED_CAST")
val gitVersion = rootProject.extra["gitVersion"] as Provider<String>
val pushEnabled = dockerPushEnabled(project)

buildx {
    imageName = dockerImageBase(project, "agent")
    tags = listOf(dockerImageTag(project))
    context = layout.buildDirectory.dir("docker").get()
    dockerfile = layout.buildDirectory.file("docker/Dockerfile").get().asFile
    buildArgs {
        put("DOCKER_GID", dockerGidProvider.get())
        put("APP_VERSION", gitVersion.get())
    }
    labels { put("org.opencontainers.image.version", gitVersion.get()) }
    push = pushEnabled
    load = !pushEnabled
}

tasks.named("buildxBuild") {
    dependsOn("stageDocker")
    mustRunAfter(tasks.named("assemble"), tasks.named("check"))
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
                classes("io.craftpanel.agent.MainKt")
            }
        }
        total {
            html { title = "CraftPanel Agent" }
            xml { xmlFile = layout.buildDirectory.file("reports/kover/report.xml") }
        }
    }
}
