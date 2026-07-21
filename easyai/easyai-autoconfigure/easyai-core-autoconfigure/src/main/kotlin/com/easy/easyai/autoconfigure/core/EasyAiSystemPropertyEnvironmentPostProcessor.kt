package com.easy.easyai.autoconfigure.core

import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Registers configuration properties prefixed with `easyai.system.` from
 * application.properties/yml into System Properties, making them accessible
 * via System.getProperty().
 *
 * Example:
 * easyai.system.otel.exporter.otlp.protocol=http/protobuf
 * will be converted to:
 * System.setProperty("otel.exporter.otlp.protocol", "http/protobuf")
 */
class EasyAiSystemPropertyEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {

    private val logger = LoggerFactory.getLogger(EasyAiSystemPropertyEnvironmentPostProcessor::class.java)

    companion object {
        private const val PREFIX = "easyai.system."
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 20

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val systemProperties = mutableMapOf<String, String>()

        // Iterate all property sources to find properties prefixed with easyai.system.
        environment.propertySources.forEach { propertySource ->
            if (propertySource.source is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val sourceMap = propertySource.source as Map<String, Any>
                sourceMap.forEach { (key, value) ->
                    if (key.startsWith(PREFIX)) {
                        // Strip the prefix to get the actual system property name
                        val systemPropertyName = key.substring(PREFIX.length)
                        if (systemPropertyName.isNotEmpty()) {
                            systemProperties[systemPropertyName] = value.toString()
                        }
                    }
                }
            }
        }

        // Apply discovered properties to System Properties
        systemProperties.forEach { (propertyName, propertyValue) ->
            val existingValue = System.getProperty(propertyName)
            if (existingValue == null) {
                System.setProperty(propertyName, propertyValue)
                logger.debug("Set system property: {}={}", propertyName, propertyValue)
            } else {
                logger.debug(
                    "Skip setting system property '{}' (already set to '{}')",
                    propertyName,
                    existingValue
                )
            }
        }

        if (systemProperties.isNotEmpty()) {
            logger.info("Registered {} system properties from easyai.system.* configuration", systemProperties.size)
        }
    }
}
