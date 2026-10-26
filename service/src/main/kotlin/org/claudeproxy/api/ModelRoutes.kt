package org.claudeproxy.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.auth.requirePermission
import org.claudeproxy.catalog.ModelCatalog
import org.claudeproxy.catalog.PriceSync
import org.claudeproxy.model.Permission

@Serializable
data class ModelOverrideRequest(
    val id: String,
    val displayName: String? = null,
    val description: String? = null,
    val hidden: Boolean = false,
)

/** Admin view of the model catalogue and the price sync (mounted under `/api`). */
fun Route.modelRoutes(pool: AccountPool, upstreamBaseUrl: String) {
    get("/models") {
        call.requirePermission(Permission.ADMIN)
        call.respond(ModelCatalog.status())
    }
    post("/models/refresh") {
        call.requirePermission(Permission.ADMIN)
        call.respond(ModelCatalog.refresh(pool, upstreamBaseUrl))
    }
    // Rename / hide a listed model, or add one upstream does not list.
    post("/models/overrides") {
        call.requirePermission(Permission.ADMIN)
        val req = call.receive<ModelOverrideRequest>()
        val id = req.id.trim()
        if (!ModelCatalog.isValidId(id)) return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("bad model id"))
        ModelCatalog.setOverride(id, req.displayName, req.description, req.hidden)
        call.respond(ModelCatalog.status())
    }
    delete("/models/overrides/{id}") {
        call.requirePermission(Permission.ADMIN)
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad model id"))
        ModelCatalog.deleteOverride(id)
        call.respond(ModelCatalog.status())
    }
    get("/model-prices/sync") {
        call.requirePermission(Permission.ADMIN)
        call.respond(PriceSync.status())
    }
    post("/model-prices/sync") {
        call.requirePermission(Permission.ADMIN)
        call.respond(PriceSync.syncNow())
    }
}
