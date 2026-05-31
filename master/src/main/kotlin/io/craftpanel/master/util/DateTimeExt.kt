package io.craftpanel.master.util

import kotlinx.datetime.LocalDateTime

fun LocalDateTime.toUtcString(): String = "${this}Z"
