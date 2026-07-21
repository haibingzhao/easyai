package com.easy.easyai.autoconfigure.r2dbc

import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Reads `~/.easyai/db-config.json` and resolves database configuration
 * before Spring context initialization.
 *
 * Resolution order:
 * 1. If `db-config.json` exists → use it to set `easyai.r2dbc.*` properties
 * 2. If `easyai.r2dbc.url` is explicitly set in Spring Environment → use existing config
 * 3. Otherwise → enter Setup Mode (`easyai.database.configured=false`)
 *
 * In Setup Mode, [R2dbcRepositoryAutoConfiguration] is deactivated and
 * the setup API becomes available for initial database configuration.
 *
 * Ordered after ConfigDataEnvironmentPostProcessor (HIGHEST_PRECEDENCE + 10)
 * so that application.properties values are available.
 */
class DatabaseConfigEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 20

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val props = mutableMapOf<String, Any>()

        // Step 1: Try loading db-config.json
        val fileConfig = DatabaseConfig.load()
        if (fileConfig != null) {
            logger.info("Loaded database configuration from db-config.json (dbType={})", fileConfig.dbType)
            val r2dbc = fileConfig.toR2dbcProperties()
            props["easyai.r2dbc.enabled"] = "true"
            props["easyai.r2dbc.url"] = r2dbc.url
            props["easyai.r2dbc.username"] = r2dbc.username
            props["easyai.r2dbc.password"] = r2dbc.password
            props["easyai.database.configured"] = "true"
            props["easyai.database.type"] = fileConfig.dbType
            addPropertySource(environment, props)
            return
        }

        // Step 2: Check if easyai.r2dbc.url is explicitly set in Spring Environment
        // (from application.properties, command-line args, or environment variables)
        val explicitUrl = environment.getProperty("easyai.r2dbc.url")
        if (!explicitUrl.isNullOrBlank()) {
            logger.info("Using database configuration from Spring properties (url={})", explicitUrl)
            props["easyai.database.configured"] = "true"
            // Infer database type from URL
            val dbType = when {
                explicitUrl.contains("postgresql") -> "postgres"
                explicitUrl.contains("h2") -> "h2"
                else -> "unknown"
            }
            props["easyai.database.type"] = dbType
            addPropertySource(environment, props)
            return
        }

        // Step 3: No configuration found
        // Only enter Setup Mode for web deployments (webflux on classpath).
        // Non-web apps (tests, shell) fall through to default H2 in-memory.
        val isWebApp = isClassPresent("org.springframework.web.reactive.DispatcherHandler")
        if (isWebApp) {
            logger.info("No database configuration found — entering Setup Mode")
            props["easyai.r2dbc.enabled"] = "false"
            props["easyai.database.configured"] = "false"
        } else {
            logger.info("No database configuration found — using default H2 in-memory (non-web mode)")
            props["easyai.database.configured"] = "true"
            props["easyai.database.type"] = "h2"
        }
        addPropertySource(environment, props)
    }

    private fun isClassPresent(className: String): Boolean {
        return try {
            Class.forName(className, false, javaClass.classLoader)
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    private fun addPropertySource(environment: ConfigurableEnvironment, props: Map<String, Any>) {
        val source = MapPropertySource("easyaiDatabaseConfig", props)
        // Add with high priority so it overrides application.properties defaults
        environment.propertySources.addFirst(source)
    }
}
