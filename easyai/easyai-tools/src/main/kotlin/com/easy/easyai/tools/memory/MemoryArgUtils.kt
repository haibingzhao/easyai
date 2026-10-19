package com.easy.easyai.tools.memory

import com.easy.easyai.common.util.SharedObjectMapper

/**
 * Repair a list-valued tool argument into something deserializable as `List<String>`.
 *
 * Not every model honours an array type in a tool schema: some emit `["a","b"]` as a JSON-encoded
 * string, some emit one bare scalar where a list was asked for. Both are unambiguous to fix here and
 * impossible to tell apart once deserialization has mangled them, so every memory tool that takes a
 * list argument runs its raw value through this first.
 */
internal fun coerceToStringList(value: Any?): Any? {
    if (value !is String) return value
    return runCatching {
        val node = SharedObjectMapper.instance.readTree(value)
        if (node.isArray) node.toList().map { it.asString() } else listOf(value)
    }.getOrElse { listOf(value) }
}
