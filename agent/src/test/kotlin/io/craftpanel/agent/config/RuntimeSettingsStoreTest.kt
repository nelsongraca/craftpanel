package io.craftpanel.agent.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.craftpanel.proto.agentRuntimeSettings
import io.craftpanel.proto.restartBudget
import java.nio.file.Files

class RuntimeSettingsStoreTest :
    FunSpec({

        fun proto(
            metricsPoll: Int = 0,
            concurrency: Int = 0,
            reconcile: Int = 0,
            budgetMaxAttempts: Int = 0,
            budgetWindowSeconds: Long = 0L,
            jvmInterval: Int = 0
        ) = agentRuntimeSettings {
            metricsPollIntervalSeconds = metricsPoll
            metricsCollectionConcurrency = concurrency
            reconcileIntervalSeconds = reconcile
            restartBudget = restartBudget {
                maxAttempts = budgetMaxAttempts
                windowSeconds = budgetWindowSeconds
            }
            jvmMetricsPollIntervalSeconds = jvmInterval
        }

        test("apply persists to disk and a fresh store loads the snapshot") {
            val dir = Files.createTempDirectory("rt-store")
            val file = dir.resolve("runtime-settings.json").toFile()

            val writer = RuntimeSettingsStore(file)
            writer.apply(RuntimeSettings(metricsPollIntervalSeconds = 42, reconcileIntervalSeconds = 0))

            val fresh = RuntimeSettingsStore(file)
            fresh.load()
            fresh.current().metricsPollIntervalSeconds shouldBe 42
            fresh.current().reconcileIntervalSeconds shouldBe 0
        }

        test("missing file falls back to defaults") {
            val dir = Files.createTempDirectory("rt-store")
            val store = RuntimeSettingsStore(dir.resolve("runtime-settings.json").toFile())
            store.load()
            store.current() shouldBe RuntimeSettings.DEFAULTS
        }

        test("corrupt file falls back to defaults") {
            val dir = Files.createTempDirectory("rt-store")
            val file = dir.resolve("runtime-settings.json").toFile()
            file.writeText("not json at all")

            val store = RuntimeSettingsStore(file)
            store.load()
            store.current() shouldBe RuntimeSettings.DEFAULTS
        }

        test("all-zero proto snapshot is rejected (master predates runtime settings)") {
            RuntimeSettings.fromProto(proto()) shouldBe null
        }

        test("reconcile_interval_seconds=0 alone is a valid snapshot (sweep disabled)") {
            val settings = RuntimeSettings.fromProto(
                proto(metricsPoll = 5, concurrency = 8, budgetMaxAttempts = 5, budgetWindowSeconds = 600, jvmInterval = 30)
            )
            settings shouldBe RuntimeSettings(
                metricsPollIntervalSeconds = 5,
                metricsCollectionConcurrency = 8,
                reconcileIntervalSeconds = 0,
                restartBudget = RestartBudgetSettings(5, 600),
                jvmMetricsPollIntervalSeconds = 30
            )
        }

        test("a populated snapshot converts to runtime settings") {
            val settings = RuntimeSettings.fromProto(
                proto(metricsPoll = 7, concurrency = 3, reconcile = 15, budgetMaxAttempts = 9, budgetWindowSeconds = 120, jvmInterval = 60)
            )
            settings shouldBe RuntimeSettings(
                metricsPollIntervalSeconds = 7,
                metricsCollectionConcurrency = 3,
                reconcileIntervalSeconds = 15,
                restartBudget = RestartBudgetSettings(9, 120),
                jvmMetricsPollIntervalSeconds = 60
            )
        }
    })
