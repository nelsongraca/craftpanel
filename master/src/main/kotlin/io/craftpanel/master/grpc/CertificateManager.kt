package io.craftpanel.master.grpc

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.io.StringWriter
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

data class GeneratedCerts(
    val caCertPem: String,
    val caKeyPem: String,
    val serverCertPem: String,
    val serverKeyPem: String,
)

class CertificateManager(
    private val certStorePath: String,
    private val additionalSans: List<String> = emptyList(),
) {

    private val log = LoggerFactory.getLogger(CertificateManager::class.java)

    private val caCertFile get() = File(certStorePath, "grpc-ca.crt")
    private val caKeyFile get() = File(certStorePath, "grpc-ca.key")
    private val serverCertFile get() = File(certStorePath, "grpc-server.crt")
    private val serverKeyFile get() = File(certStorePath, "grpc-server.key")

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun loadOrGenerate(): GeneratedCerts {
        if (caCertFile.exists() && caKeyFile.exists() && serverCertFile.exists() && serverKeyFile.exists()) {
            log.info("Loading existing gRPC TLS certs from $certStorePath")
            return GeneratedCerts(
                caCertPem = caCertFile.readText(),
                caKeyPem = caKeyFile.readText(),
                serverCertPem = serverCertFile.readText(),
                serverKeyPem = serverKeyFile.readText(),
            )
        }

        log.info("Generating self-signed gRPC TLS CA and server certificate")
        val certs = generate()
        persist(certs)
        log.info("gRPC TLS certs generated and saved to $certStorePath — copy grpc-ca.crt to each agent node")
        return certs
    }

    fun readCaCertPem(): String = caCertFile.readText()

    private fun generate(): GeneratedCerts {
        val caKeyPair = generateKeyPair()
        val serverKeyPair = generateKeyPair()

        val now = System.currentTimeMillis()
        val tenYearsMs = TimeUnit.DAYS.toMillis(3650)
        val notBefore = Date(now)
        val notAfter = Date(now + tenYearsMs)

        val caName = X500Name("CN=CraftPanel gRPC CA,O=CraftPanel,C=US")
        val extUtils = JcaX509ExtensionUtils()

        // CA cert: self-signed, BasicConstraints CA=true
        val caCertBuilder = JcaX509v3CertificateBuilder(
            caName,
            BigInteger.ONE,
            notBefore,
            notAfter,
            caName,
            caKeyPair.public,
        )
        caCertBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        caCertBuilder.addExtension(
            Extension.subjectKeyIdentifier, false,
            extUtils.createSubjectKeyIdentifier(caKeyPair.public),
        )

        val caSigner = JcaContentSignerBuilder("SHA256withRSA").build(caKeyPair.private)
        val caCert = JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(caCertBuilder.build(caSigner))

        // Server cert: signed by CA
        val serverName = X500Name("CN=CraftPanel gRPC Server,O=CraftPanel,C=US")
        val serverCertBuilder = JcaX509v3CertificateBuilder(
            caName,
            BigInteger.TWO,
            notBefore,
            notAfter,
            serverName,
            serverKeyPair.public,
        )
        serverCertBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        serverCertBuilder.addExtension(
            Extension.subjectKeyIdentifier, false,
            extUtils.createSubjectKeyIdentifier(serverKeyPair.public),
        )
        serverCertBuilder.addExtension(
            Extension.authorityKeyIdentifier, false,
            extUtils.createAuthorityKeyIdentifier(caCert),
        )
        serverCertBuilder.addExtension(
            Extension.subjectAlternativeName, false,
            buildSans(),
        )

        val serverCert = JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(serverCertBuilder.build(caSigner))

        return GeneratedCerts(
            caCertPem = toPem(caCert),
            caKeyPem = toPem(caKeyPair.private),
            serverCertPem = toPem(serverCert),
            serverKeyPem = toPem(serverKeyPair.private),
        )
    }

    private fun buildSans(): GeneralNames {
        val sans = mutableListOf<GeneralName>()

        // Always include localhost and loopback
        sans.add(GeneralName(GeneralName.dNSName, "localhost"))
        sans.add(GeneralName(GeneralName.dNSName, "master"))
        sans.add(GeneralName(GeneralName.iPAddress, "127.0.0.1"))

        // Detected hostname and private IP
        runCatching {
            val localHost = InetAddress.getLocalHost()
            sans.add(GeneralName(GeneralName.dNSName, localHost.hostName))
            sans.add(GeneralName(GeneralName.iPAddress, localHost.hostAddress))
        }

        // User-supplied SANs (GRPC_TLS_SANS env var)
        for (san in additionalSans) {
            val trimmed = san.trim()
            if (trimmed.isBlank()) continue
            if (trimmed.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                sans.add(GeneralName(GeneralName.iPAddress, trimmed))
            } else {
                sans.add(GeneralName(GeneralName.dNSName, trimmed))
            }
        }

        return GeneralNames(sans.distinctBy { it.toString() }.toTypedArray())
    }

    private fun generateKeyPair() = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        .apply { initialize(4096) }
        .generateKeyPair()

    private fun toPem(obj: Any): String {
        val sw = StringWriter()
        JcaPEMWriter(sw).use { it.writeObject(obj) }
        return sw.toString()
    }

    private fun persist(certs: GeneratedCerts) {
        File(certStorePath).mkdirs()
        caCertFile.writeText(certs.caCertPem)
        caKeyFile.writeText(certs.caKeyPem)
        serverCertFile.writeText(certs.serverCertPem)
        serverKeyFile.writeText(certs.serverKeyPem)

        // Restrict private key permissions
        runCatching {
            for (f in listOf(caKeyFile, serverKeyFile)) {
                f.setReadable(false, false)
                f.setReadable(true, true)
                f.setWritable(false, false)
                f.setWritable(true, true)
            }
        }
    }
}
