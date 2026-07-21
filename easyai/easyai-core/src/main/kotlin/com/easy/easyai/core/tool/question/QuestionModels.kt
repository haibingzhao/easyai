package com.easy.easyai.core.tool.question

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * A single option in a question prompt.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class QuestionOption(
    @JsonPropertyDescription("Display text for the option (1-5 words, concise). Avoid using 'Other' or '其它' as label - use the allowOther field instead for that case")
    val label: String,

    @JsonPropertyDescription("Optional explanation of the choice")
    val description: String? = null,
)

/**
 * A single question parameter for the ask_question tool.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class QuestionParameter(
    @JsonPropertyDescription("The complete question text to ask the user")
    val question: String,

    @JsonPropertyDescription("Very short label for the question (max 30 chars)")
    val header: String? = null,

    @JsonPropertyDescription("Available choices for the user to select from")
    val options: List<QuestionOption> = emptyList(),

    @JsonPropertyDescription("Whether to allow selecting multiple options")
    val multiple: Boolean = false,

    @JsonPropertyDescription("Whether to show an \"Other\" option that requires text input when selected. When true, an \"Other\" option is appended to options list and selecting it shows a text input field")
    val allowOther: Boolean = false,

    @JsonPropertyDescription("Placeholder text for the \"Other\" option text input")
    val otherPlaceholder: String? = null,

    @JsonPropertyDescription("Custom label for the \"Other\" option (default: \"其他\")")
    val otherLabel: String? = null
)

/**
 * Input parameters for the ask_question tool.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AskQuestionParameter(
    @JsonPropertyDescription("List of questions to ask the user")
    val questions: List<QuestionParameter>,

    @JsonPropertyDescription("Whether to show an optional supplementary information question at the end. When true, an additional text input is appended after the main questions")
    val allowSupplement: Boolean = false,

    @JsonPropertyDescription("The question text for the supplementary information")
    val supplementQuestion: String? = null,

    @JsonPropertyDescription("Placeholder text for the supplementary information input")
    val supplementPlaceholder: String? = null,

    @JsonPropertyDescription("Header/label for the supplementary question")
    val supplementHeader: String? = null
)
