import ru.vyarus.gradle.plugin.mkdocs.task.MkdocsTask

plugins {
    kotlin("jvm")
    id("ru.vyarus.mkdocs") version "4.0.1"
}

group = project.property("maven_group")
    .toString()
version = project.property("mod_version")
    .toString()


base {
    archivesName = project.property("archives_base_name")
        .toString()
}
val targetJavaVersion = 25

java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    withSourcesJar()
}
kotlin {
    jvmToolchain(25)
}

repositories {
   mavenCentral()
}

dependencies {
}

configurations {
}

tasks.withType<JavaCompile>()
    .configureEach {
        options.encoding = "UTF-8"
        options.release.set(targetJavaVersion)
    }

python {
    pip("mkdocs:1.6.1")
    pip("mkdocs-material:9.7.6")
    pip("mkdocs-print-site-plugin:2.8.0")
}

mkdocs {
    sourcesDir = "./"
    strict = true
}

tasks.register<MkdocsTask>("serveRemote") {
    description = "Serve MkDocs documentation on all network interfaces, making it accessible remotely."
    command = "serve -a 0.0.0.0:8000"
}
