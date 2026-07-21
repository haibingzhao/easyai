package com.easy.easyai.web.security

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class AuthExceptionHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(AuthException::class)
    fun handleAuthException(ex: AuthException): ResponseEntity<Map<String, String>> {
        logger.debug("Auth error ({}): {}", ex.statusCode, ex.message)
        return ResponseEntity
            .status(ex.statusCode)
            .body(mapOf("error" to (ex.message ?: "Authentication error")))
    }
}
