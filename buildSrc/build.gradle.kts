plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.protobuf.gradle.plugin)
    implementation(libs.git.changelog.lib)
}
