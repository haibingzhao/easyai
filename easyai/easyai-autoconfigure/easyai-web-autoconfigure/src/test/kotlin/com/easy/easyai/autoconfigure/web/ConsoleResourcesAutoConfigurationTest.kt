package com.easy.easyai.autoconfigure.web

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.reactive.config.WebFluxConfigurer
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Condition and whitelist tests for [ConsoleResourcesAutoConfiguration].
 *
 * Two things here are worth locking down because a host inherits both without asking: the switch
 * that turns the policy off, and the compression whitelist — whose load-bearing property is the one
 * type it must *not* match, since deflate buffers `text/event-stream` and would stall a chat stream.
 */
class ConsoleResourcesAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(ConsoleResourcesAutoConfiguration::class.java)

    @Nested
    inner class `the console switch` {

        @Test
        fun `registers the cache and compression beans when no property is set`() {
            contextRunner.run { context ->
                assertTrue(context.containsBean("consoleResourceConfigurer"))
                assertTrue(context.containsBean("consoleCompressionCustomizer"))
            }
        }

        @Test
        fun `backs off when the policy is switched off`() {
            contextRunner
                .withPropertyValues("easyai.web.console.enabled=false")
                .run { context ->
                    assertFalse(context.containsBean("consoleResourceConfigurer"))
                    assertFalse(context.containsBean("consoleCompressionCustomizer"))
                }
        }

        @Test
        fun `an unrelated host configurer does not suppress the console one`() {
            // The condition is by bean name, not by type: hosts add their own WebFluxConfigurer for
            // their own routes and must not lose the console policy by doing so.
            contextRunner
                .withBean("hostWebFluxConfigurer", WebFluxConfigurer::class.java, Supplier { object : WebFluxConfigurer {} })
                .run { context ->
                    assertTrue(context.containsBean("consoleResourceConfigurer"))
                    assertEquals(2, context.getBeanNamesForType(WebFluxConfigurer::class.java).size)
                }
        }
    }

    @Nested
    inner class `the compression whitelist` {

        @Test
        fun `compresses the bundle text types`() {
            for (contentType in listOf("text/javascript", "application/javascript", "text/css", "text/html", "image/svg+xml")) {
                assertTrue(ConsoleResourcesAutoConfiguration.isCompressible(contentType), contentType)
            }
        }

        @Test
        fun `never compresses a chat stream`() {
            assertFalse(ConsoleResourcesAutoConfiguration.isCompressible("text/event-stream"))
        }

        @Test
        fun `leaves json and binary payloads alone`() {
            assertFalse(ConsoleResourcesAutoConfiguration.isCompressible("application/json"))
            assertFalse(ConsoleResourcesAutoConfiguration.isCompressible("application/octet-stream"))
        }

        @Test
        fun `a charset suffix still matches its type`() {
            assertTrue(ConsoleResourcesAutoConfiguration.isCompressible("text/html;charset=UTF-8"))
        }

        @Test
        fun `a response without a content type is not compressed`() {
            assertFalse(ConsoleResourcesAutoConfiguration.isCompressible(""))
        }
    }
}
