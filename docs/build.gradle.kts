import ru.vyarus.gradle.plugin.mkdocs.task.MkdocsTask

plugins {
    alias(libs.plugins.mkdocs)
}

python {
    pip("mkdocs:1.6.1")
    pip("mkdocs-material:9.7.6")
    pip("mkdocs-print-site-plugin:2.8.0")
}

mkdocs {
    sourcesDir = "./"
    strict = true
    devPort = 4000

}

tasks.register<MkdocsTask>("serveRemote") {
    description = "Serve MkDocs documentation on all network interfaces, making it accessible remotely."
    command = "serve -a 0.0.0.0:8000"
}
