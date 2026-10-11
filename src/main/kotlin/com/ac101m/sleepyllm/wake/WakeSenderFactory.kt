package com.ac101m.sleepyllm.wake

import com.ac101m.sleepyllm.config.WakeConfig
import com.ac101m.sleepyllm.config.WakeMode

object WakeSenderFactory {
    fun create(wake: WakeConfig): WakeSender = when (wake.mode) {
        WakeMode.DUMMY -> DummyWakeSender()
        WakeMode.COMMAND -> CommandWakeSender.from(wake)
    }
}
