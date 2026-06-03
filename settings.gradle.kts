rootProject.name = "craftpanel"

include("docs", "master", "agent", "frontend", "fake-server")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}