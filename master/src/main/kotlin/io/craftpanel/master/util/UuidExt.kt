package io.craftpanel.master.util

import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
fun UUID.toKotlinUuid(): Uuid = Uuid.parse(toString())

@OptIn(ExperimentalUuidApi::class)
fun Uuid.toJavaUuid(): UUID = UUID.fromString(toString())

@OptIn(ExperimentalUuidApi::class)
fun String.toKotlinUuid(): Uuid = Uuid.parse(this)
