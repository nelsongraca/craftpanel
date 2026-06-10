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

val coverageOutputDir = layout.buildDirectory.dir("tmp/kover-coverage")

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

tasks.named<Test>("test") {
    useJUnitPlatform()
    maxParallelForks = 1
    systemProperty("kotest.framework.config.fqn", "craftpanel.systemtest.harness.SystemTestConfig")
    dependsOn(
        ":master:dockerBuildImage",
        ":agent:dockerBuildImage",
        ":fake-server:dockerBuildImage",
    )

    val withCoverage = project.hasProperty("withCoverage")
    if (withCoverage) {
        val agentJar = koverAgent.get()
            .filter { it.name.startsWith("kover-jvm-agent") }.singleFile
        val outputDir = coverageOutputDir.get().asFile.also { it.mkdirs() }
        systemProperty("kover.agent.jar", agentJar.absolutePath)
        systemProperty("kover.output.dir", outputDir.absolutePath)
    }
}

tasks.named("check") {
    dependsOn.clear()
}

val koverSystemTestReport by tasks.registering(Exec::class) {
    group = "verification"
    description = "Generates Kover coverage report from system test Docker containers"
    dependsOn(tasks.named("test"), ":master:installDist", ":agent:installDist")

    val cliJar = koverCli.get()
        .filter { it.name.startsWith("kover-cli") }.singleFile
    val icFiles = fileTree(coverageOutputDir) { include("*.ic") }.files

    commandLine(
        buildList {
            add("java")
            add("-jar")
            add(cliJar.absolutePath)
            add("report")
            icFiles.forEach { add(it.absolutePath) }
            add("--classfiles")
            add(file("${rootProject.projectDir}/master/build/install/master/lib").absolutePath)
            add("--classfiles")
            add(file("${rootProject.projectDir}/agent/build/install/agent/lib").absolutePath)
            add("--html")
            add(file("$buildDir/reports/kover/html").absolutePath)
            add("--xml")
            add(file("$buildDir/reports/kover/report.xml").absolutePath)
            add("--title")
            add("CraftPanel System Tests")
            file("${rootProject.projectDir}/master/src/main/kotlin").let {
                add("--src")
                add(it.absolutePath)
            }
            file("${rootProject.projectDir}/agent/src/main/kotlin").let {
                add("--src")
                add(it.absolutePath)
            }
        }
    )

    onlyIf { project.hasProperty("withCoverage") && icFiles.isNotEmpty() }
}