package com.easy.easyai.autoconfigure.web

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.web.reactive.config.WebFluxConfigurer
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleResourcesAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(ConsoleResourcesAutoConfiguration::class.java)

    @Nested
    inner class `the console switch` {

        @Test
        fun `registers caching without overriding server configuration`() {
            contextRunner.run { context ->
                assertTrue(context.containsBean("consoleResourceConfigurer"))
                assertTrue(context.getBeanNamesForType(WebServerFactoryCustomizer::class.java).isEmpty())
            }
        }

        @Test
        fun `backs off when the policy is switched off`() {
            contextRunner
                .withPropertyValues("easyai.web.console.enabled=false")
                .run { context ->
                    assertFalse(context.containsBean("consoleResourceConfigurer"))
                }
        }

        @Test
        fun `an unrelated host configurer does not suppress the console one`() {
            contextRunner
                .withBean("hostWebFluxConfigurer", WebFluxConfigurer::class.java, Supplier { object : WebFluxConfigurer {} })
                .run { context ->
                    assertTrue(context.containsBean("consoleResourceConfigurer"))
                    assertEquals(2, context.getBeanNamesForType(WebFluxConfigurer::class.java).size)
                }
        }
    }
}
