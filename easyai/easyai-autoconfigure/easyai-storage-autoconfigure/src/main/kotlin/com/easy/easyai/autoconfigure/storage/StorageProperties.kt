package com.easy.easyai.autoconfigure.storage

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Deployment-wide object-storage configuration, the highest-priority layer of the resolver chain
 * (STATIC → own DB row → group DB row → system DB row → nothing).
 *
 * Prefix: `easyai.storage`. Optional and unset by default: when [enabled] is false the resolver
 * ignores this layer entirely and behaves exactly as before (per-user DB rows only). A deployment
 * that sets it pins one bucket for every user, hides the Settings → Storage form, and refuses
 * per-user saves — credentials then live in config, never in the database.
 */
@ConfigurationProperties(prefix = "easyai.storage")
data class StorageProperties(
    /** Master switch for the STATIC layer; false keeps storage database-driven. */
    var enabled: Boolean = false,

    /** Backend type: `aliyun` (OSS) or `local` (a directory on disk). */
    var type: String = "aliyun",

    /** OSS endpoint; required when [type] is `aliyun`. */
    var endpoint: String = "",

    /** OSS bucket; required when [type] is `aliyun`. */
    var bucket: String = "",

    /** OSS access-key id; required when [type] is `aliyun`. */
    var accessKeyId: String = "",

    /** OSS access-key secret; required when [type] is `aliyun`. Server-side only. */
    var accessKeySecret: String = "",

    /** Local directory root; used when [type] is `local` (defaults to `~/.easyai/storage`). */
    var localDir: String = ""
)
