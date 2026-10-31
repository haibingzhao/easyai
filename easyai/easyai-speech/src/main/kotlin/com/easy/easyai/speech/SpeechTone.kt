package com.easy.easyai.speech

/**
 * How a rewrite should *sound*, as distinct from how it should be performed.
 *
 * Deliberately orthogonal to [SpeechStyle]: a style is the plan's own reading of the text (the model
 * picks NARRATOR or PODCAST), while a tone is a preference the plan must honour. Merging the two
 * would make "the model felt like a different style today" indistinguishable from "the listener
 * asked for blunt".
 *
 * The instruction travels in the *user* prompt rather than the system prompt: the system prompt is
 * shared by every request and fingerprinted for reproducibility, and forking it per tone would mean
 * eight near-identical prompts to keep in sync. [instruction] is empty for [DEFAULT], which is what
 * keeps the default request byte-identical to what it was before tones existed.
 *
 * Every non-default tone is bounded by the same rule, applied in the prompt: only wording, order and
 * redundancy may change. A tone that talks the planner into dropping a number would fail
 * [SpeechPlanAssembler]'s fidelity gate and fall back to reading the source — the listener would hear
 * the original sentence and conclude the setting does nothing.
 */
enum class SpeechTone(val key: String, val instruction: String) {
    /** No instruction at all: the planner's own judgement, and the pre-tone request verbatim. */
    DEFAULT("default", ""),

    PROFESSIONAL("professional", "口吻专业严谨：清晰、准确、值得信赖，结论先给，依据随后，不夸大也不含糊。"),

    FRIENDLY("friendly", "口吻亲和友善：温暖、平易近人，可以鼓励听众，但不要寒暄和客套。"),

    BLUNT("blunt", "口吻直言不讳：开门见山，删掉过渡句和客套话，先说结论再说理由，不绕弯子。"),

    IMAGINATIVE("imaginative", "口吻生动形象：用比喻和类比帮助理解，比喻只做解释，不得改变原意或替换事实。"),

    EFFICIENT("efficient", "口吻高效务实：用最少的字数承载最大的信息量，只留结论和必要依据，删掉铺垫。"),

    SARCASTIC("sarcastic", "口吻略带吐槽：可以犀利调侃，但不能人身攻击、不能改变事实判断和结论。"),

    SOCRATIC("socratic", "口吻启发引导：先给结论，再用一句追问把思考交回给听众，不堆砌设问。");

    companion object {
        /** Unknown or blank keys fall back to [DEFAULT] rather than refusing the request. */
        @JvmStatic
        fun fromKey(key: String?): SpeechTone =
            entries.firstOrNull { it.key == key?.trim()?.lowercase() } ?: DEFAULT
    }
}
