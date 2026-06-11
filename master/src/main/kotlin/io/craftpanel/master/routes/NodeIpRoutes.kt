package io.craftpanel.master.routes

import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.nodeIpRoutes() {
    get("/api/nodes/my-ip", {
        operationId = "getMyIp"
        summary = "Returns the caller's IP address as seen by master"
        response {
            code(HttpStatusCode.OK) { body<String>() }
        }
    }) {
        call.respondText(call.request.local.remoteAddress)
    }
}
