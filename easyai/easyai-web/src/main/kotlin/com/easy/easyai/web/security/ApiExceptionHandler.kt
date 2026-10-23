package com.easy.easyai.web.security

import com.easy.easyai.web.context.ChatContextRejectedException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * Renders [ResponseStatusException] reasons into the `{ "error": ... }` body the console's
 * `readErrorMessage` already understands. WebFlux's default error body omits the message
 * (`server.error.include-message=never`), which hid actionable 400/403/404/409 reasons.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<Map<String, String>> =
        ResponseEntity
            .status(ex.statusCode)
            .body(mapOf("error" to (ex.reason ?: "Request failed")))

    @ExceptionHandler(ChatContextRejectedException::class)
    fun handleChatContextRejected(ex: ChatContextRejectedException): ResponseEntity<Map<String, String>> =
        ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(mapOf("error" to (ex.message ?: "Request failed")))
}
