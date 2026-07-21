package com.easy.easyai.autoconfigure.web

import com.easy.easyai.web.setup.SetupController
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * Auto-configuration activated when the database is NOT yet configured (Setup Mode).
 *
 * Provides a minimal web layer with only the setup API endpoints,
 * allowing users to configure the database before the full application starts.
 *
 * Deactivated once `easyai.database.configured=true` (after db-config.json is created).
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "easyai.database", name = ["configured"], havingValue = "false")
@Import(SetupSecurityConfig::class)
open class SetupModeAutoConfiguration {

    @Bean
    open fun setupController(): SetupController {
        return SetupController()
    }
}
