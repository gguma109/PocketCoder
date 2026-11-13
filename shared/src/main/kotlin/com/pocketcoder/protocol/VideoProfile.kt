package com.pocketcoder.protocol

data class VideoProfile(val fps: Int = 60, val quality: String = "native", val mbps: Int = 40, val drawCursor: Boolean = true) {
    init { require(fps in 30..120 && quality in listOf("native", "balanced", "data") && mbps in 5..80) }
    val maxEdge: Int get() = when (quality) { "balanced" -> 1920; "data" -> 1280; else -> 8192 }
    val cq: Int get() = when (quality) { "balanced" -> 18; "data" -> 24; else -> 12 }
    val query: String get() = "fps=$fps&quality=$quality&mbps=$mbps"
}
