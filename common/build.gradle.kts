plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
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
}
