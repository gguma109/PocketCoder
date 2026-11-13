package com.pocketcoder.protocol

data class ControlState(val mine: Boolean, val occupied: Boolean, val label: String, val expiresInMs: Long)

/** Authentication is external. This lease arbitrates already authenticated devices only. */
class ControlLease(private val now: () -> Long = { System.nanoTime() / 1_000_000 }, private val releaseButtons: () -> Unit = {}) {
    private var owner: String? = null
    private var label = ""
    private var until = 0L
    private fun expire() { if (owner != null && now() >= until) { owner = null; label = ""; releaseButtons() } }
    @Synchronized fun state(key: String): ControlState {
        expire()
        return ControlState(owner == key, owner != null, label, (until - now()).coerceAtLeast(0))
    }
    @Synchronized fun join(key: String, name: String): ControlState {
        expire()
        if (owner == null || owner == key) claim(key, name)
        return state(key)
    }
    @Synchronized fun claim(key: String, name: String): ControlState {
        if (owner != null && owner != key) releaseButtons()
        owner = key; label = name.take(40); until = now() + 12000
        return state(key)
    }
    @Synchronized fun heartbeat(key: String): ControlState {
        expire(); if (owner == key) until = now() + 12000
        return state(key)
    }
    @Synchronized fun release(key: String): ControlState {
        if (owner == key) { owner = null; label = ""; until = 0; releaseButtons() }
        return state(key)
    }
    @Synchronized fun allows(key: String, legacy: Boolean = false): Boolean {
        expire()
        return owner == key || (legacy && owner == null)
    }
}
