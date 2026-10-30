package com.easy.easyai.speech

import java.net.URI

/**
 * Where the DashScope duplex socket lives, derived from the HTTP base URL a model row already has.
 *
 * Rows are written in HTTP form (`https://{workspace}.cn-beijing.maas.aliyuncs.com/api/v1`), so the
 * scheme and the `/api/v1` root are rewritten rather than asking the user for a second, socket-only
 * field. All of these collapse to the same URL: `…/api/v1`, `…/api/v1/services/audio/tts/…`,
 * `…/compatible-mode/v1`, and an already-complete `wss://…/api-ws/v1/inference`.
 *
 * A plaintext `ws://` endpoint is only honoured for a loopback host: an `http://` base URL for a
 * remote workspace is rewritten to `wss` rather than silently sending the API key in clear.
 *
 * Shared by the synthesis and recognition transports, and by the web layer's availability check —
 * the three must agree on what "this row's endpoint" means, or a model could be routed to a socket
 * that a different component would have refused to open.
 */
object DashScopeSpeechEndpoint {

    const val WS_PATH = "/api-ws/v1/inference"

    @JvmStatic
    fun endpoint(baseUrl: String): String {
        val raw = baseUrl.trim()
        require(raw.isNotBlank()) {
            "DashScope speech needs a base URL, e.g. https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1"
        }
        val requested = when {
            raw.startsWith("wss://", ignoreCase = true) || raw.startsWith("ws://", ignoreCase = true) ->
                raw.substringBefore("://").lowercase()

            raw.startsWith("https://", ignoreCase = true) -> "wss"
            raw.startsWith("http://", ignoreCase = true) -> "ws"
            else -> "wss"
        }
        val authority = raw.substringAfter("://").substringBefore('/').substringBefore('?')
        require(authority.isNotBlank()) { "cannot derive a host from base URL '$baseUrl'" }
        // A bracketed IPv6 literal contains colons of its own, so the port colon is not the first one.
        val host = if (authority.startsWith("[")) {
            authority.substringBefore(']') + "]"
        } else {
            authority.substringBefore(':')
        }.lowercase()
        val scheme = if (requested == "ws" && !isLoopback(host)) "wss" else requested
        return "$scheme://$authority$WS_PATH"
    }

    /**
     * Whether this base URL points at a workspace we may hand the API key to.
     *
     * Border matching is on the parsed host, not on the string: `evil-maas.aliyuncs.com.attacker.tld`
     * and `x@maas.aliyuncs.com` both *contain* the trusted name and neither may receive the key.
     * Userinfo, query and fragment are rejected outright, because that is how such a URL is disguised.
     */
    @JvmStatic
    fun isTrusted(baseUrl: String): Boolean {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return false
        if (uri.userInfo != null || uri.fragment != null || uri.query != null) return false
        if (uri.port != -1 && uri.port != 80 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        return isLoopback(host) || TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"

    private val TRUSTED_HOSTS = listOf(
        "maas.aliyuncs.com",
        "dashscope.aliyuncs.com",
        "dashscope-intl.aliyuncs.com"
    )
}
