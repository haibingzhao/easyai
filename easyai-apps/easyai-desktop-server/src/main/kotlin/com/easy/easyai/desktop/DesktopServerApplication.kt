package com.easy.easyai.desktop

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * Entry point of the self-contained backend bundled with the EasyAI desktop client.
 *
 * Differences from [com.easy.easyai.example.WebExampleApplication]:
 * - No observability/OTel dependencies (clean distribution artifact)
 * - Serves the console frontend from classpath:/static (same-origin)
 * - Defaults to an embedded H2 file database (see application.properties)
 */
@SpringBootApplication
open class DesktopServerApplication

fun main(args: Array<String>) {
    org.springframework.boot.runApplication<DesktopServerApplication>(*args)
}
