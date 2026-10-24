package com.easy.easyai.web.model

/**
 * One turn of the ephemeral "Install with Agent" panel. No session id: the conversation lives only
 * in the browser and every turn rebuilds the full context from [history], [currentProposal] and
 * [currentArchive].
 */
data class SkillAgentRequest(
    val modelConfigId: String,
    val message: String,
    val history: List<LlmMessage> = emptyList(),
    /** Last inline preview the user has not installed yet; model context only, never persisted. */
    val currentProposal: SkillProposalInput? = null,
    /**
     * Compact inventory of the archive preview the user is looking at (no file contents). Replayed
     * so a follow-up turn can rename or explain entries without re-downloading the archive.
     */
    val currentArchive: SkillArchiveInput? = null,
    /** Write bucket shared with the upload endpoint: personal or group, resolved server-side. */
    val scope: String? = null
)

/** The package from the previous preview, replayed so follow-up turns can edit it. */
data class SkillProposalInput(
    val name: String,
    val summary: String? = null,
    val description: String? = null,
    val version: String? = null,
    val tags: List<String> = emptyList(),
    val files: List<SkillFileInput> = emptyList(),
    /** Previous high-risk/quality findings as one-line text, so the model can explain them. */
    val scanSummary: String? = null
)

data class SkillFileInput(val path: String, val content: String)

/**
 * Archive preview inventory replayed into the next turn. Bounded server-side (≤20 skills) so a
 * multi-turn conversation cannot grow the request body without limit.
 */
data class SkillArchiveInput(
    val url: String = "",
    val sha256: String = "",
    val skills: List<SkillArchiveEntryInput> = emptyList()
)

data class SkillArchiveEntryInput(
    val name: String = "",
    val entryPath: String = "",
    /** Non-null when the preview marked the entry as blocked by a name conflict. */
    val conflict: String? = null
)

/**
 * Install request from an archive preview card. [scope] is resolved server-side exactly like the
 * upload endpoint; no user identity is ever read from the body.
 */
data class SkillArchiveInstallRequest(
    val url: String = "",
    val sha256: String = "",
    val entries: List<SkillArchiveEntryRequest> = emptyList(),
    val scope: String? = null
)

/** One skill selected from the archive; [name] is the final install name (a preview rename). */
data class SkillArchiveEntryRequest(
    val entryPath: String = "",
    val name: String = ""
)

data class SkillArchiveInstallResponse(
    val results: List<SkillArchiveInstallResult> = emptyList()
)

/** Outcome of one entry: `installed`, `conflict`, `invalid` or `missing`. */
data class SkillArchiveInstallResult(
    val entryPath: String,
    val name: String,
    val outcome: String,
    val message: String? = null
)
