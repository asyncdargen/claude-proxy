package org.claudeproxy.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.claudeproxy.auth.requirePermission
import org.claudeproxy.model.Permission


/** Mounted below /api; intentionally independent of general statistics and account CRUD. */
fun Route.windowAttributionRoutes() {
    get("/stats/window-attribution") {
        call.requirePermission(Permission.STATS_VIEW_WINDOW_ATTRIBUTION)
        val rawDays = call.request.queryParameters["days"]
        val days = if (rawDays == null) 30 else rawDays.toIntOrNull()
        if (days == null || days !in 1..90) {
            return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("days must be an integer from 1 to 90"))
        }
        call.response.headers.append("Cache-Control","no-store")
        call.respond(org.claudeproxy.repo.WindowAttributionRepo.fetch(days))
    }
}
