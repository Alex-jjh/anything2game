package com.anything2game.server

import com.anything2game.server.api.module
import com.anything2game.server.config.ConfigException
import com.anything2game.server.config.ServerConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

fun main() {
    val log = LoggerFactory.getLogger("com.anything2game.server")
    val config = try {
        ServerConfig.fromEnv()
    } catch (e: ConfigException) {
        System.err.println("Configuration error: ${e.message}")
        exitProcess(2)
    }
    if (!config.isLoopback) {
        log.warn("A2G_HOST={} is not loopback. That is expected inside a container; make sure only the load balancer / proxy (TLS, firewall, security groups) can reach the port (see server/README.md).", config.host)
    }
    log.info(
        "starting on {}:{} store={} oidc={} tiers: compile={}/{} frame={}/{} flash={}/{} frames={}",
        config.host, config.port, config.storeKind.name.lowercase(), config.oidc != null,
        config.compileProvider, config.tier(com.anything2game.server.llm.Tier.COMPILE).model,
        config.frameProvider, config.tier(com.anything2game.server.llm.Tier.FRAME).model,
        config.flashProvider, config.tier(com.anything2game.server.llm.Tier.FLASH).model,
        config.defaultFrames,
    )
    val ctx = try {
        AppContext.create(config)
    } catch (e: Exception) {
        // Typically the database is unreachable or the credentials are wrong; say so and exit so the platform restarts or alerts.
        System.err.println("Startup failed: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
        exitProcess(3)
    }
    ctx.start()
    embeddedServer(Netty, port = config.port, host = config.host) { module(ctx) }.start(wait = true)
}
