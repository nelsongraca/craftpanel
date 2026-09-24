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

        test("parseHeapInfo returns null when no heap line is present") {
            JvmStatsParser.parseHeapInfo("command not found: jcmd") shouldBe null
        }

        test("parseHeapInfo tolerates a missing Metaspace line") {
            val output = " garbage-first heap   total 1048576K, used 524288K [0x0, 0x1)"
            val parsed = JvmStatsParser.parseHeapInfo(output)!!
            parsed.heapUsedBytes shouldBe 524288L * 1024
            parsed.nonHeapUsedBytes shouldBe 0L
        }

        // ── parseJstatMax ───────────────────────────────────────────────────

        test("parseJstatMax sums the heap capacity columns by header name") {
            val header = " S0C    S1C    S0U    S1U      EC       EU        OC         OU       MC     MU    CCSC   CCSU   YGC     YGCT    FGC    FGCT     CGC    CGCT     GCT"
            val data = " 1024.0 1024.0 512.0  0.0   524288.0 262144.0 1048576.0  524288.0  40960.0 39936.0 5120.0 4864.0     10    0.200   0      0.000    2      0.010     0.210"

            // (1024 + 1024 + 524288 + 1048576) KB
            JvmStatsParser.parseJstatMax("$header\n$data") shouldBe (1024L + 1024L + 524288L + 1048576L) * 1024
        }

        test("parseJstatMax only reads the first data row, ignoring the totals row") {
            val header = "S0C S1C EC OC"
            val data = "100.0 100.0 1000.0 2000.0"
            val totals = "999999.0 999999.0 999999.0 999999.0"
            JvmStatsParser.parseJstatMax("$header\n$data\n$totals") shouldBe (100L + 100L + 1000L + 2000L) * 1024
        }

        test("parseJstatMax returns null on empty output") {
            JvmStatsParser.parseJstatMax("") shouldBe null
        }

        test("parseJstatMax returns null when header and data widths differ") {
            JvmStatsParser.parseJstatMax("S0C S1C EC OC\n1.0 2.0") shouldBe null
        }

        test("parseJstatMax returns null when no capacity columns are present") {
            JvmStatsParser.parseJstatMax("YGC YGCT\n10 0.2") shouldBe null
        }

        test("parseJstatMax returns null when all capacities are zero") {
            JvmStatsParser.parseJstatMax("S0C S1C EC OC\n0.0 0.0 0.0 0.0") shouldBe null
        }

        test("parseJstatMax ignores permanent-generation columns (non-heap)") {
            // Includes PC/PU which must NOT be counted toward the heap max.
            val header = "S0C S1C EC OC PC PU"
            val data = "0.0 0.0 1024.0 2048.0 5120.0 4864.0"
            JvmStatsParser.parseJstatMax("$header\n$data") shouldBe (1024L + 2048L) * 1024
        }
    })
