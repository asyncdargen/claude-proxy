package org.claudeproxy

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.forwardedheaders.ForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.claudeproxy.accounts.AccountPool
import org.claudeproxy.accounts.LimitProbe
import org.claudeproxy.accounts.LimitScheduler
import org.claudeproxy.accounts.TokenRefresher
import org.claudeproxy.api.MessageResponse
import org.claudeproxy.api.adminRoutes
import org.claudeproxy.api.tokenUsageRoutes
import org.claudeproxy.api.internalRoutes
import org.claudeproxy.datapath.DatapathService
import org.claudeproxy.auth.ForbiddenException
import org.claudeproxy.auth.UnauthorizedException
import org.claudeproxy.auth.installSecurity
import org.claudeproxy.accounts.Secrets
import org.claudeproxy.db.Crypto
import org.claudeproxy.db.Db
import org.claudeproxy.proxy.ProxyEngine
import org.claudeproxy.proxy.UpstreamForwarder
import org.claudeproxy.proxy.proxyRoutes
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Application")

fun main() {
    val config = Config.load()
    org.claudeproxy.proxy.Http.configure(config.anthropicProxy)
    Secrets.init(Crypto(config.masterKey))
    Db.init(config)

    val pool = AccountPool()
    val forwarder = UpstreamForwarder(pool, config.upstreamBaseUrl)
    val engine = ProxyEngine(pool, forwarder)
    val refresher = TokenRefresher(pool)
    val probe = LimitProbe(pool, config.upstreamBaseUrl)
    val scheduler = LimitScheduler(pool, probe)

    log.info("Starting claude-proxy on {}:{} (upstream {})", config.bindHost, config.port, config.upstreamBaseUrl)

    embeddedServer(
        Netty,
        environment = applicationEnvironment { },
        configure = {
            connector { host = config.bindHost; port = config.port }
            // Claude Code sends many/large headers (Stainless SDK x-stainless-*, anthropic-beta,
            // long tokens). The Netty defaults (~8 KB) reject them at the decoder → nginx 502.
            maxInitialLineLength = 256 * 1024
            maxHeaderSize = 1024 * 1024
            maxChunkSize = 1024 * 1024
            // Log any channel-level exception (e.g. decoder TooLongFrameException) that would
            // otherwise close the connection before the request reaches the application pipeline.
            channelPipelineConfig = {
                addLast("exlog", object : io.netty.channel.ChannelInboundHandlerAdapter() {
                    private val plog = org.slf4j.LoggerFactory.getLogger("NettyChannel")
                    override fun exceptionCaught(ctx: io.netty.channel.ChannelHandlerContext, cause: Throwable) {
                        plog.warn("channel exception (pre-handler): {}", cause.toString())
                        ctx.fireExceptionCaught(cause)
                    }
                })
            }
        },
    ) {
        module(config, pool, engine, refresher, probe, scheduler)
    }.start(wait = true)
}

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
fun Application.module(
    config: Config,
    pool: AccountPool,
    engine: ProxyEngine,
    refresher: TokenRefresher,
    probe: LimitProbe,
    scheduler: LimitScheduler,
) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    install(ForwardedHeaders)
    install(CORS) {
        allowCredentials = true
        allowHeader("Content-Type")
        allowHeader("Authorization")
        allowHeader("x-api-key")
        allowHeader("anthropic-version")
        allowHeader("anthropic-beta")
        anyMethod()
        // Dev: SPA served by Vite on a different port. In production the SPA is same-origin.
        config.publicDomain?.let { allowHost(it, schemes = listOf("http", "https")) }
        allowHost("localhost:5173", schemes = listOf("http", "https"))
        allowHost("127.0.0.1:5173", schemes = listOf("http", "https"))
    }
    install(StatusPages) {
        exception<UnauthorizedException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, MessageResponse(cause.message ?: "Unauthorized"))
        }
        exception<ForbiddenException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden, MessageResponse(cause.message ?: "Forbidden"))
        }
        exception<io.ktor.util.cio.ChannelWriteException> { _, cause ->
            // Client disconnected before we finished writing — expected, not an error.
            log.debug("client write channel closed: {}", cause.message)
        }
        exception<Throwable> { call, cause ->
            if (cause is kotlinx.coroutines.CancellationException) return@exception
            log.error("Unhandled error", cause)
            runCatching {
                call.respond(HttpStatusCode.InternalServerError, MessageResponse(cause.message ?: "Internal error"))
            }
        }
    }

    installSecurity(config.sessionSecret)

    // Load accounts and start the background token refresher + limit scheduler.
    kotlinx.coroutines.runBlocking { pool.reload() }
    refresher.start(GlobalScope)
    scheduler.start(GlobalScope)
    startSessionMapPruner()
    startAttachmentPruner()

    val datapath = DatapathService(pool)
    val chatEngine = org.claudeproxy.chat.ChatEngine(pool, config.upstreamBaseUrl, datapath)

    routing {
        get("/healthz") { call.respond(MessageResponse("ok")) }

        // Proxy datapath (Anthropic API passthrough).
        proxyRoutes(engine)

        // Management REST API (the chat UI's own API is mounted under /api by adminRoutes).
        adminRoutes(pool, probe, config.publicBaseUrl, chatEngine, datapath, config.upstreamBaseUrl)

        // Private control API for the Go gateway (never routed publicly by nginx).
        internalRoutes(datapath, config.internalToken)
        tokenUsageRoutes(pool, datapath)

        // The SPA is served by the nginx router (see deploy/), not by the service.
    }
}

/**
 * Daily TTL sweep for session-id rotation state. Rows first seen more than
 * SESSION_MAP_TTL_DAYS ago (default 30) are dead — a Claude Code session never lives that long.
 */
/**
 * Hourly sweep for chat attachments that were uploaded but never sent — a user can attach a file
 * and close the tab, and those bytes would otherwise sit in the DB forever. A day of slack is far
 * more than any compose session needs.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
private fun startAttachmentPruner() = GlobalScope.launch {
    while (isActive) {
        runCatching {
            val cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(1))
            val n = org.claudeproxy.repo.ChatMemoryRepo.pruneOrphans(cutoff)
            if (n > 0) log.info("Pruned {} unsent chat attachments", n)
        }.onFailure { log.warn("attachment prune failed: {}", it.message) }
        delay(java.time.Duration.ofHours(1).toMillis())
    }
}

private fun startSessionMapPruner() = GlobalScope.launch {
    val ttl = java.time.Duration.ofDays(envOrProp("SESSION_MAP_TTL_DAYS")?.toLongOrNull() ?: 30L)
    while (isActive) {
        runCatching {
            val n = org.claudeproxy.repo.SessionMapRepo.pruneOlderThan(java.time.Instant.now().minus(ttl))
            if (n > 0) log.info("Pruned {} session-map rows older than {} days", n, ttl.toDays())
        }.onFailure { log.warn("session-map prune failed: {}", it.message) }
        delay(java.time.Duration.ofHours(24).toMillis())
    }
}
