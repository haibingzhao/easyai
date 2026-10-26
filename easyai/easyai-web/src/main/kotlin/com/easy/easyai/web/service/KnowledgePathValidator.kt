package com.easy.easyai.web.service

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Validates knowledge keys and source labels at the service boundary.
 *
 * Every caller that can create or address an entry (HTTP controller, agent tool) goes through
 * this object so an unvalidated `relativePath` or `source` cannot reach an OSS key or RAG key.
 */
internal object KnowledgePathValidator {

    fun validateRelativePath(path: String) {
        if (path.isBlank()) throw badRequest("Path must not be blank")
        if (path.startsWith("/")) throw badRequest("Path must be relative, not absolute: $path")
        if (path.contains("..")) throw badRequest("Path must not contain '..': $path")
        if (path.contains("\\")) throw badRequest("Path must not contain backslash: $path")
    }

    fun validateSource(source: String) {
        if (source.isBlank()) throw badRequest("Source must not be blank")
        if (source.contains("/")) throw badRequest("Source must not contain '/': $source")
        if (source.contains("..")) throw badRequest("Source must not contain '..': $source")
        if (source.contains("\\")) throw badRequest("Source must not contain backslash: $source")
    }

    private fun badRequest(message: String): ResponseStatusException =
        ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}
