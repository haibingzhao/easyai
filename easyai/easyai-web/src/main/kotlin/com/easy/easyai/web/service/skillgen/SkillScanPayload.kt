package com.easy.easyai.web.service.skillgen

import com.easy.easyai.skills.SkillScanReport

/** Wire shape of a [SkillScanReport] inside a tool's JSON details; clients key badges and gates on it. */
internal object SkillScanPayload {

    fun of(report: SkillScanReport): Map<String, Any> = mapOf(
        "findings" to report.findings.map { finding ->
            buildMap {
                put("id", finding.id)
                put("category", finding.category.wire)
                put("severity", finding.severity.wire)
                put("message", finding.message)
                put("suggestion", finding.suggestion)
                put("blocking", finding.blocking)
                put("redacted", finding.redacted)
                finding.file?.let { put("file", it) }
                finding.line?.let { put("line", it) }
            }
        },
        "blocking" to report.blocking,
        "requiresAcknowledgement" to report.requiresAcknowledgement,
        "counts" to report.counts,
        "categoryCounts" to report.categoryCounts
    )
}
