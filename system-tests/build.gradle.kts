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

dependencies {
    // Kotest — framework-datatest merged into core in Kotest 6; no separate artifact
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)

    // Coroutines
    testImplementation(libs.kotlinx.coroutines.test)

    // Testcontainers
    testImplementation(libs.testcontainers.core)

    // Generated client runtime
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.logging)
    testImplementation(libs.gson)
    testImplementation(libs.kotlinx.coroutines.core)
}

val systemTestsEnabled = project.hasProperty("systemTests")

tasks.named<Test>("test") {
    useJUnitPlatform()
    // Gate execution so ./gradlew test without -PsystemTests skips this subproject
    onlyIf("requires -PsystemTests flag") { project.hasProperty("systemTests") }
}

// Exclude from check lifecycle unless -PsystemTests is set.
// The kotlin-jvm plugin wires check → test via a JvmTestSuite provider whose toString is
// "provider(TestSuite 'test', ...)", so we match on both forms.
if (!systemTestsEnabled) {
    afterEvaluate {
        tasks.named("check").configure {
            setDependsOn(dependsOn.filterNot { dep ->
                dep.toString().let { s -> s == "test" || s.contains("TestSuite 'test'") }
            })
        }
    }
}

// Explicit alias for CI — always runs regardless of flag
val systemTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the Kotest system test suite (requires running Docker images)"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
}
