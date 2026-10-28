package com.easy.easyai.web.service

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Validates knowledge ids, folder names and upload paths at the service boundary.
 *
 * Every caller that can address an article (HTTP controller, agent tool) goes through this object
 * so an unvalidated id or path cannot reach an object-storage key or a RAG key.
 */
internal object KnowledgePathValidator {

    /** Article ids are opaque UUIDs; anything path-like is rejected. */
    fun validateArticleId(id: String) {
        if (id.isBlank()) throw badRequest("Id must not be blank")
        if (id.contains("/")) throw badRequest("Id must not contain '/': $id")
        if (id.contains("\\")) throw badRequest("Id must not contain backslash: $id")
        if (id.contains("..")) throw badRequest("Id must not contain '..': $id")
    }

    /** Upload path relative to the destination folder; sub-directories become folders. */
    fun validateRelativePath(path: String) {
        if (path.isBlank()) throw badRequest("Path must not be blank")
        if (path.startsWith("/")) throw badRequest("Path must be relative, not absolute: $path")
        if (path.contains("..")) throw badRequest("Path must not contain '..': $path")
        if (path.contains("\\")) throw badRequest("Path must not contain backslash: $path")
        val segments = path.split('/')
        if (segments.any { it.isBlank() || it == "." }) {
            throw badRequest("Path must not contain empty or '.' segments: $path")
        }
        if (segments.size > MAX_DEPTH) {
            throw badRequest("Path must not exceed $MAX_DEPTH segments: $path")
        }
    }

    fun validateFolderName(name: String) {
        if (name.isBlank()) throw badRequest("Folder name must not be blank")
        if (name != name.trim()) throw badRequest("Folder name must not have leading or trailing spaces")
        if (name.length > MAX_NAME_LENGTH) {
            throw badRequest("Folder name must not exceed $MAX_NAME_LENGTH characters")
        }
        if (name.contains("/") || name.contains("\\")) {
            throw badRequest("Folder name must not contain path separators: $name")
        }
        if (name.contains("..")) throw badRequest("Folder name must not contain '..': $name")
    }

    private fun badRequest(message: String): ResponseStatusException =
        ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    private const val MAX_NAME_LENGTH = 100
    private const val MAX_DEPTH = 10
}
