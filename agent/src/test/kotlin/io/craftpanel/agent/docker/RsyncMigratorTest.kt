package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.mockk.mockk

class RsyncMigratorTest :
    FunSpec({
        val docker: DockerClient = mockk()
        val migrator = RsyncMigrator(docker)

        test("parses a typical rsync progress line") {
            val result = migrator.parseRsyncProgress("  1,234,567  42%  1.2MB/s  0:00:10")

            result.shouldNotBeNull()
            result.bytes shouldBe 1_234_567L
            result.total shouldBe 0L
            result.percent shouldBe 42
            result.phase shouldBe "transferring"
        }

        test("returns null for a line without a percent") {
            migrator.parseRsyncProgress("  1,234,567  1.2MB/s  0:00:10") should beNull()
        }

        test("returns null for a garbage line") {
            migrator.parseRsyncProgress("sending incremental file list") should beNull()
        }

        test("returns null for a non-numeric line") {
            migrator.parseRsyncProgress("abc  def%  ghi") should beNull()
        }
    })
