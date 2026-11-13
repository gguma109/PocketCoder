package com.pocketcoder.protocol

import java.util.ArrayDeque

/** Keep discrete inputs ordered, merge adjacent motion, and expire stale motion on slow links. */
class InputBuffer(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry(val fields: Map<String, String>, val time: Long)
    private val entries = ArrayDeque<Entry>()
    private var draining = false

    @Synchronized fun offer(fields: Map<String, String>): Boolean {
        val previous = entries.peekLast()
        if (fields["type"] == "move" && previous?.fields?.get("type") == "move") {
            entries.removeLast()
            val merged = mapOf("type" to "move", "dx" to sum(previous.fields, fields, "dx"),
                "dy" to sum(previous.fields, fields, "dy"))
            entries.addLast(Entry(merged, now()))
            return true
        }
        if (entries.size >= 64) return false
        entries.addLast(Entry(fields.toMap(), now()))
        return true
    }

    @Synchronized fun startDrain(): Boolean {
        if (draining) return false
        draining = true
        return true
    }

    @Synchronized fun next(): Map<String, String>? {
        while (entries.isNotEmpty()) {
            val entry = entries.removeFirst()
            if (entry.fields["type"] != "move" || now() - entry.time <= 250) return entry.fields
        }
        draining = false
        return null
    }

    private fun sum(a: Map<String, String>, b: Map<String, String>, key: String): String =
        ((a[key]?.toIntOrNull() ?: 0).toLong() + (b[key]?.toIntOrNull() ?: 0))
            .coerceIn(-2500, 2500).toString()
}
