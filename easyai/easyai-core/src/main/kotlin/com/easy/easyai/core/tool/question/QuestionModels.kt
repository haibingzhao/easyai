package com.easy.easyai.core.tool.question

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * A single option in a question prompt.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class QuestionOption(
    @param:JsonPropertyDescription("Display text for the option (1-5 words, concise). Avoid using 'Other' or '其它' as label - use the allowOther field instead for that case")
    val label: String,

    @param:JsonPropertyDescription("Optional explanation of the choice")
    val description: String? = null,
)

/**
 * A single question parameter for the ask_question tool.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class QuestionParameter(
    @param:JsonPropertyDescription("The complete question text to ask the user")
    val question: String,

    @param:JsonPropertyDescription("Very short label for the question (max 30 chars)")
    val header: String? = null,

    @param:JsonPropertyDescription("Available choices for the user to select from")
    val options: List<QuestionOption> = emptyList(),

    @param:JsonPropertyDescription("Whether to allow selecting multiple options")
    val multiple: Boolean = false,

    @param:JsonPropertyDescription("Whether to show an \"Other\" option that requires text input when selected. When true, an \"Other\" option is appended to options list and selecting it shows a text input field")
    val allowOther: Boolean = false,

    @param:JsonPropertyDescription("Placeholder text for the \"Other\" option text input")
    val otherPlaceholder: String? = null,

    @param:JsonPropertyDescription("Custom label for the \"Other\" option (default: \"其他\")")
    val otherLabel: String? = null
)

/**
 * Input parameters for the ask_question tool.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AskQuestionParameter(
    @param:JsonPropertyDescription("List of questions to ask the user")
    val questions: List<QuestionParameter>,

    @param:JsonPropertyDescription("Whether to show an optional supplementary information question at the end. When true, an additional text input is appended after the main questions")
    val allowSupplement: Boolean = false,

    @param:JsonPropertyDescription("The question text for the supplementary information")
    val supplementQuestion: String? = null,

    @param:JsonPropertyDescription("Placeholder text for the supplementary information input")
    val supplementPlaceholder: String? = null,

    @param:JsonPropertyDescription("Header/label for the supplementary question")
    val supplementHeader: String? = null
)
