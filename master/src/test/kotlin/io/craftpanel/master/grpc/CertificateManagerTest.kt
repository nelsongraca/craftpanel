package io.craftpanel.master.grpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.openssl.PEMParser
import java.io.StringReader
import java.nio.file.Files

class CertificateManagerTest : FunSpec({

    lateinit var tempDir: java.io.File

    beforeEach {
        tempDir = Files.createTempDirectory("cert-test")
            .toFile()
    }

    afterEach {
        tempDir.deleteRecursively()
    }

    fun manager(additionalSans: List<String> = emptyList()) =
        CertificateManager(tempDir.absolutePath, additionalSans)

    fun parseCert(pem: String): X509CertificateHolder =
        PEMParser(StringReader(pem)).readObject() as X509CertificateHolder

    fun sanDnsNames(holder: X509CertificateHolder): List<String> {
        val raw = holder.extensions.getExtensionParsedValue(Extension.subjectAlternativeName) ?: return emptyList()
        return GeneralNames.getInstance(raw).names
            .filter { it.tagNo == GeneralName.dNSName }
            .map { it.name.toString() }
    }

    fun sanIpAddresses(holder: X509CertificateHolder): List<String> {
        val raw = holder.extensions.getExtensionParsedValue(Extension.subjectAlternativeName) ?: return emptyList()
        return GeneralNames.getInstance(raw).names
            .filter { it.tagNo == GeneralName.iPAddress }
            .map { gn ->
                // DEROctetString bytes → dotted decimal
                val bytes = (gn.name as org.bouncycastle.asn1.DEROctetString).octets
                bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
    }

    test("loadOrGenerate produces 4 non-blank PEM strings") {
        val certs = manager().loadOrGenerate()
        certs.caCertPem.shouldNotBeBlank()
        certs.caKeyPem.shouldNotBeBlank()
        certs.serverCertPem.shouldNotBeBlank()
        certs.serverKeyPem.shouldNotBeBlank()
    }

    test("loadOrGenerate CA cert is valid X509") {
        val certs = manager().loadOrGenerate()
        val holder = parseCert(certs.caCertPem)
        holder.subject.toString() shouldContain "CraftPanel gRPC CA"
    }

    test("loadOrGenerate second call returns same PEM from disk") {
        val m = manager()
        val first = m.loadOrGenerate()
        val second = m.loadOrGenerate()
        second.caCertPem shouldBe first.caCertPem
        second.serverCertPem shouldBe first.serverCertPem
    }

    test("persist writes 4 files to certStorePath") {
        manager().loadOrGenerate()
        tempDir.resolve("grpc-ca.crt")
            .exists() shouldBe true
        tempDir.resolve("grpc-ca.key")
            .exists() shouldBe true
        tempDir.resolve("grpc-server.crt")
            .exists() shouldBe true
        tempDir.resolve("grpc-server.key")
            .exists() shouldBe true
    }

    test("additional SANs appear in server cert") {
        val certs = manager(listOf("myhost.local", "10.0.0.5")).loadOrGenerate()
        val holder = parseCert(certs.serverCertPem)
        sanDnsNames(holder).any { it.contains("myhost.local") } shouldBe true
        sanIpAddresses(holder).any { it == "10.0.0.5" } shouldBe true
    }

    test("server cert always contains localhost and 127.0.0.1") {
        val certs = manager().loadOrGenerate()
        val holder = parseCert(certs.serverCertPem)
        sanDnsNames(holder).any { it.contains("localhost") } shouldBe true
        sanIpAddresses(holder).any { it == "127.0.0.1" } shouldBe true
    }

    test("readCaCertPem returns CA cert after loadOrGenerate") {
        val m = manager()
        val certs = m.loadOrGenerate()
        m.readCaCertPem() shouldBe certs.caCertPem
    }

    test("CA cert has BasicConstraints CA=true") {
        val certs = manager().loadOrGenerate()
        val holder = parseCert(certs.caCertPem)
        val bc = BasicConstraints.fromExtensions(holder.extensions)
        bc shouldNotBe null
        bc!!.isCA shouldBe true
    }

    test("server cert has BasicConstraints CA=false") {
        val certs = manager().loadOrGenerate()
        val holder = parseCert(certs.serverCertPem)
        val bc = BasicConstraints.fromExtensions(holder.extensions)
        bc shouldNotBe null
        bc!!.isCA shouldBe false
    }
})
