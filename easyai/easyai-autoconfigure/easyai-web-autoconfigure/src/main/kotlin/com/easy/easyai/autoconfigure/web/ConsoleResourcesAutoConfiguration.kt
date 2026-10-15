package com.easy.easyai.autoconfigure.web

import io.netty.handler.codec.http.HttpHeaderNames
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.http.CacheControl
import org.springframework.web.reactive.config.ResourceHandlerRegistry
import org.springframework.web.reactive.config.WebFluxConfigurer
import reactor.netty.http.server.HttpServer
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

    /**
     * A 1.7 MB uncompressed main chunk times out on a lossy relay link, so the text bundle is
     * deflated on the way out.
     *
     * Matched on the response `Content-Type` rather than the request path, and as a whitelist, so
     * `text/event-stream` is never handed to Netty's compressor: deflate buffers the chunks a chat
     * stream depends on.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["consoleCompressionCustomizer"])
    open fun consoleCompressionCustomizer(): WebServerFactoryCustomizer<NettyReactiveWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            factory.addServerCustomizers(
                { server: HttpServer ->
                    server.compress { _, response ->
                        isCompressible(response.responseHeaders().get(HttpHeaderNames.CONTENT_TYPE, ""))
                    }
                }
            )
        }

    internal companion object {
        val COMPRESSIBLE_CONTENT_TYPES = listOf(
            "text/javascript",
            "application/javascript",
            "text/css",
            "text/html",
            "image/svg+xml",
        )

        /** Matched by prefix so a `;charset=` suffix still counts as the same type. */
        fun isCompressible(contentType: String): Boolean =
            COMPRESSIBLE_CONTENT_TYPES.any { contentType.startsWith(it) }
    }
}
