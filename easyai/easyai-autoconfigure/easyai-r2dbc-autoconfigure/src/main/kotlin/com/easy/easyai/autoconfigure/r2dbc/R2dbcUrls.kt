package com.easy.easyai.autoconfigure.r2dbc

/**
 * URL rewrites shared by the R2DBC connect path and the JDBC URL Flyway derives from it.
 */
object R2dbcUrls {

    /**
     * Append `connectTimeout=<seconds>` to a PostgreSQL R2DBC URL unless the URL already sets one.
     *
     * The driver's default leaves an unreachable host holding the caller far longer than a web
     * request can wait; a bounded connect is what makes a database outage fail fast. Non-PostgreSQL
     * URLs (H2 in tests, in-memory stores) are returned untouched, and a configured timeout wins
     * over this default.
     */
    @JvmStatic
    fun withConnectTimeout(url: String, seconds: Int): String {
        if (seconds <= 0) return url
        val isPostgres = url.startsWith("r2dbc:postgresql:") || url.startsWith("r2dbc:postgres:") ||
            url.startsWith("r2dbc:pool:postgresql:") || url.startsWith("r2dbc:pool:postgres:")
        if (!isPostgres) return url
        if (url.contains("connectTimeout=", ignoreCase = true)) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url$separator" + "connectTimeout=$seconds"
    }
}
