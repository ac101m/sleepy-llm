package com.ac101m.sleepyllm.config

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class SleepyLlmConfigLoaderTest {

    @Test
    fun `bundled application conf has no unrecognized keys`() {
        // Guards against the validator drifting out of sync with application.conf.
        // validateKnownKeys throws ConfigException.BadValue on an unknown key, so
        // this test failing on a throw is exactly the desired regression guard.
        SleepyLlmConfigLoader.validateKnownKeys(ConfigFactory.load().getConfig("sleepyllm"))
    }

    @Test
    fun `a full realistic override passes validation`() {
        val override = ConfigFactory.parseString(
            """
            sleepyllm {
              wake {
                mode = "command"
                command = ["wakeonlan", "-i", "192.168.10.255", "aa:bb:cc:dd:ee:ff"]
              }
              shutdown {
                enabled = true
                command = ["ssh", "user@host", "sudo", "poweroff"]
                check-command = ["ssh", "user@host", "test", "-z", "$(who)"]
              }
            }
            """.trimIndent(),
        )
        // validateKnownKeys throws on an unknown key, so no news is good news.
        SleepyLlmConfigLoader.validateKnownKeys(override.getConfig("sleepyllm"))
    }

    @Test
    fun `rejects a misspelled check-command`() {
        val root = ConfigFactory.parseString("sleepyllm.shutdown.check_config = []").getConfig("sleepyllm")
        val error = assertFailsWith<ConfigException.BadValue> {
            SleepyLlmConfigLoader.validateKnownKeys(root)
        }
        assertContains(error.message!!, "check_config")
        assertContains(error.message!!, "shutdown")
    }

    @Test
    fun `rejects a misspelled top-level block`() {
        val root = ConfigFactory.parseString("sleepyllm.shutdowns.enabled = true").getConfig("sleepyllm")
        val error = assertFailsWith<ConfigException.BadValue> {
            SleepyLlmConfigLoader.validateKnownKeys(root)
        }
        assertContains(error.message!!, "shutdowns")
        assertContains(error.message!!, "proxy, backend, wake, shutdown")
    }
}
