package io.craftpanel.master.service

import io.craftpanel.master.domain.ServerType
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf

class BackendForwardingTest :
    FunSpec({

        context("MODERN mode") {
            test("PAPER is eligible for modern") {
                val result = BackendForwarding.classify(ServerType.PAPER, "MODERN")
                result.shouldBeTypeOf<Classification.Eligible>()
                (result as Classification.Eligible).file shouldBe "/data/config/paper-global.yml"
            }

            test("PURPUR is eligible for modern") {
                val result = BackendForwarding.classify(ServerType.PURPUR, "MODERN")
                result.shouldBeTypeOf<Classification.Eligible>()
            }

            test("SPIGOT is warn-skip for modern") {
                val result = BackendForwarding.classify(ServerType.SPIGOT, "MODERN")
                result.shouldBeTypeOf<Classification.WarnSkip>()
            }

            test("BUKKIT is warn-skip for modern") {
                val result = BackendForwarding.classify(ServerType.BUKKIT, "MODERN")
                result.shouldBeTypeOf<Classification.WarnSkip>()
            }

            test("VANILLA is warn-skip for modern") {
                val result = BackendForwarding.classify(ServerType.VANILLA, "MODERN")
                result.shouldBeTypeOf<Classification.WarnSkip>()
            }

            test("FABRIC is warn-skip for modern") {
                val result = BackendForwarding.classify(ServerType.FABRIC, "MODERN")
                result.shouldBeTypeOf<Classification.WarnSkip>()
            }

            test("FORGE is warn-skip for modern") {
                val result = BackendForwarding.classify(ServerType.FORGE, "MODERN")
                result.shouldBeTypeOf<Classification.WarnSkip>()
            }

            test("NEOFORGE is warn-skip for modern") {
                val result = BackendForwarding.classify(ServerType.NEOFORGE, "MODERN")
                result.shouldBeTypeOf<Classification.WarnSkip>()
            }

            test("QUILT is warn-skip for modern") {
                val result = BackendForwarding.classify(ServerType.QUILT, "MODERN")
                result.shouldBeTypeOf<Classification.WarnSkip>()
            }
        }

        context("LEGACY mode") {
            test("SPIGOT is eligible for legacy") {
                val result = BackendForwarding.classify(ServerType.SPIGOT, "LEGACY")
                result.shouldBeTypeOf<Classification.Eligible>()
                (result as Classification.Eligible).file shouldBe "/data/spigot.yml"
            }

            test("BUKKIT is eligible for legacy") {
                val result = BackendForwarding.classify(ServerType.BUKKIT, "LEGACY")
                result.shouldBeTypeOf<Classification.Eligible>()
            }

            test("PAPER is eligible for legacy") {
                val result = BackendForwarding.classify(ServerType.PAPER, "LEGACY")
                result.shouldBeTypeOf<Classification.Eligible>()
            }

            test("VANILLA is warn-skip for legacy") {
                val result = BackendForwarding.classify(ServerType.VANILLA, "LEGACY")
                result.shouldBeTypeOf<Classification.WarnSkip>()
            }
        }

        context("NONE mode") {
            test("any type is warn-skip for NONE") {
                val result = BackendForwarding.classify(ServerType.PAPER, "NONE")
                result.shouldBeTypeOf<Classification.WarnSkip>()
            }
        }

        context("BUNGEEGUARD mode") {
            test("any type is warn-skip for BUNGEEGUARD") {
                val result = BackendForwarding.classify(ServerType.PAPER, "BUNGEEGUARD")
                result.shouldBeTypeOf<Classification.WarnSkip>()
            }
        }

        context("OFF mode") {
            test("any type is warn-skip for OFF") {
                val result = BackendForwarding.classify(ServerType.PAPER, "OFF")
                result.shouldBeTypeOf<Classification.WarnSkip>()
            }
        }

        test("unknown server type is warn-skip") {
            BackendForwarding.classify(ServerType.VANILLA, "MODERN")
                .shouldBeTypeOf<Classification.WarnSkip>()
        }

        test("all eligible combos are covered without throwing") {
            shouldNotThrowAny {
                BackendForwarding.classify(ServerType.PAPER, "MODERN")
                BackendForwarding.classify(ServerType.PURPUR, "MODERN")
                BackendForwarding.classify(ServerType.PAPER, "LEGACY")
                BackendForwarding.classify(ServerType.PURPUR, "LEGACY")
                BackendForwarding.classify(ServerType.SPIGOT, "LEGACY")
                BackendForwarding.classify(ServerType.BUKKIT, "LEGACY")
            }
        }
    })
