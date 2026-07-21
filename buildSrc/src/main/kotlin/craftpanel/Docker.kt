package craftpanel

import org.gradle.api.Project

fun dockerImageBase(project: Project, suffix: String): String {
    val registry = project.rootProject.findProperty("imageRegistry")
        ?.toString()
        ?: "ghcr.io/nelsongraca/craftpanel"
    return "$registry/$suffix"
}

fun dockerImageTag(project: Project): String =
    project.rootProject.findProperty("imageVersion")
        ?.toString() ?: "latest"

fun dockerPushEnabled(project: Project): Boolean =
    project.rootProject.findProperty("push")
        ?.toString()
        .toBoolean()

fun dockerCacheEnabled(project: Project): Boolean =
    project.rootProject.findProperty("dockerCache")
        ?.toString()
        .toBoolean()
