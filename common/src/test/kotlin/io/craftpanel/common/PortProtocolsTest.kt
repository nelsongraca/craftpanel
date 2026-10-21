package io.craftpanel.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class PortProtocolsTest :
    FunSpec({

        test("normalize accepts TCP, UDP and BOTH case-insensitively and trims") {
            PortProtocols.normalize("tcp") shouldBe PortProtocols.TCP
            PortProtocols.normalize("Udp") shouldBe PortProtocols.UDP
            PortProtocols.normalize(" both ") shouldBe PortProtocols.BOTH
        }

        test("normalize rejects unsupported values") {
            PortProtocols.normalize("SCTP").shouldBeNull()
            PortProtocols.normalize("").shouldBeNull()
        }

        test("expand maps BOTH to both concrete protocols") {
            PortProtocols.expand(PortProtocols.BOTH) shouldBe listOf(PortProtocols.TCP, PortProtocols.UDP)
        }

        test("expand maps single protocols to themselves") {
            PortProtocols.expand(PortProtocols.TCP) shouldBe listOf(PortProtocols.TCP)
            PortProtocols.expand("udp") shouldBe listOf(PortProtocols.UDP)
        }

        test("expand falls back to TCP for unknown values") {
            PortProtocols.expand("SCTP") shouldBe listOf(PortProtocols.TCP)
            PortProtocols.expand("") shouldBe listOf(PortProtocols.TCP)
        }

        test("hasTcp is true for TCP, BOTH and unknown values") {
            PortProtocols.hasTcp(PortProtocols.TCP) shouldBe true
            PortProtocols.hasTcp(PortProtocols.BOTH) shouldBe true
            PortProtocols.hasTcp("SCTP") shouldBe true
            PortProtocols.hasTcp(PortProtocols.UDP) shouldBe false
        }

        test("hasUdp is true only for UDP and BOTH") {
            PortProtocols.hasUdp(PortProtocols.UDP) shouldBe true
            PortProtocols.hasUdp(PortProtocols.BOTH) shouldBe true
            PortProtocols.hasUdp(PortProtocols.TCP) shouldBe false
            PortProtocols.hasUdp("SCTP") shouldBe false
        }
    })
