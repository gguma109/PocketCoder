package com.pocketcoder.protocol

import java.net.URI

/** A QR encodes an address only; it never carries credentials or arbitrary URLs. */
fun parseConnectionQr(value: String): String {
    require(value.length <= 512)
    val uri = URI(value)
    require(uri.scheme == "pocketcoder" && uri.host == "connect" && uri.fragment == null && uri.userInfo == null)
    val fields = uri.rawQuery.orEmpty().split('&').associate { val pair = it.split('=', limit = 2); require(pair.size == 2); pair[0] to pair[1] }
    val ip = fields["host"] ?: error("Missing PC address")
    val parts = ip.split('.').map { it.toIntOrNull() ?: error("Invalid PC address") }
    require(parts.size == 4 && parts.all { it in 0..255 } && parts[0] == 100 && parts[1] in 64..127)
    val port = fields["port"]?.toIntOrNull() ?: 8765
    require(port in 1024..65535 && fields.keys.all { it in listOf("host", "port", "v") })
    return "$ip:$port"
}
