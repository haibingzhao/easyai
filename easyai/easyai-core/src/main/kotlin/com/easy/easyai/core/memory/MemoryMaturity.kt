package com.easy.easyai.core.memory

/**
 * Maturity level of a memory entry, indicating how stable/verified the
 * captured knowledge is. Stored in frontmatter and surfaced in the Memory UI.
 */
enum class MemoryMaturity(val apiName: String) {
    /** Early-stage, possibly unverified knowledge. */
    LOW("low"),
    /** Moderately validated knowledge. */
    MEDIUM("medium"),
    /** Well-established, repeatedly confirmed knowledge. */
    HIGH("high");

    companion object {
        private val byApiName = entries.associateBy { it.apiName }

        /** Resolve a maturity from its wire name (case-insensitive), or null if not found. */
        @JvmStatic
        fun fromApiName(name: String): MemoryMaturity? = byApiName[name.lowercase()]
    }
}
