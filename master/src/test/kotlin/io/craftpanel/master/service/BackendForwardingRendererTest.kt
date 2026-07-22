package io.craftpanel.master.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class BackendForwardingRendererTest :
    FunSpec({

        fun opsOf(json: String): List<JsonObject> {
            val root = Json.parseToJsonElement(json).jsonObject
            val patches = root["patches"]!!.jsonArray
            patches.size shouldBe 1
            return patches[0].jsonObject["ops"]!!.jsonArray.map { it.jsonObject }
        }

        test("renders modern (paper-global.yml) patch for Paper lineage") {
            val patch = BackendForwardingRenderer.render("/data/config/paper-global.yml", "my-secret")
            val root = Json.parseToJsonElement(patch).jsonObject
            val fileObj = root["patches"]!!.jsonArray[0].jsonObject
            fileObj["file"] shouldBe JsonPrimitive("/data/config/paper-global.yml")
            fileObj["file-format"] shouldBe JsonPrimitive("yaml")

            val ops = opsOf(patch)
            ops.size shouldBe 3

            val enabledOp = ops[0]["\$set"]!!.jsonObject
            enabledOp["path"] shouldBe JsonPrimitive("$.proxies.velocity.enabled")
            enabledOp["value"] shouldBe JsonPrimitive(true)
            enabledOp["value-type"] shouldBe JsonPrimitive("bool")

            val onlineModeOp = ops[1]["\$set"]!!.jsonObject
            onlineModeOp["path"] shouldBe JsonPrimitive("\$.proxies.velocity['online-mode']")
            onlineModeOp["value"] shouldBe JsonPrimitive(true)
            onlineModeOp["value-type"] shouldBe JsonPrimitive("bool")

            val secretOp = ops[2]["\$set"]!!.jsonObject
            secretOp["path"] shouldBe JsonPrimitive("$.proxies.velocity.secret")
            secretOp["value"] shouldBe JsonPrimitive("my-secret")
            secretOp.containsKey("value-type") shouldBe false
        }

        test("renders legacy (spigot.yml) patch for Bukkit lineage") {
            val patch = BackendForwardingRenderer.render("/data/spigot.yml", "ignored-secret")
            val root = Json.parseToJsonElement(patch).jsonObject
            val fileObj = root["patches"]!!.jsonArray[0].jsonObject
            fileObj["file"] shouldBe JsonPrimitive("/data/spigot.yml")
            fileObj["file-format"] shouldBe JsonPrimitive("yaml")

            val ops = opsOf(patch)
            ops.size shouldBe 1

            val bungeeOp = ops[0]["\$set"]!!.jsonObject
            bungeeOp["path"] shouldBe JsonPrimitive("$.settings.bungeecord")
            bungeeOp["value"] shouldBe JsonPrimitive(true)
            bungeeOp["value-type"] shouldBe JsonPrimitive("bool")
        }

        test("throws for unknown file path") {
            shouldThrow<IllegalArgumentException> {
                BackendForwardingRenderer.render("/data/unknown.yml", "secret")
            }
        }
    })
