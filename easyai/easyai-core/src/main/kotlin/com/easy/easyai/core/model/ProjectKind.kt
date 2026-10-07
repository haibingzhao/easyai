package com.easy.easyai.core.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Kind of a [ProjectInfo] workspace.
 *
 * [USER] is a project explicitly registered by the user against a real repository path.
 * [TEMP] is a system-managed scratch directory bound to a single session, used when the
 * user chats without picking a project; it is hidden from project lists and cascade
 * deleted with its session.
 */
enum class ProjectKind(val value: String) {
    @JsonProperty("user")
    USER("user"),

    @JsonProperty("temp")
    TEMP("temp");

    companion object {
        /** Lenient parse: unknown or blank values fall back to [USER]. */
        @JvmStatic
        fun from(value: String?): ProjectKind =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: USER
    }
}
