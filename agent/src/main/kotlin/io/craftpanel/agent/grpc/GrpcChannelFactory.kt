package io.craftpanel.agent.grpc

import io.craftpanel.agent.config.AgentConfig
import io.grpc.ManagedChannel
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NettyChannelBuilder
import java.io.File

object GrpcChannelFactory {

    fun create(config: AgentConfig): ManagedChannel {
        val builder = NettyChannelBuilder.forAddress(config.masterAddress, config.masterPort)

        if (config.tlsEnabled) {
            val sslContext = GrpcSslContexts.forClient()
                .trustManager(File(config.tlsCertPath))
                .build()
            builder.sslContext(sslContext)
        } else {
            builder.usePlaintext()
        }

        return builder.build()
    }
}
