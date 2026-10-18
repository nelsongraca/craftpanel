package io.craftpanel.master.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ServerTypeTest :
    FunSpec({

        test("FOLIA is a plugin-capable Paper-lineage game type") {
            ServerType.FOLIA.isProxy shouldBe false
            ServerType.FOLIA.supportsPlugins shouldBe true
            ServerType.FOLIA.isModLoader shouldBe false
            ServerType.FOLIA.artifactKind shouldBe ArtifactKind.PLUGIN
            ServerType.LOADER_BY_TYPE[ServerType.FOLIA] shouldBe "folia"
        }

        test("LIMBO loads neither mods nor plugins") {
            ServerType.LIMBO.isProxy shouldBe false
            ServerType.LIMBO.supportsPlugins shouldBe false
            ServerType.LIMBO.isModLoader shouldBe false
            ServerType.LIMBO.artifactKind shouldBe ArtifactKind.NONE
            ServerType.LOADER_BY_TYPE.containsKey(ServerType.LIMBO) shouldBe false
        }

        test("artifactKind groups mod loaders, plugin types and neither") {
            ServerType.FABRIC.artifactKind shouldBe ArtifactKind.MOD
            ServerType.NEOFORGE.artifactKind shouldBe ArtifactKind.MOD
            ServerType.PAPER.artifactKind shouldBe ArtifactKind.PLUGIN
            ServerType.VELOCITY.artifactKind shouldBe ArtifactKind.PLUGIN
            ServerType.VANILLA.artifactKind shouldBe ArtifactKind.NONE
            ServerType.CUSTOM.artifactKind shouldBe ArtifactKind.NONE
            ServerType.PICOLIMBO.artifactKind shouldBe ArtifactKind.NONE
        }

        test("toDb / fromDb round-trip every constant") {
            ServerType.entries.forEach { ServerType.fromDb(it.toDb()) shouldBe it }
        }
    })
