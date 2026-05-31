package io.craftpanel.agent.grpc

import io.craftpanel.agent.auth.NodeKeyStore
import io.craftpanel.agent.config.AgentConfig
import io.grpc.ManagedChannel
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NettyChannelBuilder
import java.io.ByteArrayInputStream
import java.io.File

object GrpcChannelFactory {

    fun create(config: AgentConfig): ManagedChannel {
        val builder = NettyChannelBuilder.forAddress(config.masterAddress, config.masterPort)

        val certPem: String? = when {
            config.tlsEnabled -> File(config.tlsCertPath).readText()
            else -> NodeKeyStore.readCaCert(config.caCertFilePath)
        }

        if (certPem != null) {
            val sslContext = GrpcSslContexts.forClient()
                .trustManager(ByteArrayInputStream(certPem.toByteArray()))
                .build()
            builder.sslContext(sslContext)
        } else {
            check(config.profile == "dev") {
                "gRPC TLS is required outside dev profile — set GRPC_TLS_CERT or mount master's grpc-ca.crt at ${config.caCertFilePath}"
            }
            builder.usePlaintext()
        }

        return builder.build()
    }
}
