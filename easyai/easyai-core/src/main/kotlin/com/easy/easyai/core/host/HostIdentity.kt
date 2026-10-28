package com.easy.easyai.core.host

/**
 * Which application this process is, for the accounts and background passes that share a database.
 *
 * The value is the deployment's `spring.application.name`. It is stamped onto every new account
 * (`app_user.owner_app`), baked into every JWT, and used by the stores that scan across users:
 * a scheduled setting, watch, orphaned task or temporary workspace is executed only when its
 * `user_id` belongs to an account of this application.
 */
data class HostIdentity(val appName: String) {
    init {
        require(appName.isNotBlank()) {
            "Host identity needs a non-blank application name; set spring.application.name"
        }
    }
}
