package com.easy.easyai.core.host

/**
 * Which application a row belongs to, when several applications share one database.
 *
 * The value is the deployment's `spring.application.name`. It exists because the background passes
 * that drive watches, async tasks and temporary workspaces were written for a single process owning
 * the database: two hosts on one database would each park the other's running tasks as orphans, each
 * claim the other's due watches, and each sweep the other's scratch directories. Rows those passes
 * act on carry an owner, and a pass only touches rows it owns.
 *
 * Only the destructive and the claiming paths filter on it. Ordinary reads stay unfiltered, so a
 * global view (the Tasks page) and cross-host duplicate suppression (a shared pending row) keep
 * working.
 */
data class HostIdentity(val appName: String) {
    init {
        require(appName.isNotBlank()) {
            "Host identity needs a non-blank application name; set spring.application.name"
        }
    }

    /** True when [ownerApp] belongs to this host. A null owner is unclaimed legacy data, not ours. */
    fun owns(ownerApp: String?): Boolean = ownerApp == appName
}
