package com.easy.easyai.web.security

import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.auth.jwt.JwtTokenProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair

/**
 * Spring Security configuration for EasyAI WebFlux application.
 *
 * Configures:
 * - JWT authentication filter
 * - CORS for frontend access
 * - Public routes (auth endpoints, health check)
 * - Authenticated routes (all other API endpoints)
 *
 * When `easyai.auth.enabled=false`, all routes are permitted (local dev mode).
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    fun securityWebFilterChain(
        http: ServerHttpSecurity,
        jwtAuthenticationFilter: JwtAuthenticationFilter,
        authProperties: AuthProperties
    ): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource(authProperties)) }
            .authorizeExchange { exchanges ->
                if (!authProperties.enabled) {
                    // Auth disabled: permit all (local dev mode)
                    exchanges.anyExchange().permitAll()
                } else {
                    exchanges
                        // Auth endpoints are always public
                        .pathMatchers("/api/auth/**").permitAll()
                        // Setup endpoints are public (for database configuration wizard)
                        .pathMatchers("/api/setup/**").permitAll()
                        // Health check is public
                        .pathMatchers("/api/chat/health").permitAll()
                        // Static console assets served same-origin (desktop client)
                        .pathMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/favicon.svg").permitAll()
                        // OPTIONS preflight
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        // Everything else requires authentication
                        .anyExchange().authenticated()
                }
            }
            .addFilterAt(jwtAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            .build()
    }

    @Bean
    fun jwtTokenProvider(authProperties: AuthProperties): JwtTokenProvider {
        val keyPair = if (authProperties.privateKeyPath.isNotBlank() && authProperties.publicKeyPath.isNotBlank()) {
            val privateKeyPem = Files.readString(Path.of(authProperties.privateKeyPath))
            val publicKeyPem = Files.readString(Path.of(authProperties.publicKeyPath))
            val privateKey = JwtTokenProvider.loadPrivateKey(privateKeyPem)
            val publicKey = JwtTokenProvider.loadPublicKey(publicKeyPem)
            KeyPair(publicKey, privateKey)
        } else {
            JwtTokenProvider.generateDevKeyPair()
        }
        return JwtTokenProvider(
            privateKey = keyPair.private,
            publicKey = keyPair.public,
            accessExpirySeconds = authProperties.accessTokenExpirationSeconds,
            refreshExpirySeconds = authProperties.refreshTokenExpirationSeconds
        )
    }

    @Bean
    fun jwtAuthenticationFilter(
        jwtTokenProvider: JwtTokenProvider,
        authProperties: AuthProperties
    ): JwtAuthenticationFilter {
        return JwtAuthenticationFilter(jwtTokenProvider, authProperties.enabled)
    }

    private fun corsConfigurationSource(authProperties: AuthProperties): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = authProperties.corsAllowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
            allowedHeaders = listOf("*")
            allowCredentials = true
            maxAge = 3600
        }
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }
}
