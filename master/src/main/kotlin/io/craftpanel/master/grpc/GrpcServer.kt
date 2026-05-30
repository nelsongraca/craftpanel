package io.craftpanel.master.grpc

import io.craftpanel.master.config.AppConfig
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.security.Security

class GrpcServer(private val config: AppConfig, private val controlService: ControlServiceImpl) {

    private val log = LoggerFactory.getLogger(GrpcServer::class.java)
    private lateinit var server: Server
    private var certManager: CertificateManager? = null

    fun start(): GrpcServer {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }

        val builder = NettyServerBuilder.forPort(config.grpc.port)
            .addService(controlService)

        when {
            config.grpc.tlsEnabled -> {
                builder.useTransportSecurity(
                    File(config.grpc.tlsCertPath),
                    File(config.grpc.tlsKeyPath),
                )
                log.info("gRPC TLS enabled — using provided cert: ${config.grpc.tlsCertPath}")
            }

            config.profile == "dev" -> {
                log.warn("gRPC plaintext mode — DEV ONLY, not suitable for production")
            }

            else -> {
                val mgr = CertificateManager(config.grpc.certStorePath, config.grpc.tlsSans)
                certManager = mgr
                val certs = mgr.loadOrGenerate()
                builder.useTransportSecurity(
                    ByteArrayInputStream(certs.serverCertPem.toByteArray()),
                    ByteArrayInputStream(certs.serverKeyPem.toByteArray()),
                )
                log.info("gRPC TLS enabled — auto-generated cert (store: ${config.grpc.certStorePath})")
            }
        }

        server = builder.build().start()
        log.info("gRPC server started on port ${config.grpc.port}")
        return this
    }

    /** Returns the PEM CA cert for delivery to registering agents, or null if BYOC mode. */
    fun readCaCertPem(): String? = certManager?.readCaCertPem()

    fun stop() {
        if (::server.isInitialized) {
            server.shutdown()
            log.info("gRPC server stopped")
        }
    }
}
