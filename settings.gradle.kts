rootProject.name = "craftpanel"

include("docs", "common", "master", "agent", "frontend", "fake-server", "system-tests")

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
