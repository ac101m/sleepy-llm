package com.ac101m.sleepyllm.wake

/**
 * Sends a Wake-on-LAN trigger to the machine hosting the inference server.
 *
 * Implementations are deliberately side-effect-only: they either succeed or throw,
 * so the caller can treat any exception as "wake failed" and surface a 503.
 * Note that [wake] may block while a subprocess runs.
 */
interface WakeSender {
    fun wake()
}
