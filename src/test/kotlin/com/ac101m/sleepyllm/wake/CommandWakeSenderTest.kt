package com.ac101m.sleepyllm.wake

import com.ac101m.sleepyllm.config.WakeConfig
import com.ac101m.sleepyllm.config.WakeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandWakeSenderTest {

    private fun config(subnet: String?) = WakeConfig(
        mode = WakeMode.COMMAND,
        command = "wakeonlan",
        mac = "aa:bb:cc:dd:ee:ff",
        subnet = subnet,
    )

    @Test
    fun `command includes -i subnet when one is configured`() {
        assertEquals(
            listOf("wakeonlan", "-i", "192.168.10.255", "aa:bb:cc:dd:ee:ff"),
            CommandWakeSender.buildCommand(config("192.168.10.255")),
        )
    }

    @Test
    fun `command omits -i when no subnet is configured`() {
        assertEquals(
            listOf("wakeonlan", "aa:bb:cc:dd:ee:ff"),
            CommandWakeSender.buildCommand(config(null)),
        )
    }

    @Test
    fun `command omits -i when subnet is blank`() {
        assertEquals(
            listOf("wakeonlan", "aa:bb:cc:dd:ee:ff"),
            CommandWakeSender.buildCommand(config("   ")),
        )
    }
}
