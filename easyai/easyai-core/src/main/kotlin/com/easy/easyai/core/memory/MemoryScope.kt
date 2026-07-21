package com.easy.easyai.core.memory

/**
 * Memory scope — determines the root directory for storage.
 */
enum class MemoryScope {
    /** Cross-project memory stored in `~/.easyai/memory/`. */
    GLOBAL,
    /** Project-scoped memory stored in `{workspace}/.easyai/memory/`. */
    PROJECT
}
