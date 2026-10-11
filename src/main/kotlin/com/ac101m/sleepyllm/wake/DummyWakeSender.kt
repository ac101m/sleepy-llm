package com.ac101m.sleepyllm.wake

import org.slf4j.LoggerFactory

/**
 * Placeholder [WakeSender] used until a real MAC address / command is configured.
 *
 * It intentionally does nothing: this is what makes local development (and tests)
 * possible without a physical machine that understands magic packets.
 */
class DummyWakeSender : WakeSender {
    private val logger = LoggerFactory.getLogger(DummyWakeSender::class.java)

    override fun wake() {
        logger.info("DummyWakeSender: pretending to send a Wake-on-LAN magic packet")
    }
}
