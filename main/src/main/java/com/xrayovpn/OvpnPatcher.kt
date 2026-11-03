package com.xrayovpn

object OvpnPatcher {

    private val remoteRegex = Regex(
        """(?im)^[ \t]*remote[ \t]+(\S+)(?:[ \t]+(\d+))?(?:[ \t]+\S+)?[ \t]*(?:#.*)?$"""
    )

    private val protoRegex = Regex(
        """(?im)^[ \t]*proto[ \t]+\S+[ \t]*(?:#.*)?$"""
    )

    // Matches only the bare "client" directive (not client-nat etc.)
    private val clientRegex = Regex(
        """(?im)^[ \t]*client[ \t]*(?:#.*)?$\n?"""
    )

    private val portRegex = Regex(
        """(?im)^[ \t]*port[ \t]+(\d+)[ \t]*(?:#.*)?$"""
    )

    fun parseRemote(config: String): Pair<String, Int>? {
        val normalized = config.replace("\r\n", "\n").replace('\r', '\n')
        val match = remoteRegex.find(normalized) ?: return null
        val host = match.groupValues[1]
        val defaultPort = portRegex.find(normalized)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1194
        val port = match.groupValues[2].toIntOrNull() ?: defaultPort
        if (host.isBlank() || port !in 1..65535) return null
        return Pair(host, port)
    }

    fun patch(config: String, mode: Mode, localPort: Int): String {
        require(localPort in 1..65535) { "Invalid local port" }

        var output = config.replace("\r\n", "\n").replace('\r', '\n')
        val protocol = if (mode == Mode.UDP) "udp" else "tcp-client"

        if (mode.needsRelay) {
            // OpenVPN talks to the local relay only.
            output = remoteRegex.replace(output, "")
        } else {
            // Direct modes keep the original servers (without per-line proto).
            output = remoteRegex.replace(output) { m ->
                val host = m.groupValues[1]
                val port = m.groupValues[2]
                if (port.isEmpty()) "remote $host" else "remote $host $port"
            }
        }

        output = protoRegex.replace(output, "")
        output = clientRegex.replace(output, "")

        val header = buildString {
            append("client\n")
            append("proto $protocol\n")
            if (mode.needsRelay) append("remote 127.0.0.1 $localPort\n")
        }
        return header + output.trimStart('\n')
    }
}
