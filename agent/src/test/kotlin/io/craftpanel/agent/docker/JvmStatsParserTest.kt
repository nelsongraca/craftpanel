package io.craftpanel.agent.docker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JvmStatsParserTest :
    FunSpec({

        // ── parseHeapInfo: G1 (the itzg default on modern JDKs) ──────────────

        test("parseHeapInfo parses a G1 heap info block") {
            val output = """
                 garbage-first heap   total 1048576K, used 524288K [0x0000000700000000, 0x0000000740000000)
                  region size 1024K, 512 young (524288K), 0 survivors (0K)
                 Metaspace       used 40960K, committed 41984K, reserved 1114112K
            """.trimIndent()

            val parsed = JvmStatsParser.parseHeapInfo(output)!!
            parsed.heapUsedBytes shouldBe 524288L * 1024
            parsed.nonHeapUsedBytes shouldBe 40960L * 1024
        }

        test("parseHeapInfo ignores the total and uses only used for the heap") {
            val output = " garbage-first heap   total 2097152K, used 100K [0x0, 0x1)"
            JvmStatsParser.parseHeapInfo(output)!!.heapUsedBytes shouldBe 100L * 1024
        }

        test("parseHeapInfo sums generational heap regions for Serial/Parallel/CMS") {
            val output = """
                 def new generation   total 157248K, used 12345K [0x0, 0x1, 0x2)
                  eden space 139776K,   8% used [0x0, 0x1, 0x2)
                  from space 17472K,   0% used [0x2, 0x2, 0x3)
                  to   space 17472K,   0% used [0x3, 0x3, 0x4)
                 tenured generation   total 349568K, used 200000K [0x4, 0x5, 0x6)
                   the space 349568K,  57% used [0x4, 0x5, 0x6, 0x7)
                 Metaspace       used 8192K, committed 8448K, reserved 1114112K
            """.trimIndent()

            val parsed = JvmStatsParser.parseHeapInfo(output)!!
            parsed.heapUsedBytes shouldBe (12345L + 200000L) * 1024
            parsed.nonHeapUsedBytes shouldBe 8192L * 1024
        }

        test("parseHeapInfo sums Serial GC's DefNew/Tenured labels (JDK 25 jcmd format)") {
            val output = """
                Connected to remote JVM
                JVM response code = 0
                DefNew     total 2432K, used 1268K [0x00000000f8000000, 0x00000000f82a0000, 0x00000000faaa0000)
                 eden space 2176K,  49% used [0x00000000f8000000, 0x00000000f810f778, 0x00000000f8220000)
                 from space 256K,  71% used [0x00000000f8260000, 0x00000000f828dbd0, 0x00000000f82a0000)
                 to   space 256K,   0% used [0x00000000f8220000, 0x00000000f8220000, 0x00000000f8260000)
                Tenured    total 5504K, used 2330K [0x00000000faaa0000, 0x00000000fb000000, 0x0000000100000000)
                 the space 5504K,  42% used [0x00000000faaa0000, 0x00000000faccb7c0, 0x00000000faccb7c0, 0x00000000fb000000)
                Connected to remote JVM
                JVM response code = 0
                Metaspace        used 3320K, committed 3456K, reserved 1114112K
                 class space     used 231K, committed 320K, reserved 1048576K
            """.trimIndent()

            val parsed = JvmStatsParser.parseHeapInfo(output)!!
            parsed.heapUsedBytes shouldBe (1268L + 2330L) * 1024
            parsed.nonHeapUsedBytes shouldBe 3320L * 1024
        }

        test("parseHeapInfo parses Parallel GC's PSYoungGen/ParOldGen labels") {
            val output = " PSYoungGen      total 1536K, used 512K [0x0, 0x1)\n" +
                " ParOldGen       total 4096K, used 1024K [0x1, 0x2)"
            JvmStatsParser.parseHeapInfo(output)!!.heapUsedBytes shouldBe (512L + 1024L) * 1024
        }

        test("parseHeapInfo returns null when no heap line is present") {
            JvmStatsParser.parseHeapInfo("command not found: jcmd") shouldBe null
        }

        test("parseHeapInfo tolerates a missing Metaspace line") {
            val output = " garbage-first heap   total 1048576K, used 524288K [0x0, 0x1)"
            val parsed = JvmStatsParser.parseHeapInfo(output)!!
            parsed.heapUsedBytes shouldBe 524288L * 1024
            parsed.nonHeapUsedBytes shouldBe 0L
        }

        // ── parseMaxHeapSize (jattach printflag MaxHeapSize) ─────────────────

        test("parseMaxHeapSize reads the -XX:MaxHeapSize value in bytes") {
            JvmStatsParser.parseMaxHeapSize("-XX:MaxHeapSize=4294967296") shouldBe 4294967296L
        }

        test("parseMaxHeapSize accepts surrounding whitespace and extra lines") {
            val output = """
                Attaching to process 1
                -XX:MaxHeapSize=2147483648
            """.trimIndent()
            JvmStatsParser.parseMaxHeapSize(output) shouldBe 2147483648L
        }

        test("parseMaxHeapSize returns null on empty output") {
            JvmStatsParser.parseMaxHeapSize("") shouldBe null
        }

        test("parseMaxHeapSize returns null when the flag is absent") {
            JvmStatsParser.parseMaxHeapSize("command not found: jattach") shouldBe null
        }

        test("parseMaxHeapSize returns null for a non-positive value") {
            JvmStatsParser.parseMaxHeapSize("-XX:MaxHeapSize=0") shouldBe null
        }

        test("MAX_HEAP_MARKER splits the probe output into heap and max sections") {
            val output = " garbage-first heap   total 1048576K, used 524288K [0x0, 0x1)\n" +
                JvmStatsParser.MAX_HEAP_MARKER + "\n-XX:MaxHeapSize=4294967296"
            JvmStatsParser.parseHeapInfo(output.substringBefore(JvmStatsParser.MAX_HEAP_MARKER))!!
                .heapUsedBytes shouldBe 524288L * 1024
            JvmStatsParser.parseMaxHeapSize(
                output.substringAfter(JvmStatsParser.MAX_HEAP_MARKER, "")
            ) shouldBe 4294967296L
        }

        test("JATTACH_PROBE_SCRIPT emits the marker and both jattach invocations") {
            JvmStatsParser.JATTACH_PROBE_SCRIPT.contains("jattach") shouldBe true
            JvmStatsParser.JATTACH_PROBE_SCRIPT.contains(JvmStatsParser.MAX_HEAP_MARKER) shouldBe true
            JvmStatsParser.JATTACH_PROBE_SCRIPT.contains(JvmStatsParser.NO_JATTACH_MARKER) shouldBe true
            JvmStatsParser.JATTACH_PROBE_SCRIPT.contains("GC.heap_info") shouldBe true
            JvmStatsParser.JATTACH_PROBE_SCRIPT.contains("VM.metaspace") shouldBe true
            JvmStatsParser.JATTACH_PROBE_SCRIPT.contains("MaxHeapSize") shouldBe true
        }
    })
