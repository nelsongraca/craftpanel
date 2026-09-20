import org.gradle.api.tasks.WriteProperties
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    `java-library`
    id("craftpanel.protobuf-convention")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

sourceSets.main {
    proto.srcDir("${rootProject.projectDir}/proto")
}

// Build hash baked into this jar at build time. Master and agent both read it from the
// shared jar on the classpath, so it reflects the exact commit the image was built from and
// cannot be overridden at runtime via env vars or mounted files.
@Suppress("UNCHECKED_CAST")
val gitVersion = rootProject.extra["gitVersion"] as Provider<String>

val generateBuildInfo by tasks.registering(WriteProperties::class) {
    destinationFile.set(layout.buildDirectory.file("generated/buildinfo/build-info.properties"))
    property("version", gitVersion)
    inputs.property("version", gitVersion)
}

sourceSets.named("main") {
    resources.srcDir(layout.buildDirectory.dir("generated/buildinfo"))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateBuildInfo)
}

dependencies {
    // Generated protobuf/grpc types appear in public signatures, so they must be exported
    // (api, not implementation) for master/agent to compile against :common.
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    api(libs.grpc.kotlin.stub)
    api(libs.protobuf.kotlin)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.framework.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
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
                classes("io.craftpanel.proto.*", "*Grpc*", "*OuterClass")
            }
        }
        total {
            html { title = "CraftPanel Common" }
            xml { xmlFile = layout.buildDirectory.file("reports/kover/report.xml") }
        }
    }
}
