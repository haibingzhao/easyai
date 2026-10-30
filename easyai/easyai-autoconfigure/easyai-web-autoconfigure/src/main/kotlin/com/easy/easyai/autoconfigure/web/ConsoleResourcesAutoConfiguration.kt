package com.easy.easyai.autoconfigure.web

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.http.CacheControl
import org.springframework.web.reactive.config.ResourceHandlerRegistry
import org.springframework.web.reactive.config.WebFluxConfigurer
import java.time.Duration

/**
 * HTTP policy for the console build that hosts inject into `classpath:/static` — the same
 * `copy-console-dist` resources execution appears in `easyai-web-server`, `easyai-desktop-server`
 * and the host apps' poms, so all of them serve the bundle same-origin and all of them need this.
 *
 * Set `easyai.web.console.enabled=false` in a backend that ships no console.
 */
@AutoConfiguration
@ConditionalOnClass(NettyReactiveWebServerFactory::class)
@ConditionalOnProperty(prefix = "easyai.web.console", name = ["enabled"], havingValue = "true", matchIfMissing = true)
open class ConsoleResourcesAutoConfiguration {

    /**
     * Vite names every chunk with its content hash, so the hashed asset tree is immutable and may be
     * cached forever; the entry documents (`/`, `/index.html`, `/mobile.html`) keep the framework's
     * no-store so a deploy is picked up immediately.
     *
     * This handler must be declared explicitly rather than left to Boot's catch-all mapping: Spring
     * Security's `CacheControlHeadersWriter` only writes its no-store value when `Cache-Control` is
     * still absent, so setting it here suppresses that default.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["consoleResourceConfigurer"])
    open fun consoleResourceConfigurer(): WebFluxConfigurer = object : WebFluxConfigurer {
        override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
            registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
        }
    }
}
