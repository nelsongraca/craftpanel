package craftpanel

import org.gradle.api.Project

fun dockerImageName(project: Project, suffix: String): String {
    val registry = project.rootProject.findProperty("imageRegistry")?.toString()
        ?: "ghcr.io/nelsongraca/craftpanel"
    val version = project.rootProject.findProperty("imageVersion")?.toString()
        ?: "latest"
    return "$registry/$suffix:$version"
}

fun dockerBuildEnabled(project: Project): Boolean =
    project.rootProject.hasProperty("dockerBuild")
