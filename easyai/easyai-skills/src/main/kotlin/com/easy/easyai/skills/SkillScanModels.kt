package com.easy.easyai.skills

/**
 * Why one scan finding exists: a broken easyai-spec rule, a security risk, or a quality hint.
 * [wire] is the lowercase form exposed to clients.
 */
enum class SkillScanCategory(val wire: String) {
    SPEC("spec"),
    SECURITY("security"),
    QUALITY("quality")
}

/** Ordered most-severe first; [ordinal] is the report sort key. [wire] is the lowercase client form. */
enum class SkillScanSeverity(val wire: String) {
    CRITICAL("critical"),
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
    INFO("info")
}

/**
 * How [SkillPackageScanner] treats binary files other than `SKILL.md`.
 *
 * [BLOCK] is the inline-submission rule: the panel cannot carry bytes as text, so a binary asset is
 * refused. [WARN] is the archive rule: a downloaded zip legitimately ships images, fonts or bundled
 * assets. They are still reported as HIGH — installation copies them verbatim and never executes
 * them — but they do not block. `SKILL.md` itself stays blocking under both policies.
 */
enum class BinaryAssetPolicy { BLOCK, WARN }

/**
 * One deterministic scan verdict.
 *
 * @param id stable rule id (e.g. `security.reverse-shell`); clients key UI behavior and any future
 *   translation on it. [message] stays the authoritative wording — it carries the dynamic detail
 *   (byte counts, domains, masked fragments) a static per-id translation would drop.
 * @param line 1-based line inside [file]; null for file-level findings.
 * @param blocking true for hard spec errors and security criticals: the package is refused and no
 *   preview is produced.
 * @param redacted true when [message] carries a partially masked secret; clients must keep the
 *   fragment out of model context.
 */
data class SkillScanFinding(
    val id: String,
    val category: SkillScanCategory,
    val severity: SkillScanSeverity,
    val file: String? = null,
    val line: Int? = null,
    val message: String,
    val suggestion: String,
    val blocking: Boolean = false,
    val redacted: Boolean = false
)

/**
 * Result of [SkillPackageScanner.scan].
 *
 * [findings] is capped for transport; [counts] and the two gates are computed over the full set, so
 * a critical beyond the display cap still blocks.
 */
data class SkillScanReport(
    val findings: List<SkillScanFinding>,
    val blocking: Boolean,
    val requiresAcknowledgement: Boolean,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val version: String = "0.0.0",
    /** Always carries all five severity keys, including zeros, so clients never null-check. */
    val counts: Map<String, Int> = SkillScanSeverity.entries.associate { it.wire to 0 },
    /**
     * Per-category severity totals. Always carries every category and severity, including zeros;
     * computed over the full set so a truncated [findings] list cannot undercount a badge.
     */
    val categoryCounts: Map<String, Map<String, Int>> = SkillScanCategory.entries.associate { category ->
        category.wire to SkillScanSeverity.entries.associate { it.wire to 0 }
    }
)
