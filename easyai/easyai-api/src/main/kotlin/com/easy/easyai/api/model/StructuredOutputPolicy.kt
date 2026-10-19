package com.easy.easyai.api.model

/**
 * Whether this model row may carry API-level structured-output enforcement on the wire.
 *
 * Undeclared used to mean "send the schema", which turned every row predating the capability column
 * into a bet that its gateway accepts `response_format`/`output_config`. Rows lose that bet in ways
 * the framework cannot see — the same model id answers differently on a dedicated deployment than on
 * the shared one — and the price was a schema-bearing background call failing outright, with the
 * gateway's reason swallowed as `400: Unknown`. So the default is now the choice that adds nothing to
 * the request: the loop falls back to conveying the schema in the prompt and validating the answer,
 * which works for every model. A model that really does enforce a schema is one
 * `capabilities.structuredOutput` declaration away from getting it, and the model catalog prefills
 * that field for rows created from it.
 */
object StructuredOutputPolicy {

    /** The row's declared support, or [StructuredOutputSupport.NONE] when it declares nothing. */
    @JvmStatic
    fun of(config: ModelProviderConfig): StructuredOutputSupport =
        config.capabilities?.structuredOutput ?: StructuredOutputSupport.NONE
}
