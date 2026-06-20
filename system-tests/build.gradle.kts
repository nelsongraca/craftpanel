import craftpanel.registerKoverCliReport
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.openapi.generator)
}

kotlin {
    jvmToolchain(25)
}

val openApiSpecFile = rootProject.layout.buildDirectory.file("openapi.json")
val generatedDir = layout.buildDirectory.dir("generated/openapi").get().asFile

val generateApiClient by tasks.registering(GenerateTask::class) {
    notCompatibleWithConfigurationCache("Generated code is patched in doLast")
    group = "build"
    description = "Generates Kotlin HTTP client from openapi.json"
    dependsOn(":master:generateOpenApiSpec")
    generatorName.set("kotlin")
    validateSpec.set(false)
    inputSpec.set(openApiSpecFile.get().asFile.absolutePath)
    outputDir.set(generatedDir.absolutePath)
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

    // OAG jvm-okhttp4 does not null-guard response.body (ResponseBody?) — patch
    // so the generated ApiClient.kt compiles under Kotlin strict null safety.
    doLast {
        val apiClientFile = file("${generatedDir}/src/main/kotlin/craftpanel/systemtest/client/api/ApiClient.kt")
        if (apiClientFile.exists()) {
            apiClientFile.writeText(apiClientFile.readText().replace(
                "val body = response.body",
                "val body = response.body ?: return null"
            ))
        }
    }
}

sourceSets {
    test {
        kotlin {
            srcDir("${generatedDir}/src/main/kotlin")
        }
    }
}

tasks.named("compileTestKotlin") {
    dependsOn(generateApiClient)
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
        outputs.cacheIf { false }
    }
}

// System tests need Docker — don't include in default check lifecycle
tasks.named("check") {
    dependsOn.clear()
}

val masterJarFile = rootProject.layout.projectDirectory.file("master/build/libs/master.jar")
val agentJarFile = rootProject.layout.projectDirectory.file("agent/build/libs/agent.jar")
val masterSrcDir = rootProject.layout.projectDirectory.dir("master/src/main/kotlin")
val agentSrcDir = rootProject.layout.projectDirectory.dir("agent/src/main/kotlin")

val classfiles = listOf(masterJarFile.asFile, agentJarFile.asFile)
val srcDirs = listOf(masterSrcDir.asFile, agentSrcDir.asFile)
val cliFiles = files(koverCli)

tasks.registerKoverCliReport(
    taskName = "koverSystemTestReport",
    description = "Generates HTML+XML coverage report from system-test .ic files via Kover CLI",
    title = "CraftPanel System Tests",
    icDirs = listOf(coverageOutputDir.get().asFile),
    classfiles = classfiles,
    srcDirs = srcDirs,
    htmlOut = layout.buildDirectory.dir("reports/kover/system-tests/html").get().asFile,
    xmlOut = layout.buildDirectory.file("reports/kover/system-tests/report.xml").get().asFile,
    cliClasspath = cliFiles,
).configure { enabled = project.hasProperty("withCoverage"); mustRunAfter("test") }

tasks.registerKoverCliReport(
    taskName = "koverMergedReport",
    description = "Generates merged HTML+XML coverage report (unit + system tests) via Kover CLI",
    title = "CraftPanel (merged)",
    icDirs = listOf(
        coverageOutputDir.get().asFile,
        rootProject.layout.projectDirectory.dir("master/build/kover/bin-reports").asFile,
        rootProject.layout.projectDirectory.dir("agent/build/kover/bin-reports").asFile,
    ),
    classfiles = classfiles,
    srcDirs = srcDirs,
    htmlOut = rootProject.layout.buildDirectory.dir("reports/kover/merged/html").get().asFile,
    xmlOut = rootProject.layout.buildDirectory.file("reports/kover/merged/report.xml").get().asFile,
    cliClasspath = cliFiles,
).configure { enabled = project.hasProperty("withCoverage"); mustRunAfter(":master:test", ":agent:test", ":system-tests:test") }

if (project.hasProperty("withCoverage")) {
    tasks.named("test") {
        finalizedBy("koverSystemTestReport")
    }
}

