import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.openapi.generator)
}

kotlin {
    jvmToolchain(25)
}

val openApiSpecFile = rootProject.layout.buildDirectory.file("openapi.json")
val generatedRawDir = layout.buildDirectory.dir("generated/openapi-raw")
val generatedDir = layout.buildDirectory.dir("generated/openapi")

val generateApiClient by tasks.registering(GenerateTask::class) {
    group = "build"
    description = "Generates Kotlin HTTP client from openapi.json"
    dependsOn(":master:generateOpenApiSpec")
    generatorName.set("kotlin")
    validateSpec.set(false)
    inputSpec.set(openApiSpecFile.get().asFile.absolutePath)
    outputDir.set(generatedRawDir.get().asFile.absolutePath)
    apiPackage.set("craftpanel.systemtest.client.api")
    modelPackage.set("craftpanel.systemtest.client.model")
    configOptions.put("library", "jvm-okhttp4")
    configOptions.put("serializationLibrary", "gson")
    configOptions.put("useCoroutines", "true")
    configOptions.put("dateLibrary", "java8")
    globalProperties.put("modelDocs", "false")
    globalProperties.put("apiDocs", "false")
    globalProperties.put("modelTests", "false")
    globalProperties.put("apiTests", "false")
}

// OAG jvm-okhttp4 does not null-guard response.body (ResponseBody?) — patch it via Copy.filter
// so the generated ApiClient.kt compiles under Kotlin strict null safety.
val patchGeneratedClient by tasks.registering(Copy::class) {
    group = "build"
    description = "Copies generated client sources, patching OkHttp ResponseBody null safety"
    dependsOn(generateApiClient)
    from(generatedRawDir)
    into(generatedDir)
    filter { line: String ->
        line.replace(
            "        val body = response.body",
            "        val body = response.body ?: return null"
        )
    }
}

sourceSets {
    test {
        kotlin {
            srcDir(generatedDir.map { it.dir("src/main/kotlin") })
        }
    }
}

tasks.named("compileTestKotlin") {
    dependsOn(patchGeneratedClient)
}

// -- Kover standalone agent + CLI (only resolved when -PwithCoverage is set) --
val koverAgent by configurations.registering {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val koverCli by configurations.registering {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    koverAgent(libs.kover.jvm.agent)
    koverCli(libs.kover.cli)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.logging)
    testImplementation(libs.gson)
    testImplementation(libs.kotlinx.coroutines.core)
}

// Copy the Kover JVM agent JAR into the project build directory.
// Docker bind-mounts must point to a local path; the Gradle module cache
// (deep ~/.gradle/caches/.../hash/... path) can be inaccessible to the
// Docker daemon, resulting in an empty directory mount instead of a file.
val stageKoverAgent by tasks.registering(Copy::class) {
    description = "Copies the Kover JVM agent JAR into the project build directory for Docker bind-mounting"
    from(configurations.named("koverAgent"))
    into(layout.buildDirectory.dir("kover-agent"))
    rename { "kover-jvm-agent.jar" }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("kotest.framework.config.fqn", "craftpanel.systemtest.harness.SystemTestConfig")
    dependsOn(
        ":master:dockerBuildImage",
        ":agent:dockerBuildImage",
        ":fake-server:dockerBuildImage",
    )

    if (project.hasProperty("withCoverage")) {
        // Coverage mode requires project access at execution time, which is not
        // supported by the configuration cache. Opt out so normal runs still cache.
        notCompatibleWithConfigurationCache("Kover coverage is not compatible with the configuration cache")
        dependsOn(stageKoverAgent)
        doFirst {
            val agentJar = project.layout.buildDirectory.file("kover-agent/kover-jvm-agent.jar").get().asFile
            val outputDir = project.layout.buildDirectory.dir("tmp/kover-coverage").get().asFile
                .also { it.mkdirs() }
            systemProperty("kover.agent.jar", agentJar.absolutePath)
            systemProperty("kover.output.dir", outputDir.absolutePath)
        }
    }
}

tasks.named("check") {
    dependsOn.clear()
}

val koverSystemTestReport by tasks.registering(Exec::class) {
    group = "verification"
    description = "Generates Kover coverage report from system test Docker containers"
    dependsOn(tasks.named("test"), ":master:installDist", ":agent:installDist")

    onlyIf { project.hasProperty("withCoverage") }

    // Defer file resolution and command assembly to execution time for CC compatibility.
    doFirst {
        val cliJar = project.configurations.getByName("koverCli")
            .filter { it.name.startsWith("kover-cli") }.singleFile
        val icDir = project.layout.buildDirectory.dir("tmp/kover-coverage").get().asFile
        val icFiles = icDir.walkTopDown().filter { it.isFile && it.extension == "ic" }.toList()
        if (icFiles.isEmpty()) throw StopExecutionException("No .ic coverage files found — skipping report")
        commandLine(buildList {
            add("java")
            add("-jar")
            add(cliJar.absolutePath)
            add("report")
            icFiles.forEach { add(it.absolutePath) }
            add("--classfiles")
            add(project.file("${project.rootProject.projectDir}/master/build/install/master/lib").absolutePath)
            add("--classfiles")
            add(project.file("${project.rootProject.projectDir}/agent/build/install/agent/lib").absolutePath)
            add("--html")
            add(project.layout.buildDirectory.dir("reports/kover/html").get().asFile.absolutePath)
            add("--xml")
            add(project.layout.buildDirectory.file("reports/kover/report.xml").get().asFile.absolutePath)
            add("--title")
            add("CraftPanel System Tests")
            add("--src")
            add(project.file("${project.rootProject.projectDir}/master/src/main/kotlin").absolutePath)
            add("--src")
            add(project.file("${project.rootProject.projectDir}/agent/src/main/kotlin").absolutePath)
        })
    }
}