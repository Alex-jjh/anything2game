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
        log.warn("A2G_HOST={} is not loopback. Put a TLS reverse proxy and a firewall in front before exposing this (see server/README.md).", config.host)
    }
    log.info(
        "starting on {}:{} data={} tiers: compile={} frame={} flash={} frames={}",
        config.host, config.port, config.dataDir, config.compileProvider, config.frameProvider, config.flashProvider, config.defaultFrames,
    )
    val ctx = AppContext.create(config)
    ctx.start()
    embeddedServer(Netty, port = config.port, host = config.host) { module(ctx) }.start(wait = true)
}
