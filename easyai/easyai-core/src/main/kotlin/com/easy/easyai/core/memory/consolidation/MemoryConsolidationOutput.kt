package com.easy.easyai.core.memory.consolidation

import com.easy.easyai.api.llm.schema.JsonSchemaGenerator
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * The one JSON object a consolidation batch must return.
 *
 * Every property is non-nullable and has no default, which is what makes [JsonSchemaGenerator] put
 * all of them in `required` — the shape OpenAI-style strict schema enforcement additionally demands.
 * Consequences the model must state explicitly instead of staying silent about: an entry it judged
 * unmergeable belongs in [leftoverDocIds], and a batch with no cluster to write still returns an
 * empty [clusters] rather than prose.
 */
data class MemoryConsolidationOutput(
    @field:JsonPropertyDescription(
        "Clusters to write. Each cluster needs at least 2 sourceDocIds taken from the entries in the prompt. " +
            "Empty only when no entry in this batch belongs together with another."
    )
    val clusters: List<ConsolidationCluster>,

    @field:JsonPropertyDescription(
        "docIds of the entries in this batch that you deliberately left untouched because they had no " +
            "sibling. Every docId from the prompt must appear either here or in one cluster's sourceDocIds."
    )
    val leftoverDocIds: List<String>
)

/** One merged memory plus the exact set of source documents it absorbs. */
data class ConsolidationCluster(
    @field:JsonPropertyDescription("Short unique title for the merged memory.")
    val mergedTitle: String,

    @field:JsonPropertyDescription("One-line summary of the merged memory.")
    val mergedDescription: String,

    @field:JsonPropertyDescription("Merged Markdown body keeping every distinct fact from the sources.")
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

/** Schema handed to the model as `outputSchema`; generated once from the data classes above. */
internal val CONSOLIDATION_OUTPUT_SCHEMA: String by lazy {
    JsonSchemaGenerator.generateForType(MemoryConsolidationOutput::class.java)
}
