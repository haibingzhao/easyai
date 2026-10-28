package com.easy.easyai.core.knowledge

/**
 * Pure helpers for the folder tree.
 *
 * Display paths are derived on demand: a folder is persisted as one adjacency row, so renaming or
 * moving a folder never has to touch its descendants or the article storage keys.
 */
object KnowledgeTree {

    /** `Family/Recipes/Dinner`, or `""` when [folderId] is null/unknown. Cycle-safe. */
    @JvmStatic
    fun pathOf(folderId: String?, foldersById: Map<String, KnowledgeFolder>): String {
        if (folderId == null) return ""
        val names = ArrayDeque<String>()
        val seen = HashSet<String>()
        var current = foldersById[folderId]
        while (current != null && seen.add(current.id)) {
            names.addFirst(current.name)
            current = current.parentId?.let { foldersById[it] }
        }
        return names.joinToString("/")
    }

    /** [rootId] plus every descendant folder id. Cycle-safe. */
    @JvmStatic
    fun subtreeIds(rootId: String, folders: Collection<KnowledgeFolder>): Set<String> {
        val children = folders.groupBy { it.parentId }
        val result = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(rootId)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!result.add(id)) continue
            children[id]?.forEach { queue.add(it.id) }
        }
        return result
    }

    /** Resolve a display path (`Family/Recipes`) inside one bucket; null when it does not exist. */
    @JvmStatic
    fun resolve(
        bucketId: String,
        path: String,
        folders: Collection<KnowledgeFolder>,
    ): KnowledgeFolder? {
        val segments = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        var parentId: String? = null
        var current: KnowledgeFolder? = null
        for (segment in segments) {
            current = folders.firstOrNull {
                it.userId == bucketId && it.parentId == parentId && it.name == segment
            } ?: return null
            parentId = current.id
        }
        return current
    }
}
