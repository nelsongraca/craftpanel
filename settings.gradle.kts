rootProject.name = "craftpanel"

include("docs", "master", "agent", "frontend", "fake-server", "system-tests")

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