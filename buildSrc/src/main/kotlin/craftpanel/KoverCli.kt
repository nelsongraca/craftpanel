package craftpanel

import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import java.io.File

fun TaskContainer.registerKoverCliReport(
    taskName: String,
    description: String,
    title: String,
    icDirs: List<File>,
    classfiles: List<File>,
    srcDirs: List<File>,
    htmlOut: File,
    xmlOut: File,
    cliClasspath: Iterable<File>,
): TaskProvider<JavaExec> = register(taskName, JavaExec::class.java) {
    notCompatibleWithConfigurationCache(".ic files discovered at execution time")
    group = "verification"
    this.description = description
    classpath = project.files(cliClasspath)
    mainClass.set("kotlinx.kover.cli.MainKt")
    doFirst {
        htmlOut.mkdirs()
        xmlOut.parentFile.mkdirs()
        val icFiles = icDirs.flatMap { dir ->
            dir.listFiles { f -> f.extension == "ic" }?.toList() ?: emptyList()
        }
        check(icFiles.isNotEmpty()) { "No .ic files found in ${icDirs.map { it.absolutePath }}" }
        args = buildList {
            add("report")
            icFiles.forEach { add(it.absolutePath) }
            classfiles.forEach { add("--classfiles"); add(it.absolutePath) }
            srcDirs.forEach { add("--src"); add(it.absolutePath) }
            add("--include"); add("io.craftpanel.*")
            add("--exclude"); add("io.craftpanel.proto.*")
            add("--exclude"); add("*Grpc*")
            add("--exclude"); add("*OuterClass")
            add("--html"); add(htmlOut.absolutePath)
            add("--xml"); add(xmlOut.absolutePath)
            add("--title"); add(title)
        }
    }
}
