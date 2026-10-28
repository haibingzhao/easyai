package com.easy.easyai.autoconfigure.r2dbc

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bindings for `easyai.host.*` — how this application presents itself to a database that other
 * applications may share.
 *
 * The identity itself is not configurable here: it is `spring.application.name`, checked at startup.
 * A second, host-specific property for the same thing would let a deployment say one name to the
 * isolation logic and another to tracing and logs.
 */
@ConfigurationProperties(prefix = "easyai.host")
class HostProperties {

    /**
     * Claim rows written before `owner_app` existed (`async_task`, `watch`, and temp `project` rows)
     * for this application on startup.
     *
     * On by default so single-application deployments upgrade with no manual step. When several
     * applications share one database, exactly **one** of them may keep this on: whichever starts
     * first claims every unowned row, including rows that belong to the others. Either turn it off
     * everywhere and run the documented backfill, or leave it on for the application that already
     * owns all the existing data.
     */
    var adoptLegacyRows: Boolean = true
}
