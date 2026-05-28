package io.craftpanel.master.grpc

import io.craftpanel.master.config.AppConfig
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import org.slf4j.LoggerFactory
import java.io.File

class GrpcServer(private val config: AppConfig, private val controlService: ControlServiceImpl) {

    private val log = LoggerFactory.getLogger(GrpcServer::class.java)
    private lateinit var server: Server

    fun start(): GrpcServer {
        val builder = NettyServerBuilder.forPort(config.grpc.port)
            .addService(controlService)

        if (config.grpc.tlsEnabled) {
            builder.useTransportSecurity(
                File(config.grpc.tlsCertPath),
                File(config.grpc.tlsKeyPath),
            )
            log.info("gRPC TLS enabled (cert: ${config.grpc.tlsCertPath})")
        }
        else {
            log.warn("gRPC TLS disabled — plaintext only, not suitable for production")
        }

        server = builder.build()
            .start()
        log.info("gRPC server started on port ${config.grpc.port}")
        return this
    }

    fun stop() {
        if (::server.isInitialized) {
            server.shutdown()
            log.info("gRPC server stopped")
        }
    }
}
