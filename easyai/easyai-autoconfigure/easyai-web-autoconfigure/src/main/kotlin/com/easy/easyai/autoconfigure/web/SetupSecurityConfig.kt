package com.easy.easyai.autoconfigure.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain

/**
 * Minimal security configuration for Setup Mode.
 *
 * Permits all setup API endpoints and static assets without authentication.
 * Only active when `easyai.database.configured=false`.
 */
@Configuration
@EnableWebFluxSecurity
open class SetupSecurityConfig {

    @Bean
    open fun setupSecurityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .authorizeExchange { exchanges ->
                exchanges
                    .pathMatchers("/api/setup/**").permitAll()
                    .pathMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/favicon.svg").permitAll()
                    .pathMatchers(HttpMethod.OPTIONS).permitAll()
                    .anyExchange().permitAll()
            }
            .build()
    }
}
