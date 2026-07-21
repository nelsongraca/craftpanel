package craftpanel.systemtest.harness

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.ExecCreateCmdResponse
import com.github.dockerjava.core.command.ExecStartResultCallback
import craftpanel.systemtest.client.api.DefaultApi
import io.kotest.core.spec.style.ShouldSpec
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

abstract class BaseSystemTest : ShouldSpec() {

    val api: DefaultApi by lazy { DefaultApi(basePath = SharedStack.masterApiUrl) }
    val docker: DockerClient by lazy { SharedStack.dockerClient }
    val nodeId: String get() = SharedStack.nodeId
    val masterApiUrl: String get() = SharedStack.masterApiUrl
    protected val helper = ServerHelper(api)
    protected val authHelper = AuthHelper(api)
    protected val nodeHelper = NodeHelper(api)

    fun containerName(serverId: String): String = "${SharedStack.containerPrefix}-$serverId"

    /** Runs a command inside a running container and returns its stdout+stderr. Throws if the command exits non-zero. */
    fun execInContainer(containerName: String, vararg cmd: String): String {
        val exec: ExecCreateCmdResponse = docker.execCreateCmd(containerName)
            .withCmd(*cmd)
            .withAttachStdout(true)
            .withAttachStderr(true)
            .exec()
        val output = ByteArrayOutputStream()
        docker.execStartCmd(exec.id)
            .exec(ExecStartResultCallback(output, output))
            .awaitCompletion(30, TimeUnit.SECONDS)
        val text = output.toString(Charsets.UTF_8)
        val exitCode = docker.inspectExecCmd(exec.id).exec().exitCodeLong
        check(exitCode == 0L) { "exec ${cmd.joinToString(" ")} in $containerName exited $exitCode: $text" }
        return text
    }

    init {
        beforeSpec {
            authHelper.login()
        }
        beforeTest {
            authHelper.login()
            nodeHelper.pollUntilActive(nodeId)
        }
    }
}
