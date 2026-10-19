package com.easy.easyai.core.memory.consolidation

import com.easy.easyai.api.llm.schema.JsonSchemaGenerator
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * The grouping pass's answer: which entries belong together, and nothing else.
 *
 * Deciding membership is the part that needs the widest possible view — entries in different prompt
 * batches can never be compared — and it costs the model almost nothing to answer, because a group is
 * a list of docIds. That asymmetry is why grouping is its own pass instead of one more field of the
 * merge answer: the merge pass must re-emit every merged body, and its budget is what is actually scarce.
 *
 * Every property is non-nullable and has no default, which is what makes [JsonSchemaGenerator] put all
 * of them in `required` — the shape OpenAI-style strict schema enforcement additionally demands.
 */
data class MemoryConsolidationGroupingOutput(
    @field:JsonPropertyDescription(
        "Clusters to merge later. Each needs 2..N sourceDocIds taken from the entries in the prompt, and " +
            "the cluster's entries must describe the same subject. No bodies are shown here, so a cluster " +
            "whose combined size is reported as too large must not be formed."
    )
    val groups: List<ConsolidationGroup>,

    @field:JsonPropertyDescription(
        "docIds of the entries you deliberately left alone because they had no sibling. Every docId from " +
            "the prompt must appear either here or in exactly one group."
    )
    val leftoverDocIds: List<String>
)

/** One proposed cluster of source docIds, plus the title the merged entry should carry. */
data class ConsolidationGroup(
    @field:JsonPropertyDescription("Short title for the merged memory this cluster will become.")
    val title: String,

    @field:JsonPropertyDescription("All docIds proposed for one merged memory, in the order they should be read.")
    val sourceDocIds: List<String>
)

/**
 * The merge pass's answer: the merged bodies of the clusters it was shown, and the ones it refuses.
 *
 * Refusal is a first-class field rather than an absence: a cluster the model was asked to merge and
 * silently dropped cannot be told apart from one whose answer was truncated, and the writer needs that
 * difference to decide whether the sources stay active duplicates or simply were never offered.
 */
data class MemoryConsolidationMergeOutput(
    @field:JsonPropertyDescription(
        "Merged memories to write. Each needs at least 2 sourceDocIds from the clusters in this prompt."
    )
    val clusters: List<ConsolidationCluster>,

    @field:JsonPropertyDescription(
        "docIds of the entries in this prompt that you refuse to merge after reading their bodies, " +
            "because they turned out to be about different things. Every docId must appear either here or " +
            "in exactly one cluster."
    )
    val unmergedDocIds: List<String>
)

/** One merged memory plus the exact set of source documents it absorbs. */
data class ConsolidationCluster(
    @field:JsonPropertyDescription("Short unique title for the merged memory.")
    val mergedTitle: String,

    @field:JsonPropertyDescription("One-line summary of the merged memory.")
    val mergedDescription: String,

    @field:JsonPropertyDescription(
        "Distilled merged body: the key points only, organised under headings that fit the subject " +
            "rather than a fixed template. Aim for the target size the prompt states; every still-true " +
            "fact must survive in some form."
    )
    val mergedContent: String,

    @field:JsonPropertyDescription(
        "Mapping of a heading/anchor in mergedContent to the source docIds it came from; " +
            "the writer appends the inline source markers at those anchors. May be empty."
    )
    val sectionRefs: List<ConsolidationSectionRef>,

    @field:JsonPropertyDescription("All docIds absorbed into this merged memory (the full ref_ids set).")
    val sourceDocIds: List<String>
)

/** Provenance for one heading of a merged body. */
data class ConsolidationSectionRef(
    @field:JsonPropertyDescription("Exact heading or anchor text present in mergedContent.")
    val anchor: String,

    @field:JsonPropertyDescription("Source docIds backing this section.")
    val sourceDocIds: List<String>
)

/** Schema handed to the grouping pass as `outputSchema`; generated once from the data classes above. */
internal val CONSOLIDATION_GROUPING_SCHEMA: String by lazy {
    JsonSchemaGenerator.generateForType(MemoryConsolidationGroupingOutput::class.java)
}

/** Schema handed to the merge pass as `outputSchema`. */
internal val CONSOLIDATION_MERGE_SCHEMA: String by lazy {
    JsonSchemaGenerator.generateForType(MemoryConsolidationMergeOutput::class.java)
}
