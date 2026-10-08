package org.claudeproxy.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.AccountRepo
import org.claudeproxy.accounts.AccountSecret
import org.claudeproxy.accounts.LimitProbe
import org.claudeproxy.repo.Totals
import org.claudeproxy.auth.clearUserSession
import org.claudeproxy.auth.currentUser
import org.claudeproxy.auth.requirePermission
import org.claudeproxy.auth.requireUser
import org.claudeproxy.auth.setUserSession
import org.claudeproxy.model.AccountType
import org.claudeproxy.model.Permission
import org.claudeproxy.model.PoolStatsDto
import org.claudeproxy.model.UserDto
import org.claudeproxy.model.WindowKind
import org.claudeproxy.oauth.ClaudeOAuth
import org.claudeproxy.proxy.Http
import org.claudeproxy.repo.GroupRepo
import org.claudeproxy.repo.McpUsageRepo
import org.claudeproxy.repo.ModelPriceRepo
import org.claudeproxy.repo.OAuthAddRepo
import org.claudeproxy.repo.ProxyTokenRepo
import org.claudeproxy.repo.RoutingTokenRepo
import org.claudeproxy.repo.RoleRepo
import org.claudeproxy.repo.SettingsRepo
import org.claudeproxy.repo.UsageRepo
import org.claudeproxy.repo.UserRepo
import java.time.Instant

fun Route.adminRoutes(
    pool: AccountPool,
    probe: LimitProbe,
    publicBaseUrl: String,
    chatEngine: org.claudeproxy.chat.ChatEngine,
    datapath: org.claudeproxy.datapath.DatapathService,
    upstreamBaseUrl: String,
) {
    route("/api") {
        get("/config") {
            call.requireUser()
            call.respond(ConfigDto(publicBaseUrl))
        }
        // per-model pricing ($ / 1M tokens), admin-managed
        get("/model-prices") {
            call.requireUser()
            call.respond(ModelPriceRepo.list())
        }
        post("/model-prices") {
            call.requirePermission(Permission.ADMIN)
            val req = call.receive<ModelPriceRequest>()
            if (req.pattern.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("pattern required"))
            ModelPriceRepo.set(
                req.pattern, req.inputPrice, req.outputPrice, req.cacheReadPrice, req.cacheWritePrice,
                req.cacheWrite1hPrice, req.fastMultiplier, req.webSearchPrice,
            )
            call.respond(ModelPriceRepo.list())
        }
        delete("/model-prices/{pattern}") {
            call.requirePermission(Permission.ADMIN)
            val p = call.parameters["pattern"] ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad pattern"))
            ModelPriceRepo.delete(p)
            call.respond(ModelPriceRepo.list())
        }
        installRoutes(publicBaseUrl)
        authRoutes()
        profileRoutes()
        accountRoutes(pool, probe)
        myAccountRoutes(pool, probe)
        groupRoutes(pool)
        userRoutes()
        userAccountRoutes(pool, probe)
        roleRoutes()
        proxyTokenRoutes()
        routingTokenRoutes()
        statsRoutes(pool)
        chatRoutes(pool, chatEngine, datapath, upstreamBaseUrl)
    }
}

// ---- helpers ----

private fun userToDto(user: org.claudeproxy.repo.UserAuth): UserDto = UserRepo.get(user.id)!!

/** Global (shared-pool) stats — personal accounts are excluded everywhere. */
private suspend fun buildPoolStats(pool: AccountPool): PoolStatsDto =
    assembleStats(
        runtimes = pool.snapshotGlobal(),
        perAcc = UsageRepo.totalsPerAccount(),
        poolWide = UsageRepo.poolTotals(),
        activeAccountId = pool.activeAccountId,
        nextFiveHourReset = pool.nextReset(WindowKind.FIVE_HOUR)?.toString(),
        nextWeeklyReset = pool.nextReset(WindowKind.WEEKLY)?.toString(),
        // pool-wide view: everyone's in-flight requests
        active = org.claudeproxy.datapath.ActiveSessions.counts(),
    )

/** Stats for one user's personal accounts (the "My Accounts" view + admin oversight). */
private suspend fun buildOwnedStats(pool: AccountPool, userId: Int): PoolStatsDto {
    val runtimes = pool.snapshotOwned(userId)
    val ids = runtimes.map { it.id }.toSet()
    val perAcc = UsageRepo.totalsForAccounts(ids)
    val poolWide = perAcc.values.fold(Totals()) { a, b ->
        Totals(a.requests + b.requests, a.input + b.input, a.output + b.output,
            a.cacheRead + b.cacheRead, a.cacheWrite + b.cacheWrite, a.cost + b.cost)
    }
    return assembleStats(
        runtimes = runtimes,
        perAcc = perAcc,
        poolWide = poolWide,
        activeAccountId = pool.activeAccountId.takeIf { it in ids },
        nextFiveHourReset = pool.nextResetOwned(userId, WindowKind.FIVE_HOUR)?.toString(),
        nextWeeklyReset = pool.nextResetOwned(userId, WindowKind.WEEKLY)?.toString(),
        // personal view: only this user's own in-flight requests
        active = org.claudeproxy.datapath.ActiveSessions.countsForUser(userId),
    )
}

private fun assembleStats(
    runtimes: List<org.claudeproxy.accounts.AccountRuntime>,
    perAcc: Map<Int, Totals>,
    poolWide: Totals,
    activeAccountId: Int?,
    nextFiveHourReset: String?,
    nextWeeklyReset: String?,
    active: org.claudeproxy.datapath.ActiveSessions.Counts,
): PoolStatsDto {
    val createdAt = AccountRepo.createdAtMap()
    val accounts = runtimes.map { rt ->
        rt.toDto(createdAt[rt.id]?.toString() ?: Instant.now().toString(), perAcc[rt.id] ?: Totals())
    }
    // Weekly headroom mirrors the 5-hour `effectiveRemaining` logic: coefficient-weighted
    // remaining over the accounts that actually report a weekly reading (API keys count as
    // full capacity, since they have no subscription window). Accounts with no weekly reading
    // are excluded from both sums so the resulting % stays meaningful.
    val weeklyContribs = runtimes.mapNotNull { rt ->
        val wu = rt.limit.window(WindowKind.WEEKLY)?.usageFraction()
        val rem = wu?.let { rt.coefficient * (1.0 - it) }
            ?: if (rt.type == org.claudeproxy.model.AccountType.API_KEY) rt.coefficient else null
        rem?.let { it to rt.coefficient }
    }
    return PoolStatsDto(
        totalAccounts = runtimes.size,
        healthyAccounts = runtimes.count { it.enabled && it.health == org.claudeproxy.model.AccountHealth.OK },
        activeAccountId = activeAccountId,
        totalEffectiveRemaining = accounts.sumOf { it.effectiveRemaining ?: 0.0 },
        totalEffectiveCapacity = runtimes.sumOf { it.coefficient },
        totalWeeklyRemaining = weeklyContribs.sumOf { it.first },
        totalWeeklyCapacity = weeklyContribs.sumOf { it.second },
        totalRequests = poolWide.requests,
        totalInputTokens = poolWide.input,
        totalOutputTokens = poolWide.output,
        totalCacheReadTokens = poolWide.cacheRead,
        totalCacheWriteTokens = poolWide.cacheWrite,
        totalCost = poolWide.cost,
        nextFiveHourReset = nextFiveHourReset,
        nextWeeklyReset = nextWeeklyReset,
        activeProxySessions = active.proxy,
        activeRoutingSessions = active.routing,
        selectionStrategy = org.claudeproxy.accounts.SelectionStrategy.current().name,
        accounts = accounts,
    )
}

// ---- auth ----

private fun Route.authRoutes() {
    post("/auth/login") {
        val req = call.receive<LoginRequest>()
        val user = UserRepo.authenticate(req.username, req.password)
        if (user == null) {
            call.respond(HttpStatusCode.Unauthorized, MessageResponse("Invalid credentials"))
            return@post
        }
        call.setUserSession(user)
        call.respond(userToDto(user))
    }
    post("/auth/logout") {
        call.clearUserSession()
        call.respond(OkResponse())
    }
    get("/auth/me") {
        val user = call.currentUser()
        if (user == null) call.respond(HttpStatusCode.Unauthorized, MessageResponse("Not authenticated"))
        else call.respond(userToDto(user))
    }
}

// ---- self-service profile (any authenticated user) ----

private fun Route.profileRoutes() {
    // Change your own username and/or password. Current password gates the change.
    patch("/account") {
        val user = call.requireUser()
        val req = call.receive<UpdateProfileRequest>()
        if (!UserRepo.verifyPassword(user.id, req.currentPassword)) {
            return@patch call.respond(HttpStatusCode.Unauthorized, MessageResponse("Current password is incorrect"))
        }
        val newName = req.username?.trim()?.takeIf { it.isNotBlank() }
        if (newName != null && newName != user.username && UserRepo.usernameTaken(newName, user.id)) {
            return@patch call.respond(HttpStatusCode.Conflict, MessageResponse("Username already taken"))
        }
        val newPassword = req.password?.takeIf { it.isNotBlank() }
        UserRepo.updateSelf(user.id, newName, newPassword)
        call.respond(UserRepo.get(user.id) ?: MessageResponse("updated"))
    }
}

// ---- accounts ----

private fun Route.accountRoutes(pool: AccountPool, probe: LimitProbe) {
    get("/accounts") {
        call.requirePermission(Permission.ACCOUNTS_VIEW)
        call.respond(buildPoolStats(pool))
    }

    post("/accounts") {
        val user = call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val req = call.receive<CreateAccountRequest>()
        val type = AccountType.fromString(req.type)
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Unknown type ${req.type}"))
        val secret = when (type) {
            AccountType.API_KEY -> {
                val key = req.apiKey ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("apiKey required"))
                AccountSecret(apiKey = key)
            }
            AccountType.OAUTH, AccountType.OAUTH_STATIC -> {
                val access = req.accessToken ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("accessToken required"))
                AccountSecret(accessToken = access, refreshToken = req.refreshToken, expiresAt = req.expiresAt)
            }
        }
        val id = AccountRepo.create(req.name, type, req.groupId, req.priority, req.threshold, req.coefficient, secret, user.id)
        pool.reload()
        runCatching { probe.probe(id) } // scrape limits on add (best effort)
        call.respond(buildPoolStats(pool))
    }

    patch("/accounts/{id}") {
        call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val req = call.receive<UpdateAccountRequest>()
        if (!validAccountUuid(req.accountUuid)) return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("account uuid must be a UUID"))
        AccountRepo.updateConfig(id, req.name, req.groupId, req.priority, req.threshold, req.coefficient, req.enabled, req.deviceId, req.clearGroup, req.overThreshold, accountUuid = req.accountUuid)
        pool.reload()
        call.respond(buildPoolStats(pool))
    }

    delete("/accounts/{id}") {
        call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        AccountRepo.delete(id)
        pool.reload()
        call.respond(OkResponse())
    }

    // Refresh live limits for one account.
    post("/accounts/{id}/refresh-limits") {
        call.requirePermission(Permission.ACCOUNTS_VIEW)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        probe.probe(id)
        call.respond(buildPoolStats(pool))
    }

    // Refresh live limits for every account.
    post("/accounts/refresh-limits") {
        call.requirePermission(Permission.ACCOUNTS_VIEW)
        probe.probeAll()
        call.respond(buildPoolStats(pool))
    }

    // OAuth "Login with Claude" add flow
    post("/accounts/oauth/start") {
        val user = call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val pkce = ClaudeOAuth.newPkce()
        val state = ClaudeOAuth.randomState()
        OAuthAddRepo.create(state, pkce.verifier, user.id)
        call.respond(OAuthStartResponse(ClaudeOAuth.buildAuthorizeUrl(pkce.challenge, state), state))
    }

    post("/accounts/oauth/complete") {
        val user = call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val req = call.receive<OAuthCompleteRequest>()
        val verifier = OAuthAddRepo.consume(req.state)
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Invalid or expired state"))
        val (code, stateFromCode) = ClaudeOAuth.splitCode(req.code)
        try {
            val result = ClaudeOAuth.exchangeCode(Http.client, code, verifier, stateFromCode ?: req.state)
            val secret = AccountSecret(
                accessToken = result.accessToken,
                refreshToken = result.refreshToken,
                expiresAt = result.expiresAtMillis,
            )
            val type = if (result.refreshToken != null) AccountType.OAUTH else AccountType.OAUTH_STATIC
            val id = AccountRepo.create(
                req.name, type, req.groupId, req.priority, req.threshold, req.coefficient, secret, user.id,
                accountUuid = accountUuidAtLogin(result),
            )
            pool.reload()
            runCatching { probe.probe(id) }
            call.respond(buildPoolStats(pool))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadGateway, MessageResponse("OAuth exchange failed: ${e.message}"))
        }
    }
    post("/accounts/{id}/oauth/complete") {
        call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val account = pool.get(id)?.takeIf { it.ownerId == null }
            ?: return@post call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
        if (account.type == AccountType.API_KEY) return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("API key accounts have no login to redo"))
        val req = call.receive<OAuthReauthRequest>()
        try {
            if (!reauthorizeAccount(id, req, pool, probe)) return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Invalid or expired state"))
            call.respond(buildPoolStats(pool))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadGateway, MessageResponse("OAuth exchange failed: ${e.message}"))
        }
    }
}

// ---- personal accounts (per-user, requires accounts.own.manage) ----

private fun Route.myAccountRoutes(pool: AccountPool, probe: LimitProbe) {
    get("/my/accounts") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        call.respond(buildOwnedStats(pool, user.id))
    }
    // Shared-pool headroom shown on "My Accounts": same global stats as the Dashboard, but
    // available to anyone allowed to route through the global pool (not just ACCOUNTS_VIEW).
    get("/my/global-pool") {
        call.requirePermission(Permission.POOL_GLOBAL_USE)
        call.respond(buildPoolStats(pool))
    }
    post("/my/accounts") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        val req = call.receive<CreateAccountRequest>()
        val type = AccountType.fromString(req.type)
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Unknown type ${req.type}"))
        val secret = when (type) {
            AccountType.API_KEY -> {
                val key = req.apiKey ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("apiKey required"))
                AccountSecret(apiKey = key)
            }
            AccountType.OAUTH, AccountType.OAUTH_STATIC -> {
                val access = req.accessToken ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("accessToken required"))
                AccountSecret(accessToken = access, refreshToken = req.refreshToken, expiresAt = req.expiresAt)
            }
        }
        val id = AccountRepo.create(req.name, type, null, req.priority, req.threshold, req.coefficient, secret, createdBy = user.id, ownerId = user.id)
        pool.reload()
        runCatching { probe.probe(id) }
        call.respond(buildOwnedStats(pool, user.id))
    }
    patch("/my/accounts/{id}") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        if (!AccountRepo.isOwnedBy(id, user.id)) return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
        val req = call.receive<UpdateAccountRequest>()
        // personal accounts are never grouped
        if (!validAccountUuid(req.accountUuid)) return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("account uuid must be a UUID"))
        AccountRepo.updateConfig(id, req.name, null, req.priority, req.threshold, req.coefficient, req.enabled, req.deviceId, clearGroup = true, overThreshold = req.overThreshold, accountUuid = req.accountUuid)
        pool.reload()
        call.respond(buildOwnedStats(pool, user.id))
    }
    delete("/my/accounts/{id}") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        if (!AccountRepo.isOwnedBy(id, user.id)) return@delete call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
        AccountRepo.delete(id)
        pool.reload()
        call.respond(OkResponse())
    }
    post("/my/accounts/{id}/refresh-limits") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        if (!AccountRepo.isOwnedBy(id, user.id)) return@post call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
        probe.probe(id)
        call.respond(buildOwnedStats(pool, user.id))
    }
    post("/my/accounts/refresh-limits") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        pool.snapshotOwned(user.id).forEach { probe.probe(it.id) }
        call.respond(buildOwnedStats(pool, user.id))
    }
    post("/my/accounts/oauth/start") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        val pkce = ClaudeOAuth.newPkce()
        val state = ClaudeOAuth.randomState()
        OAuthAddRepo.create(state, pkce.verifier, user.id)
        call.respond(OAuthStartResponse(ClaudeOAuth.buildAuthorizeUrl(pkce.challenge, state), state))
    }
    post("/my/accounts/oauth/complete") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        val req = call.receive<OAuthCompleteRequest>()
        val verifier = OAuthAddRepo.consume(req.state)
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Invalid or expired state"))
        val (code, stateFromCode) = ClaudeOAuth.splitCode(req.code)
        try {
            val result = ClaudeOAuth.exchangeCode(Http.client, code, verifier, stateFromCode ?: req.state)
            val secret = AccountSecret(accessToken = result.accessToken, refreshToken = result.refreshToken, expiresAt = result.expiresAtMillis)
            val type = if (result.refreshToken != null) AccountType.OAUTH else AccountType.OAUTH_STATIC
            val id = AccountRepo.create(
                req.name, type, null, req.priority, req.threshold, req.coefficient, secret, createdBy = user.id, ownerId = user.id,
                accountUuid = accountUuidAtLogin(result),
            )
            pool.reload()
            runCatching { probe.probe(id) }
            call.respond(buildOwnedStats(pool, user.id))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadGateway, MessageResponse("OAuth exchange failed: ${e.message}"))
        }
    }
    post("/my/accounts/{id}/oauth/complete") {
        val user = call.requirePermission(Permission.ACCOUNTS_OWN_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        if (!AccountRepo.isOwnedBy(id, user.id)) return@post call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
        if (pool.get(id)?.type == AccountType.API_KEY) return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("API key accounts have no login to redo"))
        val req = call.receive<OAuthReauthRequest>()
        try {
            if (!reauthorizeAccount(id, req, pool, probe)) return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Invalid or expired state"))
            call.respond(buildOwnedStats(pool, user.id))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadGateway, MessageResponse("OAuth exchange failed: ${e.message}"))
        }
    }
    // Switch whether the global pool or personal accounts are tried first for this user's requests.
    patch("/my/account-order") {
        val user = call.requirePermission(Permission.ACCOUNTS_ORDER_TOGGLE)
        val req = call.receive<AccountOrderRequest>()
        UserRepo.setPreferGlobalPool(user.id, req.preferGlobalPool)
        call.respond(UserRepo.get(user.id) ?: MessageResponse("updated"))
    }
}

// ---- admin oversight of any user's personal accounts (requires users.manage) ----

private fun Route.userAccountRoutes(pool: AccountPool, probe: LimitProbe) {
    get("/users/{id}/accounts") {
        call.requirePermission(Permission.USERS_MANAGE)
        val uid = call.parameters["id"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        call.respond(buildOwnedStats(pool, uid))
    }
    patch("/users/{id}/accounts/{aid}") {
        call.requirePermission(Permission.USERS_MANAGE)
        val uid = call.parameters["id"]?.toIntOrNull()
        val aid = call.parameters["aid"]?.toIntOrNull()
        if (uid == null || aid == null) return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        if (!AccountRepo.isOwnedBy(aid, uid)) return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
        val req = call.receive<UpdateAccountRequest>()
        if (!validAccountUuid(req.accountUuid)) return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("account uuid must be a UUID"))
        AccountRepo.updateConfig(aid, req.name, null, req.priority, req.threshold, req.coefficient, req.enabled, req.deviceId, clearGroup = true, overThreshold = req.overThreshold, accountUuid = req.accountUuid)
        pool.reload()
        call.respond(buildOwnedStats(pool, uid))
    }
    delete("/users/{id}/accounts/{aid}") {
        call.requirePermission(Permission.USERS_MANAGE)
        val uid = call.parameters["id"]?.toIntOrNull()
        val aid = call.parameters["aid"]?.toIntOrNull()
        if (uid == null || aid == null) return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        if (!AccountRepo.isOwnedBy(aid, uid)) return@delete call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
        AccountRepo.delete(aid)
        pool.reload()
        call.respond(buildOwnedStats(pool, uid))
    }
}

// ---- groups ----

private fun Route.groupRoutes(pool: AccountPool) {
    get("/groups") {
        call.requirePermission(Permission.ACCOUNTS_VIEW)
        call.respond(GroupRepo.list())
    }
    post("/groups") {
        call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val req = call.receive<CreateGroupRequest>()
        GroupRepo.create(req.name)
        call.respond(GroupRepo.list())
    }
    patch("/groups/{id}") {
        call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val req = call.receive<UpdateGroupRequest>()
        GroupRepo.rename(id, req.name)
        call.respond(GroupRepo.list())
    }
    delete("/groups/{id}") {
        call.requirePermission(Permission.ACCOUNTS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        GroupRepo.delete(id)
        pool.reload()
        call.respond(OkResponse())
    }
}

// ---- users ----

private fun Route.userRoutes() {
    get("/users") {
        call.requirePermission(Permission.USERS_MANAGE)
        call.respond(UserRepo.list())
    }
    post("/users") {
        call.requirePermission(Permission.USERS_MANAGE)
        val req = call.receive<CreateUserRequest>()
        val id = UserRepo.create(req.username, req.password, req.roles, req.allowedGroups, req.dailyCostLimit, req.dailyRoutingCostLimit, req.dailyChatCostLimit)
        call.respond(UserRepo.get(id) ?: MessageResponse("created"))
    }
    patch("/users/{id}") {
        call.requirePermission(Permission.USERS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val req = call.receive<UpdateUserRequest>()
        UserRepo.update(id, req.password, req.enabled, req.roles, req.allowedGroups, req.dailyCostLimit, req.clearDailyLimit, req.dailyRoutingCostLimit, req.clearRoutingLimit, req.dailyChatCostLimit, req.clearChatLimit)
        call.respond(UserRepo.get(id) ?: MessageResponse("updated"))
    }
    delete("/users/{id}") {
        val me = call.requirePermission(Permission.USERS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        if (id == me.id) return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("cannot delete yourself"))
        UserRepo.delete(id)
        call.respond(OkResponse())
    }
}

// ---- roles ----

private fun Route.roleRoutes() {
    get("/roles") {
        call.requirePermission(Permission.USERS_MANAGE)
        call.respond(RolesPayload(RoleRepo.list(), RoleRepo.allPermissions()))
    }
    post("/roles") {
        call.requirePermission(Permission.USERS_MANAGE)
        val req = call.receive<CreateRoleRequest>()
        RoleRepo.create(req.name, req.permissions)
        call.respond(RolesPayload(RoleRepo.list(), RoleRepo.allPermissions()))
    }
    patch("/roles/{id}") {
        call.requirePermission(Permission.USERS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val req = call.receive<UpdateRoleRequest>()
        RoleRepo.setPermissions(id, req.permissions)
        call.respond(RolesPayload(RoleRepo.list(), RoleRepo.allPermissions()))
    }
    delete("/roles/{id}") {
        call.requirePermission(Permission.USERS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        RoleRepo.delete(id)
        call.respond(OkResponse())
    }
}

// ---- proxy tokens (per-user, requires proxy.use) ----

private fun Route.proxyTokenRoutes() {
    get("/proxy-tokens") {
        val user = call.requireUser()
        call.respond(ProxyTokenRepo.listForUser(user.id))
    }
    post("/proxy-tokens") {
        val user = call.requirePermission(Permission.PROXY_USE)
        val req = call.receive<CreateProxyTokenRequest>()
        call.respond(ProxyTokenRepo.create(user.id, req.name))
    }
    // Turn a token off/on without revoking it: a disabled token stops authenticating (401).
    patch("/proxy-tokens/{id}/enabled") {
        val user = call.requireUser()
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val req = call.receive<UpdateTokenEnabledRequest>()
        val ok = ProxyTokenRepo.setEnabled(id, user.id, req.enabled)
        if (ok) call.respond(ProxyTokenRepo.listForUser(user.id))
        else call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
    }
    // Set/clear the model forced onto every request made with the token (own tokens only).
    patch("/proxy-tokens/{id}") {
        val user = call.requirePermission(Permission.PROXY_USE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val req = call.receive<UpdateProxyTokenRequest>()
        runCatching { ProxyTokenRepo.normalizeModel(req.defaultModel) }.onFailure {
            return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("model id must be one word, up to 128 characters"))
        }
        val ok = ProxyTokenRepo.updateDefaultModel(id, user.id, req.defaultModel)
        if (ok) call.respond(ProxyTokenRepo.listForUser(user.id))
        else call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
    }
    delete("/proxy-tokens/{id}") {
        val user = call.requireUser()
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val ok = ProxyTokenRepo.delete(id, user.id)
        call.respond(if (ok) OkResponse() else MessageResponse("not found"))
    }
}

// ---- routing tokens (per-user, requires routing.use) — OpenAI/Anthropic API gateways ----

private fun Route.routingTokenRoutes() {
    get("/routing-tokens") {
        val user = call.requireUser()
        call.respond(RoutingTokenRepo.listForUser(user.id))
    }
    post("/routing-tokens") {
        val user = call.requirePermission(Permission.ROUTING_USE)
        val req = call.receive<CreateProxyTokenRequest>()
        call.respond(RoutingTokenRepo.create(user.id, req.name, req.systemPrompt))
    }
    // Set/clear the token's static system prompt (own tokens only).
    patch("/routing-tokens/{id}") {
        val user = call.requirePermission(Permission.ROUTING_USE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val req = call.receive<UpdateRoutingTokenRequest>()
        val ok = RoutingTokenRepo.updatePrompt(id, user.id, req.systemPrompt)
        if (ok) call.respond(RoutingTokenRepo.listForUser(user.id))
        else call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
    }
    patch("/routing-tokens/{id}/enabled") {
        val user = call.requireUser()
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val req = call.receive<UpdateTokenEnabledRequest>()
        val ok = RoutingTokenRepo.setEnabled(id, user.id, req.enabled)
        if (ok) call.respond(RoutingTokenRepo.listForUser(user.id))
        else call.respond(HttpStatusCode.NotFound, MessageResponse("not found"))
    }
    delete("/routing-tokens/{id}") {
        val user = call.requireUser()
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val ok = RoutingTokenRepo.delete(id, user.id)
        call.respond(if (ok) OkResponse() else MessageResponse("not found"))
    }
}

// ---- stats ----

private fun org.claudeproxy.repo.UserAuth.canRecent() =
    Permission.STATS_VIEW_RECENT in permissions || Permission.STATS_VIEW in permissions
private fun org.claudeproxy.repo.UserAuth.canAccounts() =
    Permission.STATS_VIEW_ACCOUNTS in permissions || Permission.STATS_VIEW in permissions
private fun org.claudeproxy.repo.UserAuth.canOwn() =
    Permission.STATS_VIEW_OWN in permissions || Permission.STATS_VIEW in permissions

/**
 * Build the daily-cost time series for a window of [days] ending at [endDate] in [zone].
 * [fetch] supplies the buckets (pool-wide or one user's); [accountFilter], when set, limits the
 * per-account series to those account ids (the total always reflects every bucket returned).
 */
private fun buildDaily(
    days: Int, endDate: java.time.LocalDate, includeAccounts: Boolean,
    zone: java.time.ZoneId = java.time.ZoneOffset.UTC,
    accountFilter: Set<Int>? = null,
    fetch: (java.time.Instant, java.time.Instant) -> List<org.claudeproxy.repo.DailyBucketDto> =
        { s, e -> UsageRepo.dailyBuckets(s, e, zone) },
): DailyStatsPayload {
    val n = days.coerceIn(1, 90)
    val startDate = endDate.minusDays((n - 1).toLong())
    val start = startDate.atStartOfDay(zone).toInstant()
    val end = endDate.plusDays(1).atStartOfDay(zone).toInstant()
    val dayLabels = (0 until n).map { startDate.plusDays(it.toLong()).toString() }
    val idx = dayLabels.withIndex().associate { (i, d) -> d to i }
    val totalCost = DoubleArray(n); val totalReq = LongArray(n)
    val perAcc = HashMap<Int, Pair<DoubleArray, LongArray>>()
    fetch(start, end).forEach { b ->
        val i = idx[b.date] ?: return@forEach
        totalCost[i] += b.cost; totalReq[i] += b.requests
        val (c, r) = perAcc.getOrPut(b.accountId) { DoubleArray(n) to LongArray(n) }
        c[i] += b.cost; r[i] += b.requests
    }
    val names = AccountRepo.namesMap()
    val series = if (includeAccounts)
        perAcc.entries.filter { accountFilter == null || it.key in accountFilter }
            .sortedByDescending { it.value.first.sum() }
            .map { (aid, cr) -> AccountSeriesDto(aid, names[aid], cr.first.toList(), cr.second.toList()) }
    else emptyList()
    return DailyStatsPayload(dayLabels, totalCost.toList(), totalReq.toList(), series, includeAccounts)
}

/**
 * Build per-day token breakdowns (by model + account) for a window of [days] ending at [endDate] in [zone].
 * [fetch] supplies the buckets (pool-wide or one user's); [accountFilter], when set, limits the
 * per-account series to those account ids (total + per-model always reflect every bucket returned).
 */
private fun buildTokens(
    days: Int, endDate: java.time.LocalDate, includeAccounts: Boolean,
    zone: java.time.ZoneId = java.time.ZoneOffset.UTC,
    accountFilter: Set<Int>? = null,
    fetch: (java.time.Instant, java.time.Instant) -> List<org.claudeproxy.repo.TokenBucketDto> =
        { s, e -> UsageRepo.tokenBuckets(s, e, zone) },
): TokenStatsPayload {
    val n = days.coerceIn(1, 90)
    val startDate = endDate.minusDays((n - 1).toLong())
    val start = startDate.atStartOfDay(zone).toInstant()
    val end = endDate.plusDays(1).atStartOfDay(zone).toInstant()
    val dayLabels = (0 until n).map { startDate.plusDays(it.toLong()).toString() }
    val idx = dayLabels.withIndex().associate { (i, d) -> d to i }

    // Each series is four per-day arrays: [input, output, cacheRead, cacheWrite].
    fun kinds() = Array(4) { LongArray(n) }
    val total = kinds()
    val perModel = HashMap<String, Array<LongArray>>()
    val perAccount = HashMap<Int, Array<LongArray>>()
    fetch(start, end).forEach { b ->
        val i = idx[b.date] ?: return@forEach
        val m = perModel.getOrPut(b.model ?: "unknown") { kinds() }
        val a = perAccount.getOrPut(b.accountId) { kinds() }
        // total, per-model and per-account all accumulate the same four kinds at day i
        listOf(total, m, a).forEach { k ->
            k[0][i] += b.input; k[1][i] += b.output; k[2][i] += b.cacheRead; k[3][i] += b.cacheWrite
        }
    }

    val models = perModel.keys.sorted()
    val modelSeries = models.map { model ->
        val k = perModel.getValue(model)
        TokenModelSeriesDto(model, k[0].toList(), k[1].toList(), k[2].toList(), k[3].toList())
    }
    val names = AccountRepo.namesMap()
    val accountSeries = if (includeAccounts)
        perAccount.entries.filter { accountFilter == null || it.key in accountFilter }
            .sortedByDescending { (_, k) -> k.sumOf { it.sum() } }
            .map { (aid, k) -> TokenAccountSeriesDto(aid, names[aid], k[0].toList(), k[1].toList(), k[2].toList(), k[3].toList()) }
    else emptyList()

    return TokenStatsPayload(
        dayLabels,
        TokenTotalsDto(total[0].toList(), total[1].toList(), total[2].toList(), total[3].toList()),
        modelSeries, accountSeries, models, includeAccounts,
    )
}

/**
 * Build the per-inbound-token usage payload for one user on one datapath ("proxy" | "routing"):
 * range-aligned daily cost/token/request series per token, plus all-time totals per token.
 * Deleted tokens keep their series with name=null; tokenId=null groups unattributed rows.
 */
private fun buildTokenUsage(
    userId: Int, source: String, days: Int, endDate: java.time.LocalDate,
    zone: java.time.ZoneId = java.time.ZoneOffset.UTC,
): TokenUsagePayload {
    val n = days.coerceIn(1, 90)
    val startDate = endDate.minusDays((n - 1).toLong())
    val start = startDate.atStartOfDay(zone).toInstant()
    val end = endDate.plusDays(1).atStartOfDay(zone).toInstant()
    val dayLabels = (0 until n).map { startDate.plusDays(it.toLong()).toString() }
    val idx = dayLabels.withIndex().associate { (i, d) -> d to i }

    val perToken = HashMap<Int?, Triple<DoubleArray, LongArray, LongArray>>() // tokenId -> (cost, tokens, requests)
    UsageRepo.tokenDailyBucketsForUser(userId, source, start, end, zone).forEach { b ->
        val i = idx[b.date] ?: return@forEach
        val (c, t, r) = perToken.getOrPut(b.tokenId) { Triple(DoubleArray(n), LongArray(n), LongArray(n)) }
        c[i] += b.cost; t[i] += b.tokens; r[i] += b.requests
    }
    val totals = UsageRepo.totalsPerTokenForUser(userId, source)
    val names = if (source == "routing") RoutingTokenRepo.namesForUser(userId) else ProxyTokenRepo.namesForUser(userId)

    // Every token with any usage (all-time or in range) gets a series; usage-less tokens are
    // omitted — the UI shows zeros for them from the table side.
    val ids = (perToken.keys + totals.keys)
    val series = ids.map { id ->
        val (c, t, r) = perToken[id] ?: Triple(DoubleArray(n), LongArray(n), LongArray(n))
        val tot = totals[id] ?: Totals()
        TokenUsageSeriesDto(
            tokenId = id, name = id?.let { names[it] },
            cost = c.toList(), tokens = t.toList(), requests = r.toList(),
            totalCost = tot.cost, totalTokens = tot.clean, totalRequests = tot.requests,
        )
    }.sortedByDescending { it.totalCost }
    return TokenUsagePayload(dayLabels, series)
}

/**
 * Build the per-MCP-tool call payload for one user: range-aligned daily call series per tool
 * plus range totals, sorted by total calls desc. Claude Code datapath only — the gateway
 * reports MCP tool_use blocks solely for `source="proxy"` traffic.
 */
private fun buildMcpUsage(
    userId: Int, days: Int, endDate: java.time.LocalDate, zone: java.time.ZoneId = java.time.ZoneOffset.UTC,
): McpUsagePayload {
    val n = days.coerceIn(1, 90)
    val startDate = endDate.minusDays((n - 1).toLong())
    val start = startDate.atStartOfDay(zone).toInstant()
    val end = endDate.plusDays(1).atStartOfDay(zone).toInstant()
    val dayLabels = (0 until n).map { startDate.plusDays(it.toLong()).toString() }
    val idx = dayLabels.withIndex().associate { (i, d) -> d to i }

    val perTool = HashMap<String, LongArray>() // tool -> per-day calls
    McpUsageRepo.dailyBucketsForUser(userId, start, end, zone).forEach { b ->
        val i = idx[b.date] ?: return@forEach
        perTool.getOrPut(b.toolName) { LongArray(n) }[i] += b.calls
    }
    val tools = perTool.map { (name, arr) -> McpToolSeriesDto(name, arr.toList(), arr.sum()) }
        .sortedByDescending { it.totalCalls }
    return McpUsagePayload(dayLabels, tools)
}

/**
 * Build the headline stats payload for one user. [source] ("proxy" | "routing" | null = both)
 * filters the today/total counters, per-model tables and the recent list; the two daily-limit
 * gauges always stay on their own basis (shared-pool spend of their datapath), matching how the
 * limits are actually enforced. Shared by "My Stats" and the admin per-user view.
 *
 * The displayed "today" counters are sliced on [zone] — the viewer's clock — so the cards agree
 * with the charts below them. The limit gauges deliberately stay on the UTC day, because that is
 * the day the limits are enforced on; the UI labels them with their own 00:00 UTC countdown.
 */
private fun buildUserStats(
    userId: Int, source: String?, canAccounts: Boolean, zone: java.time.ZoneId,
): MyStatsPayload {
    val startOfDay = UserRepo.startOfDayIn(zone)
    val startOfLimitDay = UserRepo.startOfUtcDay()
    val active = org.claudeproxy.datapath.ActiveSessions.countsForUser(userId)
    val today = UsageRepo.userTotals(userId, startOfDay, source = source)
    val total = UsageRepo.userTotals(userId, source = source)
    val recent = UsageRepo.recentForUser(userId, 20, source)
    return MyStatsPayload(
        todayCost = today.cost, todayClean = today.clean, todayRequests = today.requests,
        totalCost = total.cost, totalClean = total.clean, totalRequests = total.requests,
        dailyCostLimit = UserRepo.dailyLimitOf(userId),
        proxyTodayCost = UsageRepo.userTotals(userId, startOfLimitDay, globalOnly = true, source = "proxy").cost,
        dailyRoutingCostLimit = UserRepo.dailyRoutingLimitOf(userId),
        routingTodayCost = UsageRepo.userTotals(userId, startOfLimitDay, globalOnly = true, source = "routing").cost,
        dailyChatCostLimit = UserRepo.dailyChatLimitOf(userId),
        chatTodayCost = UsageRepo.userTotals(userId, startOfLimitDay, globalOnly = true, source = "chat").cost,
        activeProxySessions = active.proxy,
        activeRoutingSessions = active.routing,
        perModel = UsageRepo.userPerModel(userId, source = source),
        perModelToday = UsageRepo.userPerModel(userId, startOfDay, source),
        recent = if (canAccounts) recent else recent.map { it.copy(accountName = null, accountId = 0) },
    )
}

/** Optional `source` query param: "proxy" | "routing" | "chat"; anything else = no filter. */
private fun io.ktor.server.application.ApplicationCall.sourceParam(): String? =
    parameters["source"]?.takeIf { it in setOf("proxy", "routing", "chat") }

/**
 * Build bucketed window-utilization series (5h + weekly) for a range, with carry-forward.
 * [keep] decides which account ids contribute — pool-wide excludes personal accounts; the
 * per-user view keeps only that user's personal accounts.
 */
private suspend fun buildWindows(
    days: Int, endDate: java.time.LocalDate, includeAccounts: Boolean,
    pool: AccountPool,
    zone: java.time.ZoneId = java.time.ZoneOffset.UTC,
    keep: ((Int) -> Boolean)? = null,
): WindowStatsPayload {
    val now = java.time.Instant.now()
    val plan = planWindowBuckets(days, endDate, zone, now)
    // Default keep: exclude personal accounts (pool-wide view).
    val includeAccount = keep ?: AccountRepo.personalIds().let { personal -> { id: Int -> id !in personal } }
    val samples = org.claudeproxy.repo.WindowSnapshotRepo.fetch(plan.start, plan.end).filter { includeAccount(it.accountId) }

    // Live "now" column. Buckets are 30 min wide and snapshots are throttled to one per minute, so
    // the right edge of the grid can lag reality by most of a bucket — on a 5-hour window that is a
    // meaningful blind spot. When the range reaches the present, append one extra column holding the
    // pool's *current* readings, labelled with the actual clock time rather than a bucket boundary.
    val live = if (plan.truncated) liveWindowSamples(pool, plan, includeAccount) else emptyList()
    val extra = if (live.isEmpty()) 0 else 1
    val gridLabels = (0 until plan.buckets).map {
        java.time.Instant.ofEpochMilli(plan.start.toEpochMilli() + (it * plan.widthMs).toLong())
            .atZone(zone).toLocalDateTime().toString().substring(5, 16)
    }
    val labels = if (extra == 0) gridLabels
    else gridLabels + now.atZone(zone).toLocalDateTime().toString().substring(5, 16)

    return aggregateWindows(
        samples + live, plan.start.toEpochMilli(), plan.widthMs, plan.buckets + extra,
        labels, includeAccounts, AccountRepo.namesMap(),
    )
}

/**
 * Synthesize the samples backing the live "now" column from the pool's in-memory limit state — the
 * freshest reading there is, updated on every upstream response, ahead of what has been persisted.
 *
 * They are stamped in the middle of the extra bucket (`plan.end` .. `+widthMs`) so
 * [aggregateWindows] bins them there and nowhere else. Mid-bucket rather than exactly on
 * `plan.end`: the bucket boundary is a truncated long, so a sample sitting on it can floor back
 * into the previous bucket for non-integral widths (coarsened ranges).
 *
 * Accounts with no reading for a window contribute nothing, which leaves them to the aggregator's
 * carry-forward — the same treatment a gap in the persisted series gets.
 */
private suspend fun liveWindowSamples(
    pool: AccountPool, plan: BucketPlan, includeAccount: (Int) -> Boolean,
): List<org.claudeproxy.repo.WindowSample> {
    val ts = java.time.Instant.ofEpochMilli(plan.end.toEpochMilli() + (plan.widthMs / 2).toLong())
    return pool.snapshot()
        .filter { includeAccount(it.id) }
        .flatMap { acct ->
            org.claudeproxy.model.WindowKind.entries.mapNotNull { kind ->
                acct.limit.window(kind)?.utilization?.let { util ->
                    org.claudeproxy.repo.WindowSample(acct.id, kind.code, ts, util, acct.coefficient)
                }
            }
        }
}

/**
 * Time grid for the window-utilization charts: half-open [start, end) split into [buckets].
 * [truncated] means the requested range ran past `now` and was cut short — i.e. the chart reaches
 * the present, so a live "now" column is meaningful.
 */
internal data class BucketPlan(
    val start: java.time.Instant, val end: java.time.Instant, val widthMs: Double, val buckets: Int,
    val truncated: Boolean = false,
)

/**
 * Plan the bucket grid for a window-utilization range.
 *
 * 30-min bins: the limit probe samples each account every ~30 min, so half-hour buckets are the
 * finest resolution the data actually supports. Capped at 336 (= 7 days of 30-min bins); longer
 * ranges coarsen the bucket width to keep the payload sane.
 *
 * The grid **stops at [now]** when the requested range runs past it. Sizing the axis to the end of
 * the last calendar day instead would leave the rest of today as empty trailing buckets, and since
 * [aggregateWindows] carries the last reading forward across gaps, those render as a flat line
 * stretching hours into the future — indistinguishable from real, current data. Truncating keeps
 * the bucket width intact (it is computed from the full span first) and simply drops the tail.
 */
internal fun planWindowBuckets(
    days: Int, endDate: java.time.LocalDate, zone: java.time.ZoneId, now: java.time.Instant,
): BucketPlan {
    val n = days.coerceIn(1, 30)
    val start = endDate.minusDays((n - 1).toLong()).atStartOfDay(zone).toInstant()
    val fullEnd = endDate.plusDays(1).atStartOfDay(zone).toInstant()
    val fullBuckets = (n * 48).coerceAtMost(336)
    val widthMs = (fullEnd.toEpochMilli() - start.toEpochMilli()).toDouble() / fullBuckets
    if (!fullEnd.isAfter(now)) return BucketPlan(start, fullEnd, widthMs, fullBuckets, truncated = false)
    // Keep the bucket holding `now` — it is partially elapsed but already carries readings.
    val elapsed = (now.toEpochMilli() - start.toEpochMilli()).toDouble()
    val buckets = (Math.floor(elapsed / widthMs).toInt() + 1).coerceIn(1, fullBuckets)
    val end = java.time.Instant.ofEpochMilli(start.toEpochMilli() + (buckets * widthMs).toLong())
    return BucketPlan(start, end, widthMs, buckets, truncated = true)
}

/**
 * Pure bucketing/aggregation for the window-utilization charts — split out of [buildWindows] so
 * the sum + carry-forward + coefficient-weighting math is unit-testable without a DB. [samples]
 * are already filtered to the accounts that should contribute.
 *
 * Each (account, window) is binned to per-bucket averages (raw utilization and coefficient×util,
 * using the coefficient frozen on each sample), then carried forward across gaps. Pool totals are
 * the **sum** across accounts of those carried series (so a multi-account pool can exceed 100%),
 * for both the raw and the ×coef-weighted variants.
 */
internal fun aggregateWindows(
    samples: List<org.claudeproxy.repo.WindowSample>,
    startMs: Long, widthMs: Double, buckets: Int,
    labels: List<String>, includeAccounts: Boolean, names: Map<Int, String>,
): WindowStatsPayload {
    // (accountId, kind) -> per-bucket [Σutil, Σ(coef·util), count]
    val agg = HashMap<Pair<Int, String>, Array<DoubleArray>>()
    samples.forEach { s ->
        val i = ((s.ts.toEpochMilli() - startMs) / widthMs).toInt().coerceIn(0, buckets - 1)
        val arr = agg.getOrPut(s.accountId to s.kind) {
            arrayOf(DoubleArray(buckets), DoubleArray(buckets), DoubleArray(buckets))
        }
        arr[0][i] += s.util
        arr[1][i] += s.util * s.coef
        arr[2][i] += 1.0
    }
    // per-bucket average of one accumulator lane (0 = util, 1 = coef·util), carried forward across gaps
    fun series(key: Pair<Int, String>, lane: Int): List<Double?> {
        val arr = agg[key] ?: return List(buckets) { null }
        val out = arrayOfNulls<Double>(buckets)
        var last: Double? = null
        for (i in 0 until buckets) {
            if (arr[2][i] > 0) last = arr[lane][i] / arr[2][i]
            out[i] = last
        }
        return out.toList()
    }

    val accountIds = agg.keys.map { it.first }.distinct().sorted()
    val perAccount = if (includeAccounts) accountIds.map { aid ->
        WindowSeriesDto(
            aid, names[aid],
            series(aid to "5h", 0), series(aid to "7d", 0),
            series(aid to "5h", 1), series(aid to "7d", 1),
        )
    } else emptyList()

    // pool total = SUM across accounts of each account's carried series (raw or ×coef-weighted)
    fun total(kind: String, lane: Int): List<Double?> {
        val all = accountIds.map { series(it to kind, lane) }
        return (0 until buckets).map { i ->
            val vals = all.mapNotNull { it[i] }
            if (vals.isEmpty()) null else vals.sum()
        }
    }

    return WindowStatsPayload(
        labels,
        total("5h", 0), total("7d", 0),
        total("5h", 1), total("7d", 1),
        perAccount, includeAccounts,
    )
}

/**
 * Build the per-day window-burn series (5h + weekly) for a range, in the viewer's [zone].
 * [keep] decides which account ids contribute, exactly as in [buildWindows].
 *
 * Samples are fetched with a 24h lookback before the range so the first day of the range continues
 * the previous day's series instead of restarting from zero.
 */
private fun buildWindowDaily(
    days: Int, endDate: java.time.LocalDate, includeAccounts: Boolean,
    zone: java.time.ZoneId = java.time.ZoneOffset.UTC,
    keep: ((Int) -> Boolean)? = null,
): WindowDailyPayload {
    val n = days.coerceIn(1, 90)
    val startDate = endDate.minusDays((n - 1).toLong())
    val start = startDate.atStartOfDay(zone).toInstant()
    val end = endDate.plusDays(1).atStartOfDay(zone).toInstant()
    val dayLabels = (0 until n).map { startDate.plusDays(it.toLong()).toString() }
    val includeAccount = keep ?: AccountRepo.personalIds().let { personal -> { id: Int -> id !in personal } }
    val samples = org.claudeproxy.repo.WindowSnapshotRepo
        .fetch(start.minus(java.time.Duration.ofHours(24)), end)
        .filter { includeAccount(it.accountId) }
    return aggregateWindowDaily(samples, zone, dayLabels, includeAccounts, AccountRepo.namesMap())
}

/**
 * Pure per-day window-burn aggregation — split out of [buildWindowDaily] so the reset detection is
 * unit-testable without a DB.
 *
 * Anthropic reports each window as *utilization* (0..1) that climbs while the window is spent and
 * drops back when the window rolls over, so "how much did this account burn today" is the sum of
 * the series' positive steps, not the last value. A drop means the window reset, and whatever the
 * gauge reads right after a reset was already spent in the *new* window — so the new value counts
 * in full. The first sample of an account's series is a baseline (there is nothing to diff it
 * against), which is why [buildWindowDaily] fetches a lookback window: without it the first day of
 * a range would silently lose its opening step.
 *
 * Resolution is bounded by the sampling rate (~30 min): spend between the last pre-reset sample and
 * the reset is attributed to whatever the pre-reset sample showed, so bursts straddling a rollover
 * are slightly under-counted. Days are keyed in [zone], so a reset mid-day lands in the right day.
 */
internal fun aggregateWindowDaily(
    samples: List<org.claudeproxy.repo.WindowSample>,
    zone: java.time.ZoneId,
    dayLabels: List<String>,
    includeAccounts: Boolean,
    names: Map<Int, String>,
): WindowDailyPayload {
    val n = dayLabels.size
    val idx = dayLabels.withIndex().associate { (i, d) -> d to i }
    val eps = 1e-9
    // accountId -> [5h per-day burn, 7d per-day burn, 5h per-day burn ×coefficient]
    val perAcc = HashMap<Int, Array<DoubleArray>>()

    samples.groupBy { it.accountId to it.kind }.forEach { (key, list) ->
        val (accountId, kind) = key
        val lane = when (kind) { "5h" -> 0; "7d" -> 1; else -> return@forEach }
        val lanes = perAcc.getOrPut(accountId) { arrayOf(DoubleArray(n), DoubleArray(n), DoubleArray(n)) }
        var prev: Double? = null
        list.sortedBy { it.ts }.forEach { s ->
            val p = prev
            // reset (value dropped) → the whole new reading is fresh burn in the new window
            val delta = if (p == null) 0.0 else if (s.util < p - eps) s.util else s.util - p
            prev = s.util
            if (delta > eps) {
                val i = idx[s.ts.atZone(zone).toLocalDate().toString()] ?: return@forEach
                lanes[lane][i] += delta
                // Each step is a fraction of *this account's* window; scaling by the coefficient
                // frozen on the sample restates it in base-subscription windows, so a ×5 and a ×1
                // account become comparable. 5-hour only — the weekly window does not scale with
                // the plan, so weighting it would invent capacity that isn't there.
                if (lane == 0) lanes[2][i] += delta * s.coef
            }
        }
    }

    val zeros = List(n) { 0.0 }
    fun total(lane: Int): List<Double> =
        if (perAcc.isEmpty()) zeros
        else (0 until n).map { i -> perAcc.values.sumOf { it[lane][i] } }

    val perAccount = if (includeAccounts)
        perAcc.entries
            .sortedByDescending { it.value[0].sum() + it.value[1].sum() }
            .map { (aid, k) -> WindowDailySeriesDto(aid, names[aid], k[0].toList(), k[1].toList(), k[2].toList()) }
    else emptyList()

    return WindowDailyPayload(dayLabels, total(0), total(1), total(2), perAccount, includeAccounts)
}

private fun Route.statsRoutes(pool: AccountPool) {
    // Window-utilization (5h + weekly) trend over time.
    get("/stats/windows") {
        call.requirePermission(Permission.STATS_VIEW)
        val user = call.requireUser()
        val (days, endDate, zone) = call.rangeParams()
        call.respond(buildWindows(days, endDate, user.canAccounts(), pool, zone))
    }
    // Per-day window burn (5h + weekly), reset-aware — how much of each window was spent per day.
    get("/stats/window-daily") {
        call.requirePermission(Permission.STATS_VIEW)
        val user = call.requireUser()
        val (days, endDate, zone) = call.rangeParams()
        call.respond(buildWindowDaily(days, endDate, user.canAccounts(), zone))
    }
    // Per-account summary over the last N hours (full stats).
    get("/stats/summary") {
        call.requirePermission(Permission.STATS_VIEW)
        val sinceHours = call.parameters["sinceHours"]?.toLongOrNull() ?: 24L
        call.respond(UsageRepo.summarySince(Instant.now().minusSeconds(sinceHours * 3600)))
    }
    // Recent requests list (latest 20). Account attribution only if the viewer may see accounts.
    get("/stats/recent") {
        val user = call.requireUser()
        if (!user.canRecent()) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_VIEW_RECENT")
        val recent = UsageRepo.recent(20)
        call.respond(if (user.canAccounts()) recent else recent.map { it.copy(accountName = null, accountId = 0) })
    }
    // Pool-wide per-model breakdown: today (on the caller's clock) + all-time, for the toggle.
    get("/stats/models") {
        call.requirePermission(Permission.STATS_VIEW)
        val startOfDay = UserRepo.startOfDayIn(call.zoneParam())
        call.respond(ModelBreakdownPayload(today = UsageRepo.perModel(startOfDay), allTime = UsageRepo.perModel()))
    }
    // Daily cost time series (graphs). Default: last 7 days ending today in the caller's timezone.
    get("/stats/daily") {
        call.requirePermission(Permission.STATS_VIEW)
        val user = call.requireUser()
        val (days, endDate, zone) = call.rangeParams()
        call.respond(buildDaily(days, endDate, user.canAccounts(), zone))
    }
    // Per-day token breakdown (by model + account) for the token charts. Default: last 7 days.
    get("/stats/tokens") {
        call.requirePermission(Permission.STATS_VIEW)
        val user = call.requireUser()
        val (days, endDate, zone) = call.rangeParams()
        call.respond(buildTokens(days, endDate, user.canAccounts(), zone))
    }
    post("/stats/reset") {
        // Wipes every user's history and today's spend (so every daily limit) — admin only, not a viewer right.
        call.requirePermission(Permission.ADMIN)
        McpUsageRepo.clearAll()
        call.respond(MessageResponse("Cleared ${UsageRepo.clearAll()} usage records for all users"))
    }
    post("/users/{id}/stats/reset") {
        call.requirePermission(Permission.USERS_MANAGE)
        val id = call.parameters["id"]?.toIntOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        McpUsageRepo.clearUser(id)
        call.respond(MessageResponse("Cleared ${UsageRepo.clearUser(id)} usage records"))
    }
    post("/stats/mine/reset") {
        val user = call.requireUser()
        // Resetting own stats zeroes today's spend, so it can bypass a daily limit — gate it.
        val ok = Permission.STATS_RESET_OWN in user.permissions || Permission.ADMIN in user.permissions
        if (!ok) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_RESET_OWN")
        McpUsageRepo.clearUser(user.id)
        call.respond(MessageResponse("Cleared ${UsageRepo.clearUser(user.id)} of your usage records"))
    }
    get("/stats/mine") {
        val user = call.requireUser()
        if (!user.canOwn()) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_VIEW_OWN")
        call.respond(buildUserStats(user.id, call.sourceParam(), user.canAccounts(), call.zoneParam()))
    }
    // Per-user charts mirroring the pool-wide graphs: Spend/Tokens cover ALL of the user's own
    // usage (any account), while per-account + window series are scoped to the user's personal accounts.
    get("/stats/mine/daily") {
        val user = call.requireUser()
        if (!user.canOwn()) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_VIEW_OWN")
        val (days, endDate, zone) = call.rangeParams()
        val source = call.sourceParam()
        call.respond(buildDaily(days, endDate, includeAccounts = true, zone = zone,
            accountFilter = AccountRepo.personalIdsOf(user.id),
            fetch = { s, e -> UsageRepo.dailyBucketsForUser(user.id, s, e, source, zone) }))
    }
    get("/stats/mine/tokens") {
        val user = call.requireUser()
        if (!user.canOwn()) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_VIEW_OWN")
        val (days, endDate, zone) = call.rangeParams()
        val source = call.sourceParam()
        call.respond(buildTokens(days, endDate, includeAccounts = true, zone = zone,
            accountFilter = AccountRepo.personalIdsOf(user.id),
            fetch = { s, e -> UsageRepo.tokenBucketsForUser(user.id, s, e, source, zone) }))
    }
    // Per-inbound-token usage for the caller's Tokens / API Routing pages: all-time totals per
    // token (table summary) + daily cost/token series for the charts. Strictly the caller's own
    // data (incl. personal accounts), so a session is the only requirement.
    get("/stats/mine/token-usage") {
        val user = call.requireUser()
        val source = if (call.parameters["source"] == "routing") "routing" else "proxy"
        val (days, endDate, zone) = call.rangeParams()
        call.respond(buildTokenUsage(user.id, source, days, endDate, zone))
    }
    // Per-MCP-tool call counts for the caller (Claude Code datapath): daily series + range totals.
    get("/stats/mine/mcp") {
        val user = call.requireUser()
        if (!user.canOwn()) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_VIEW_OWN")
        val (days, endDate, zone) = call.rangeParams()
        call.respond(buildMcpUsage(user.id, days, endDate, zone))
    }
    get("/stats/mine/windows") {
        val user = call.requireUser()
        if (!user.canOwn()) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_VIEW_OWN")
        val (days, endDate, zone) = call.rangeParams()
        val mine = AccountRepo.personalIdsOf(user.id)
        call.respond(buildWindows(days, endDate, includeAccounts = true, pool = pool, zone = zone, keep = { it in mine }))
    }
    get("/stats/mine/window-daily") {
        val user = call.requireUser()
        if (!user.canOwn()) throw org.claudeproxy.auth.ForbiddenException("Missing permission STATS_VIEW_OWN")
        val (days, endDate, zone) = call.rangeParams()
        val mine = AccountRepo.personalIdsOf(user.id)
        call.respond(buildWindowDaily(days, endDate, includeAccounts = true, zone = zone, keep = { it in mine }))
    }

    // ---- admin per-user statistics (users.manage): the same views as "My Stats", for any user ----

    // Aggregate overview of every user's spend (today + all-time, split by datapath).
    get("/users/stats/overview") {
        call.requirePermission(Permission.USERS_MANAGE)
        val users = UserRepo.list().associateBy { it.id }
        // Display counters on the viewer's day; the per-datapath costs read against the daily
        // limits stay on the UTC day the limits are enforced on.
        val rows = UsageRepo.overviewByUser(UserRepo.startOfDayIn(call.zoneParam()), UserRepo.startOfUtcDay())
            .associateBy { it.userId }
        // every user appears (even with zero usage); unattributed events get a userId=null line
        val ids: List<Int?> = (users.keys + rows.keys).distinct()
        val overview = ids.map { id ->
            val u = id?.let { users[it] }
            val r = rows[id]
            UserStatsOverviewDto(
                userId = id, username = u?.username,
                enabled = u?.enabled ?: true,
                dailyCostLimit = u?.dailyCostLimit, dailyRoutingCostLimit = u?.dailyRoutingCostLimit,
                todayProxyCost = r?.todayProxyCost ?: 0.0, todayRoutingCost = r?.todayRoutingCost ?: 0.0,
                todayCost = r?.todayCost ?: 0.0, todayRequests = r?.todayRequests ?: 0, todayTokens = r?.todayTokens ?: 0,
                totalProxyCost = r?.totalProxyCost ?: 0.0, totalRoutingCost = r?.totalRoutingCost ?: 0.0,
                totalCost = r?.totalCost ?: 0.0, totalRequests = r?.totalRequests ?: 0, totalTokens = r?.totalTokens ?: 0,
                lastActivity = r?.lastActivity?.toString(),
            )
        }.sortedByDescending { it.totalCost }
        call.respond(overview)
    }
    get("/users/{id}/stats") {
        call.requirePermission(Permission.USERS_MANAGE)
        val uid = call.parameters["id"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        call.respond(buildUserStats(uid, call.sourceParam(), canAccounts = true, zone = call.zoneParam()))
    }
    get("/users/{id}/stats/daily") {
        call.requirePermission(Permission.USERS_MANAGE)
        val uid = call.parameters["id"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val (days, endDate, zone) = call.rangeParams()
        val source = call.sourceParam()
        call.respond(buildDaily(days, endDate, includeAccounts = true, zone = zone,
            accountFilter = AccountRepo.personalIdsOf(uid),
            fetch = { s, e -> UsageRepo.dailyBucketsForUser(uid, s, e, source, zone) }))
    }
    get("/users/{id}/stats/tokens") {
        call.requirePermission(Permission.USERS_MANAGE)
        val uid = call.parameters["id"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val (days, endDate, zone) = call.rangeParams()
        val source = call.sourceParam()
        call.respond(buildTokens(days, endDate, includeAccounts = true, zone = zone,
            accountFilter = AccountRepo.personalIdsOf(uid),
            fetch = { s, e -> UsageRepo.tokenBucketsForUser(uid, s, e, source, zone) }))
    }
    get("/users/{id}/stats/token-usage") {
        call.requirePermission(Permission.USERS_MANAGE)
        val uid = call.parameters["id"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val source = if (call.parameters["source"] == "routing") "routing" else "proxy"
        val (days, endDate, zone) = call.rangeParams()
        call.respond(buildTokenUsage(uid, source, days, endDate, zone))
    }
    get("/users/{id}/stats/mcp") {
        call.requirePermission(Permission.USERS_MANAGE)
        val uid = call.parameters["id"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val (days, endDate, zone) = call.rangeParams()
        call.respond(buildMcpUsage(uid, days, endDate, zone))
    }
    get("/users/{id}/stats/windows") {
        call.requirePermission(Permission.USERS_MANAGE)
        val uid = call.parameters["id"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val (days, endDate, zone) = call.rangeParams()
        val theirs = AccountRepo.personalIdsOf(uid)
        call.respond(buildWindows(days, endDate, includeAccounts = true, pool = pool, zone = zone, keep = { it in theirs }))
    }
    get("/users/{id}/stats/window-daily") {
        call.requirePermission(Permission.USERS_MANAGE)
        val uid = call.parameters["id"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("bad id"))
        val (days, endDate, zone) = call.rangeParams()
        val theirs = AccountRepo.personalIdsOf(uid)
        call.respond(buildWindowDaily(days, endDate, includeAccounts = true, zone = zone, keep = { it in theirs }))
    }
}

/**
 * Parse the shared `days` + `end` + `tz` query params used by every time-series endpoint.
 *
 * `tz` is an IANA zone id from the browser; anything unparseable (or absent — old clients, curl)
 * falls back to UTC, which is exactly the behaviour these endpoints had before. `end` is read as a
 * date *in that zone*, so "today" means the caller's today.
 */
private fun io.ktor.server.application.ApplicationCall.rangeParams(): Triple<Int, java.time.LocalDate, java.time.ZoneId> {
    val days = parameters["days"]?.toIntOrNull() ?: 7
    val zone = zoneParam()
    val endDate = parameters["end"]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        ?: java.time.LocalDate.now(zone)
    return Triple(days, endDate, zone)
}

/**
 * The viewer's IANA timezone from `tz`, for endpoints that need the zone but not a date range
 * (the "today" counter payloads). Missing/unparseable falls back to UTC — the behaviour these
 * endpoints had before they became viewer-local.
 */
private fun io.ktor.server.application.ApplicationCall.zoneParam(): java.time.ZoneId =
    parameters["tz"]?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneOffset.UTC

/** Replaces an account's credentials with a fresh "Login with Claude"; false when [req]'s state is unknown or expired. */
private suspend fun reauthorizeAccount(id: Int, req: OAuthReauthRequest, pool: AccountPool, probe: LimitProbe): Boolean {
    val verifier = OAuthAddRepo.consume(req.state) ?: return false
    val (code, stateFromCode) = ClaudeOAuth.splitCode(req.code)
    val result = ClaudeOAuth.exchangeCode(Http.client, code, verifier, stateFromCode ?: req.state)
    val secret = AccountSecret(accessToken = result.accessToken, refreshToken = result.refreshToken, expiresAt = result.expiresAtMillis)
    val type = if (result.refreshToken != null) AccountType.OAUTH else AccountType.OAUTH_STATIC
    AccountRepo.reauthorize(id, type, secret, accountUuidAtLogin(result))
    pool.reload()
    runCatching { probe.probe(id) }
    return true
}

/** The token response usually names the account; the profile is the fallback when it doesn't. */
private suspend fun accountUuidAtLogin(result: ClaudeOAuth.TokenResult): String? =
    result.accountUuid ?: runCatching { ClaudeOAuth.fetchAccountUuid(Http.client, result.accessToken) }.getOrNull()

private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** null (untouched) and blank (clear) pass; anything else must be shaped like Anthropic's uuid. */
private fun validAccountUuid(v: String?): Boolean = v == null || v.isBlank() || UUID_RE.matches(v.trim())
