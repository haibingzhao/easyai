package com.easy.easyai.common.util

import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Shared singleton [ObjectMapper] configured with Kotlin module support.
 *
 * Replaces scattered `jacksonObjectMapper()` instantiations across the codebase
 * to reduce memory overhead and share the serialization cache.
 */
object SharedObjectMapper {
    val instance: ObjectMapper = jacksonObjectMapper()
}
