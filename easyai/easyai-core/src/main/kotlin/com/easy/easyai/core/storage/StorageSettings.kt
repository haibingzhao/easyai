package com.easy.easyai.core.storage

/**
 * One user's object-storage configuration, persisted in the `storage_settings` table and editable
 * from the frontend Settings page without a restart.
 *
 * The database is the only source of this configuration; there are no storage properties.
 * [enabled] `false` is meaningful: an explicit disabled row shadows the shared `system` row.
 *
 * Credentials live server-side only — a GET endpoint never returns [accessKeySecret] verbatim.
 */
data class StorageSettings(
    val enabled: Boolean = false,
    val type: String = TYPE_ALIYUN,
    val endpoint: String = "",
    val bucket: String = "",
    val accessKeyId: String = "",
    val accessKeySecret: String = "",
    val localDir: String = ""
) {
    companion object {
        const val TYPE_ALIYUN = "aliyun"
        const val TYPE_LOCAL = "local"
    }
}
