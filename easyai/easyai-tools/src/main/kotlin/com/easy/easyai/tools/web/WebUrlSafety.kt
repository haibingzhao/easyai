package com.easy.easyai.tools.web

import java.net.InetAddress
import java.net.URI

/**
 * Outbound HTTP admission rules shared by every fetch path (the `webfetch` tool, archive downloads
 * in the skill installer).
 *
 * Known limitation: [validate] resolves the host once, and the HTTP client resolves it again when it
 * connects. A DNS response with a zero TTL can therefore answer a public address here and a private
 * one at connect time (DNS rebinding). Closing that window needs a client with a pinned resolver;
 * until then this stays a best-effort SSRF guard, not a network sandbox.
 */
object WebUrlSafety {

    private val HTTP_SCHEMES = setOf("http", "https")

    /** True when [url] parses as a plain http(s) URL with a host. */
    @JvmStatic
    fun isHttpUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in HTTP_SCHEMES && !uri.host.isNullOrBlank()
    }

    /**
     * Refuses URLs whose host resolves to a loopback, link-local, site-local or wildcard address.
     *
     * @throws IllegalArgumentException for an unparseable URL, a missing host or an internal address
     * @throws java.net.UnknownHostException when the host cannot be resolved
     */
    @JvmStatic
    fun validate(url: String) {
        val uri = URI(url)
        val host = uri.host ?: throw IllegalArgumentException("Invalid URL: no host")
        val addr = InetAddress.getByName(host)
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isAnyLocalAddress
        ) {
            throw IllegalArgumentException("Access to internal network addresses is not allowed: $host")
        }
    }

    /**
     * Resolves a `Location` header against the URL it came from, or null when the target is not
     * followable (missing, malformed, or not plain http(s)).
     */
    @JvmStatic
    fun resolveRedirect(current: URI, location: String?): URI? {
        if (location.isNullOrBlank()) return null
        val next = runCatching { current.resolve(location.trim()) }.getOrNull() ?: return null
        if (next.scheme?.lowercase() !in HTTP_SCHEMES) return null
        if (next.host.isNullOrBlank()) return null
        return next
    }
}
