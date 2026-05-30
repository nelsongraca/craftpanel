package io.craftpanel.agent.grpc

import io.craftpanel.agent.auth.NodeKeyStore
import io.craftpanel.agent.config.AgentConfig
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import org.slf4j.LoggerFactory
import java.security.MessageDigest

class DataServiceAuthInterceptor(private val config: AgentConfig) : ServerInterceptor {

    private val log = LoggerFactory.getLogger(DataServiceAuthInterceptor::class.java)

    companion object {

        val DATA_TOKEN_KEY: Metadata.Key<String> =
            Metadata.Key.of("x-craftpanel-data-token", Metadata.ASCII_STRING_MARSHALLER)
    }

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val storedHash = NodeKeyStore.readDataTokenHash(config.dataTokenFilePath)

        if (storedHash == null) {
            if (config.profile == "dev") {
                log.warn("DataService: no data-token hash on disk — skipping auth check (dev only)")
                return next.startCall(call, headers)
            }
            log.error("DataService: no data-token hash on disk and not in dev profile — rejecting call")
            call.close(Status.UNAUTHENTICATED.withDescription("DataService not yet authenticated with master"), Metadata())
            return NoOpListener()
        }

        val presented = headers.get(DATA_TOKEN_KEY)
        if (presented.isNullOrBlank()) {
            call.close(Status.UNAUTHENTICATED.withDescription("Missing data token"), Metadata())
            return NoOpListener()
        }

        val presentedHash = sha256Hex(presented)
        if (!MessageDigest.isEqual(presentedHash.toByteArray(), storedHash.toByteArray())) {
            log.warn("DataService: invalid data token presented")
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid data token"), Metadata())
            return NoOpListener()
        }

        return next.startCall(call, headers)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private class NoOpListener<ReqT> : ServerCall.Listener<ReqT>()
}
