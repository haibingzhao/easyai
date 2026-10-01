package com.easy.easyai.skills

import org.slf4j.LoggerFactory
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Parses SKILL.md files: extracts YAML front matter and markdown body.
 */
internal object SkillLoader {

    private val logger = LoggerFactory.getLogger(SkillLoader::class.java)
    private val yamlMapper = YAMLMapper()

    /** Frontmatter delimiter on its own line. Values may contain `---` without breaking the split. */
    private const val FRONTMATTER_DELIMITER = "---"

    /**
     * Parse a SKILL.md file at the given path together with its raw frontmatter map, so callers
     * that need fields beyond [SkillInfo] (catalog `version`) do not re-read or re-parse the file.
     */
    fun parseWithFrontmatter(path: Path): Pair<SkillInfo, Map<String, Any?>> {
        val content = path.readText()
        val (frontmatter, body) = extractFrontmatter(content)
        val name = frontmatter["name"] as? String
        if (name.isNullOrBlank()) {
            throw IllegalArgumentException("SKILL.md at $path missing required 'name' field")
        }
        val description = frontmatter["description"] as? String
        val tags = when (val raw = frontmatter["tags"]) {
            is List<*> -> raw.filterIsInstance<String>().toSet()
            is String -> setOf(raw)
            else -> emptySet()
        }
        val examples = when (val raw = frontmatter["examples"]) {
            is List<*> -> raw.filterIsInstance<String>().toSet()
            is String -> setOf(raw)
            else -> emptySet()
        }
        val info = SkillInfo(
            name = name,
            description = description,
            location = path.toAbsolutePath(),
            content = body.trim(),
            tags = tags,
            examples = examples,
        )
        return info to frontmatter
    }

    /**
     * Rewrite the frontmatter `name` of the SKILL.md at [path] to [newName], preserving every
     * other key. Used when a copied skill is registered under a user-chosen name that differs
     * from what its source file declared.
     */
    fun rewriteName(path: Path, newName: String) {
        val content = path.readText()
        val (frontmatter, body) = extractFrontmatter(content)
        val merged = LinkedHashMap(frontmatter)
        merged["name"] = newName
        path.writeText("$FRONTMATTER_DELIMITER\n${yamlFields(merged)}\n$FRONTMATTER_DELIMITER\n\n$body\n")
    }

    /**
     * The frontmatter block as plain `key: value` lines.
     *
     * Jackson's YAML writer emits an explicit `---` document-start marker, and a second one from the
     * frontmatter wrapper above would terminate the block immediately and read back as an empty map.
     */
    private fun yamlFields(frontmatter: Map<String, Any?>): String {
        val yaml = yamlMapper.writeValueAsString(frontmatter).trim().lineSequence()
        return yaml.dropWhile { it.trim().isEmpty() || it.trim() == FRONTMATTER_DELIMITER || it.startsWith("%") }
            .joinToString("\n").trim()
    }

    /**
     * Parse a SKILL.md file at the given path.
     * @return SkillInfo with extracted metadata and content.
     * @throws IllegalArgumentException if the required 'name' field is missing.
     */
    fun parse(path: Path): SkillInfo = parseWithFrontmatter(path).first

    /**
     * Split content at a `---` line boundary, parse the YAML frontmatter.
     *
     * Matching is line-based: a `---` inside a value (e.g. `name: some---thing`) does not terminate
     * the frontmatter, and a longer dash run like `----` does not leak a stray `-` into the body.
     * The rule matches [com.easy.easyai.rag.RagSkillStore]'s chunk parser so the same document reads
     * identically from disk and from the retrieval index.
     *
     * Returns (frontmatter map, markdown body).
     */
    fun extractFrontmatter(content: String): Pair<Map<String, Any?>, String> {
        val lines = content.lines()
        if (lines.firstOrNull()?.trim() != FRONTMATTER_DELIMITER) {
            return emptyMap<String, Any?>() to content
        }
        val endIndex = lines.drop(1).indexOfFirst { it.trim() == FRONTMATTER_DELIMITER }
        if (endIndex < 0) {
            return emptyMap<String, Any?>() to content
        }
        val yamlContent = lines.subList(1, endIndex + 1).joinToString("\n").trim()
        val body = lines.subList(endIndex + 2, lines.size).joinToString("\n").trim()
        val map: Map<String, Any?> = try {
            yamlMapper.readValue(yamlContent)
        } catch (e: Exception) {
            logger.warn("Failed to parse YAML frontmatter: {}", e.message)
            emptyMap()
        }
        return map to body
    }
}