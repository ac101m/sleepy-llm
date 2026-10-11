package com.ac101m.sleepyllm

import com.ac101m.sleepyllm.config.SleepyLlmConfigLoader
import com.ac101m.sleepyllm.proxy.module
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = SleepyLlmConfigLoader.load()
    val server = embeddedServer(Netty, host = config.proxy.host, port = config.proxy.port) {
        module(config)
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(1_000, 2_000) })
    server.start(wait = true)
}
