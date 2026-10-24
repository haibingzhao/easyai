package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillCatalogEntry
import java.util.Base64

/**
 * Deterministic easyai-spec and security scan over the exact bytes a skill package is about to be
 * installed from.
 *
 * This is a rule engine, not a semantic reviewer: it cannot prove a skill is safe, and it is
 * deliberately independent of any LLM so an injected package cannot talk its way past the gate.
 * Hard spec errors and security criticals [SkillScanReport.blocking] the package; security highs
 * require an explicit user acknowledgement ([SkillScanReport.requiresAcknowledgement]).
 */
object SkillPackageScanner {

    private const val MAX_FINDINGS = 100
    private const val MAX_NAME_LENGTH = 64
    private const val MAX_FILES = 50
    private const val LONG_BODY_LINES = 500
    private const val VERY_LONG_BODY_LINES = 2000
    private const val MAX_LINE_LENGTH = 5_000
    private const val REGEX_SCAN_LIMIT = 8_000
    /**
     * Per-line scan budget. Everything up to it is inspected; a longer line is reported as a
     * high-severity finding but does not block installation. Legitimate minified bundles and data
     * files exceed it, while encoded payloads are still caught by the blob rules and the chunked
     * scans on both sides of the budget.
     */
    private const val MAX_LINE_SCAN_CHARS = 512 * 1024
    private const val CHUNK_OVERLAP = 512
    private const val MIN_OBFUSCATED_BLOB = 200

    private val SLUG = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]*$""")
    private val DRIVE_PATH = Regex("""^[A-Za-z]:""")
    private val SEMVER = Regex("""^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$""")
    private val MARKDOWN_LINK = Regex("""\]\(\s*([^)\s]+)(?:\s+"[^"]*")?\s*\)""")
    private val ABSOLUTE_USER_PATH = Regex("""(?:/Users/|/home/|[A-Za-z]:\\Users\\)[A-Za-z0-9._-]+""")
    private val HIDDEN_UNICODE = Regex("""[\u200B-\u200F\u202A-\u202E\u2060-\u2064\uFEFF]""")
    private val BASE64_BLOB = Regex("""[A-Za-z0-9+/]{$MIN_OBFUSCATED_BLOB,}={0,2}""")
    private val HEX_BLOB = Regex("""\b(?:[0-9a-fA-F]{2}){100,}\b""")
    private val HTML_COMMENT = Regex("""<!--[\s\S]*?-->""")
    private val URL = Regex("""https?://([^/\s)"'<>]+)""", RegexOption.IGNORE_CASE)
    private val NETWORK_COMMAND = Regex(
        """\b(?:curl|wget|Invoke-WebRequest|Invoke-RestMethod|requests\.(?:get|post|put|delete)|urllib|http\.client|fetch\s*\(|WebSocket|scp|rsync|socat|nc|ncat)\b""",
        RegexOption.IGNORE_CASE
    )
    private val DEPENDENCY_INSTALL = Regex(
        """\b(?:npm\s+(?:install|i)|yarn\s+add|pnpm\s+add|pip3?\s+install|python3?\s+-m\s+pip\s+install|brew\s+install|apt(?:-get)?\s+install)\b""",
        RegexOption.IGNORE_CASE
    )
    private val PINNED_VERSION = Regex("""(?:==\s*\d|@\d+\.\d+|@latest|-v?\d+\.\d+)""")

    /**
     * Any run of `rm` flag tokens (`-v`, `-rf`, `--recursive`, `--no-preserve-root`) plus the
     * whitespace after it. Regex backtracking hands the decisive tokens back to the alternation
     * below, so flag order and separation do not decide detection.
     */
    private val ANY_FLAGS = """(?:--?[a-zA-Z-]+\s+)*"""

    /**
     * A delete target that is the filesystem root (optionally globbed) or a home directory — `~`,
     * `~/`, `$HOME/`, `${HOME}/`. Anything below those is scoped, not catastrophic. The root
     * patterns stay in the regex only: spelling them here with a slash-star would open a nested
     * Kotlin block comment and swallow the rest of the file.
     */
    private val DESTRUCTIVE_TARGET =
        """(?:/\*{0,2}|~/?|\$\{?HOME\}?/?)(?=\s|${'$'}|["';])"""

    private val BINARY_EXTENSIONS = setOf(
        "exe", "dll", "so", "dylib", "bin", "jar", "class", "wasm", "o", "a",
        "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "pkg", "dmg", "iso", "img",
        "woff", "woff2", "ttf", "otf", "eot",
        "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "tiff", "pdf",
        "mp3", "wav", "ogg", "mp4", "mov", "avi", "mkv",
        "sqlite", "db"
    )

    private val SCRIPT_EXTENSIONS = setOf(
        "sh", "bash", "zsh", "fish", "py", "js", "mjs", "cjs", "ts", "rb", "pl", "php", "ps1", "bat", "cmd", "lua"
    )

    private val CREDENTIAL_FILE_NAMES = setOf(
        "credentials", "credentials.json", "id_rsa", "id_ed25519", "id_dsa",
        "keystore", "keystore.json", ".npmrc", ".netrc", "secrets.yml", "secrets.yaml"
    )
    private val ENV_ALLOWLIST = setOf(".env.example", ".env.sample", ".env.template", ".env.dist")

    private data class ScanFile(
        val path: String,
        val text: String?,
        val binary: Boolean
    ) {
        val lines: List<String> get() = text?.lines().orEmpty()
    }

    private data class Metadata(
        val description: String? = null,
        val tags: List<String> = emptyList(),
        val examples: List<String> = emptyList(),
        val version: String = SkillCatalogEntry.DEFAULT_VERSION,
        val body: String? = null
    )

    /**
     * Scan [files] as the package that would be installed under [name].
     *
     * @param maxBytes effective package cap; the agent panel passes
     *   `min(config.packageMaxBytes, 128 KiB)` and uploads pass `config.packageMaxBytes`
     * @param binaryAssets [BinaryAssetPolicy.WARN] for archive installs, whose legitimately shipped
     *   assets are reported but not refused; the default keeps inline submissions text-only
     */
    fun scan(
        name: String,
        files: List<SkillUploadFile>,
        maxBytes: Long,
        binaryAssets: BinaryAssetPolicy = BinaryAssetPolicy.BLOCK
    ): SkillScanReport {
        val findings = Findings()

        if (files.isEmpty()) {
            findings.add(
                spec(
                    "spec.package-empty", SkillScanSeverity.HIGH, null, null,
                    "The package carries no files", "Submit the complete skill directory, including SKILL.md.", blocking = true
                )
            )
        }
        val totalBytes = files.sumOf { it.content.size.toLong() }
        if (maxBytes > 0 && totalBytes > maxBytes) {
            findings.add(
                spec(
                    "spec.package-too-large", SkillScanSeverity.HIGH, null, null,
                    "The package is $totalBytes bytes, over the $maxBytes-byte limit",
                    "Remove large files or use Upload from computer for a bigger package.", blocking = true
                )
            )
        }

        val scanned = normalize(files, findings)
        val skillFile = scanned.firstOrNull { it.path == SkillPaths.SKILL_FILE_NAME }
        val metadata = if (skillFile == null) {
            findings.add(
                spec(
                    "spec.skill-md-missing", SkillScanSeverity.HIGH, null, null,
                    "The package has no SKILL.md at its root",
                    "Put SKILL.md at the top level (not inside a folder).", blocking = true
                )
            )
            Metadata()
        } else {
            parseMetadata(name, skillFile, findings)
        }

        inspectPackage(scanned, metadata, findings, binaryAssets)
        scanned.forEach { inspectSecurity(it, findings) }

        val all = findings.all()
        val sorted = all.sortedWith(
            compareByDescending<SkillScanFinding> { it.blocking }
                .thenBy { it.severity.ordinal }
                .thenBy {
                    when (it.category) {
                        SkillScanCategory.SECURITY -> 0
                        SkillScanCategory.SPEC -> 1
                        SkillScanCategory.QUALITY -> 2
                    }
                }
                .thenBy { it.file ?: "" }
                .thenBy { it.line ?: 0 }
        )
        return SkillScanReport(
            findings = sorted.take(MAX_FINDINGS),
            blocking = all.any { it.blocking },
            requiresAcknowledgement = all.any {
                it.category == SkillScanCategory.SECURITY && it.severity == SkillScanSeverity.HIGH
            },
            description = metadata.description,
            tags = metadata.tags,
            version = metadata.version,
            counts = SkillScanSeverity.entries.associate { severity ->
                severity.wire to all.count { it.severity == severity }
            },
            categoryCounts = SkillScanCategory.entries.associate { category ->
                category.wire to SkillScanSeverity.entries.associate { severity ->
                    severity.wire to all.count { it.category == category && it.severity == severity }
                }
            }
        )
    }

    // ── Paths ──────────────────────────────────────────────────────────────────

    private fun normalize(files: List<SkillUploadFile>, findings: Findings): List<ScanFile> {
        val seen = HashSet<String>()
        val result = mutableListOf<ScanFile>()
        for (file in files) {
            val raw = file.relativePath
            var rejected = false
            if (raw.isBlank()) {
                findings.add(specPath("spec.path-blank", raw, "A file has an empty path", "Give every file a relative path."))
                rejected = true
            }
            if ('\\' in raw) {
                findings.add(
                    specPath("spec.path-backslash", raw, "Path uses a backslash: $raw", "Use forward slashes in relative paths.")
                )
                rejected = true
            }
            // A `.` segment is navigation noise, not an escape: `./SKILL.md` is the root SKILL.md,
            // and rejecting it would refuse a package the pipeline installs without complaint.
            // `..` stays a rejection below.
            val segments = raw.replace('\\', '/').split('/').filterNot { it == "." }
            val path = segments.joinToString("/")
            if (path.isBlank() && !rejected) {
                findings.add(specPath("spec.path-blank", raw, "A file has an empty path", "Give every file a relative path."))
                rejected = true
            }
            if (path.startsWith("/")) {
                findings.add(specPath("spec.path-absolute", path, "Path is absolute: $path", "Use a path relative to the skill directory."))
                rejected = true
            }
            if (DRIVE_PATH.containsMatchIn(path)) {
                findings.add(
                    specPath("spec.path-absolute", path, "Path is an absolute drive path: $path", "Use a path relative to the skill directory.")
                )
                rejected = true
            }
            if (segments.contains("..")) {
                findings.add(specPath("spec.path-traversal", path, "Path escapes the skill directory: $path", "Remove '..' segments."))
                rejected = true
            }
            if (path.any { it.code < 0x20 }) {
                findings.add(specPath("spec.path-control", path, "Path contains control characters", "Use printable relative paths."))
                rejected = true
            }
            if (!seen.add(path)) {
                findings.add(specPath("spec.path-duplicate", path, "Duplicate file path: $path", "Send each path once."))
                rejected = true
            }
            if (rejected) continue
            val binary = isBinary(path, file.content)
            result.add(ScanFile(path, if (binary) null else String(file.content, Charsets.UTF_8), binary))
        }
        return result
    }

    private fun isBinary(path: String, bytes: ByteArray): Boolean {
        val extension = path.substringAfterLast('.', "").lowercase()
        if (extension in BINARY_EXTENSIONS) return true
        if (bytes.any { it == 0.toByte() }) return true
        if (bytes.isEmpty()) return false
        val sample = bytes.take(4096)
        val control = sample.count { byte ->
            val value = byte.toInt() and 0xFF
            value < 0x09 || value in 0x0E..0x1F
        }
        return control * 100 / sample.size > 5
    }

    // ── easyai spec ────────────────────────────────────────────────────────────

    private fun parseMetadata(name: String, skill: ScanFile, findings: Findings): Metadata {
        if (skill.binary) {
            findings.add(
                spec(
                    "spec.binary-file", SkillScanSeverity.HIGH, skill.path, null,
                    "SKILL.md is not text", "SKILL.md must be UTF-8 markdown.", blocking = true
                )
            )
            return Metadata()
        }
        val text = skill.text.orEmpty()
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") {
            findings.add(
                spec(
                    "spec.frontmatter-missing", SkillScanSeverity.HIGH, skill.path, 1,
                    "SKILL.md does not start with a YAML frontmatter block",
                    "Start the file with '---' followed by at least name and description.", blocking = true
                )
            )
            return Metadata(body = text)
        }
        val closing = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
        if (closing == null) {
            findings.add(
                spec(
                    "spec.frontmatter-unterminated", SkillScanSeverity.HIGH, skill.path, 1,
                    "SKILL.md frontmatter is not closed with '---'",
                    "Close the frontmatter block with a '---' line.", blocking = true
                )
            )
            return Metadata()
        }

        val frontmatter = SkillLoader.extractFrontmatter(text).first
        if (frontmatter.isEmpty()) {
            findings.add(
                spec(
                    "spec.frontmatter-invalid", SkillScanSeverity.HIGH, skill.path, 1,
                    "SKILL.md frontmatter is empty or is not valid YAML",
                    "Provide a YAML mapping with at least name and description.", blocking = true
                )
            )
            return Metadata()
        }

        val declared = frontmatter["name"] as? String
        when {
            declared.isNullOrBlank() -> findings.add(
                spec(
                    "spec.name-missing", SkillScanSeverity.HIGH, skill.path, 2,
                    "Frontmatter has no name", "Add 'name: <slug>' matching the requested name.", blocking = true
                )
            )
            declared != null && !SLUG.matches(declared) -> findings.add(
                spec(
                    "spec.name-invalid", SkillScanSeverity.HIGH, skill.path, 2,
                    "Skill name '$declared' is not a path-safe slug",
                    "Use letters, digits, dot, dash and underscore, starting with a letter or digit.", blocking = true
                )
            )
        }
        if (!declared.isNullOrBlank()) {
            if (declared.length > MAX_NAME_LENGTH) {
                findings.add(
                    spec(
                        "spec.name-too-long", SkillScanSeverity.HIGH, skill.path, 2,
                        "Skill name is ${declared.length} characters; the limit is $MAX_NAME_LENGTH",
                        "Shorten the name.", blocking = true
                    )
                )
            }
            if (declared != name) {
                findings.add(
                    spec(
                        "spec.name-mismatch", SkillScanSeverity.HIGH, skill.path, 2,
                        "Frontmatter name '$declared' does not match the submitted name '$name'",
                        "Make the frontmatter name and the submitted name identical.", blocking = true
                    )
                )
            }
        }

        val description = (frontmatter["description"] as? String)?.trim()
        if (description.isNullOrBlank()) {
            findings.add(
                spec(
                    "spec.description-missing", SkillScanSeverity.HIGH, skill.path, null,
                    "Frontmatter has no description; EasyAI only advertises described skills to agents",
                    "Add a specific 'description: Use when ...' line.", blocking = true
                )
            )
        }

        val tags = stringsOf(frontmatter["tags"])
        val examples = stringsOf(frontmatter["examples"])
        val version = frontmatter["version"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: SkillCatalogEntry.DEFAULT_VERSION
        if (version != SkillCatalogEntry.DEFAULT_VERSION && !SEMVER.matches(version)) {
            findings.add(
                spec(
                    "spec.version-not-semver", SkillScanSeverity.INFO, skill.path, null,
                    "Version '$version' is not semver", "Use a version like 1.2.0, or omit it."
                )
            )
        }

        val body = lines.subList(closing + 1, lines.size).joinToString("\n").trim()
        if (body.isBlank()) {
            findings.add(
                spec(
                    "spec.body-empty", SkillScanSeverity.HIGH, skill.path, null,
                    "SKILL.md has no body; the agent would load an empty skill",
                    "Write the procedure the agent should follow.", blocking = true
                )
            )
        }

        if (tags.isEmpty()) {
            findings.add(
                quality(
                    "quality.missing-tags", skill.path, null,
                    "No tags declared", "Add a few tags to improve discovery."
                )
            )
        }
        if (examples.isEmpty()) {
            findings.add(
                quality(
                    "quality.missing-examples", skill.path, null,
                    "No examples declared", "Optionally add 'examples' to show typical requests."
                )
            )
        }

        val bodyLines = body.lines().size
        if (bodyLines > VERY_LONG_BODY_LINES) {
            findings.add(
                spec(
                    "spec.body-very-long", SkillScanSeverity.HIGH, skill.path, null,
                    "SKILL.md body has $bodyLines lines; the agent loads all of it",
                    "Move detail into references/*.md and keep SKILL.md focused."
                )
            )
        } else if (bodyLines > LONG_BODY_LINES) {
            findings.add(
                spec(
                    "spec.body-long", SkillScanSeverity.MEDIUM, skill.path, null,
                    "SKILL.md body has $bodyLines lines", "Consider moving long sections into references/*.md."
                )
            )
        }

        return Metadata(description, tags, examples, version, body)
    }

    private fun inspectPackage(
        files: List<ScanFile>,
        metadata: Metadata,
        findings: Findings,
        binaryAssets: BinaryAssetPolicy
    ) {
        val skillPaths = files.map { it.path }.toSet()
        for (file in files) {
            if (file.path != SkillPaths.SKILL_FILE_NAME &&
                file.path.substringAfterLast('/') == SkillPaths.SKILL_FILE_NAME
            ) {
                findings.add(
                    spec(
                        "spec.nested-skill-md", SkillScanSeverity.MEDIUM, file.path, null,
                        "Nested SKILL.md is packaged but never loaded; discovery only reads one level",
                        "Move this content under references/, or install it as a separate skill."
                    )
                )
            }
            if (file.path.substringAfterLast('/') !in ENV_ALLOWLIST &&
                file.path.split('/').any { it.startsWith(".") }
            ) {
                findings.add(
                    spec(
                        "spec.hidden-file", SkillScanSeverity.LOW, file.path, null,
                        "Hidden or dot file included", "Remove repository/editor metadata such as .git or .DS_Store."
                    )
                )
            }
            // SKILL.md is the one mandated uppercase basename; flagging it would put a quality hint
            // on every valid package.
            if (file.path.substringAfterLast('/') != SkillPaths.SKILL_FILE_NAME &&
                file.path.split('/').any { segment ->
                    segment.isNotEmpty() && (segment.any { it.isWhitespace() } || segment.first().isUpperCase())
                }
            ) {
                findings.add(
                    quality(
                        "quality.file-naming", file.path, null,
                        "File name uses spaces or leading capitals", "Prefer lowercase names with dashes."
                    )
                )
            }
            if (file.binary && file.path != SkillPaths.SKILL_FILE_NAME) {
                val warnOnly = binaryAssets == BinaryAssetPolicy.WARN
                findings.add(
                    spec(
                        "spec.binary-file", SkillScanSeverity.HIGH, file.path, null,
                        if (warnOnly) "Binary or executable file included; it is installed as-is and never executed"
                        else "Binary or executable file included",
                        if (warnOnly) "EasyAI stores the file but never runs it; remove it if it is not needed."
                        else "v1 accepts text files only; use Upload from computer.",
                        blocking = !warnOnly
                    )
                )
            }
        }

        if (files.size > MAX_FILES) {
            findings.add(
                quality(
                    "quality.many-files", null, null,
                    "Package contains ${files.size} files", "Consider keeping SKILL.md and references/ only."
                )
            )
        }

        val body = metadata.body ?: return
        body.lines().forEachIndexed { index, line ->
            MARKDOWN_LINK.findAll(line).forEach { match ->
                val target = match.groupValues[1]
                if (target.startsWith("http://", true) || target.startsWith("https://", true) ||
                    target.startsWith("mailto:", true) || target.startsWith("#") || target.startsWith("{{")
                ) {
                    return@forEach
                }
                val clean = target.substringBefore('#').substringBefore('?').removePrefix("./")
                if (clean.isBlank()) return@forEach
                val linked = skillPaths.any { it == clean || it.endsWith("/$clean") }
                if (!linked) {
                    findings.add(
                        spec(
                            "spec.broken-reference", SkillScanSeverity.MEDIUM, SkillPaths.SKILL_FILE_NAME, index + 1,
                            "References a file that is not in the package: $target",
                            "Include the referenced file or remove the link."
                        )
                    )
                }
            }
        }

        val userPath = ABSOLUTE_USER_PATH.find(body)
        if (userPath != null) {
            val line = body.take(userPath.range.first).count { it == '\n' } + 1
            findings.add(
                spec(
                    "spec.absolute-user-path", SkillScanSeverity.MEDIUM, SkillPaths.SKILL_FILE_NAME, line,
                    "Contains a machine-specific absolute path: ${userPath.value}",
                    "Use a relative path or a placeholder so the skill works for every user."
                )
            )
        }
    }

    // ── Security ───────────────────────────────────────────────────────────────

    private data class Rule(
        val id: String,
        val severity: SkillScanSeverity,
        val pattern: Regex,
        val message: String,
        val suggestion: String,
        val redactMatch: Boolean = false,
        val blocking: Boolean = severity == SkillScanSeverity.CRITICAL
    )

    private val RULES = listOf(
        Rule(
            "security.reverse-shell", SkillScanSeverity.CRITICAL,
            Regex(
                """(?:bash\s+-i\s+>&\s*/dev/tcp/|/dev/tcp/\d|n(?:c|cat)\s+(?:-[a-zA-Z]*e|--exec)\s|socat\s+[^\n]*\bexec:|\b(?:python3?|perl|ruby)\b[^\n]*\bsocket\b[^\n]*\b(?:connect|exec)\b)""",
                RegexOption.IGNORE_CASE
            ),
            "Remote-shell pattern detected",
            "Remove the remote-shell code; a skill must not open an interactive shell to a remote host."
        ),
        Rule(
            "security.destructive-delete", SkillScanSeverity.CRITICAL,
            Regex(
                """\brm\s+$ANY_FLAGS""" +
                    """(?:-[a-zA-Z]*(?:r[a-zA-Z]*f|f[a-zA-Z]*r)[a-zA-Z]*""" +
                    """|-[a-zA-Z]*r[a-zA-Z]*\s+$ANY_FLAGS-[a-zA-Z]*f[a-zA-Z]*""" +
                    """|-[a-zA-Z]*f[a-zA-Z]*\s+$ANY_FLAGS-[a-zA-Z]*r[a-zA-Z]*""" +
                    """|--recursive\s+$ANY_FLAGS--force""" +
                    """|--force\s+$ANY_FLAGS--recursive)""" +
                    """\s+$ANY_FLAGS["']?$DESTRUCTIVE_TARGET""",
                RegexOption.IGNORE_CASE
            ),
            "Recursive deletion of the filesystem root or the home directory",
            "Scope destructive commands to a task directory; never delete / or a home directory."
        ),
        Rule(
            "security.disk-wipe", SkillScanSeverity.CRITICAL,
            Regex("""\bmkfs(?:\.[a-z0-9]+)?\s|\bdd\s+[^\n]*\bof=/dev/""", RegexOption.IGNORE_CASE),
            "Disk-formatting or raw-device write detected",
            "A skill must not format disks or write raw devices; remove the command."
        ),
        Rule(
            "security.fork-bomb", SkillScanSeverity.CRITICAL,
            Regex("""(?::\s*\(\s*\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:|[a-zA-Z_]\w*\s*\(\s*\)\s*\{\s*[a-zA-Z_]\w*\s*\|\s*[a-zA-Z_]\w*\s*&\s*\}\s*;\s*[a-zA-Z_]\w*)"""),
            "Fork bomb detected",
            "Remove the process bomb; it exhausts the host."
        ),
        Rule(
            "security.system-shutdown", SkillScanSeverity.CRITICAL,
            Regex("""(?:^|[;&|]\s*|\bsudo\s+)(?:shutdown|reboot|halt|poweroff)\b""", RegexOption.IGNORE_CASE),
            "System shutdown or reboot command detected",
            "Remove host-level power commands; the skill must not control the machine."
        ),
        Rule(
            "security.world-writable-root", SkillScanSeverity.CRITICAL,
            Regex("""\bchmod\s+(?:-R\s+)?777\s+/(?=\s|${'$'})""", RegexOption.IGNORE_CASE),
            "World-writable permissions on the filesystem root",
            "Remove the command; it disables the host's permission model."
        ),
        Rule(
            "security.private-key", SkillScanSeverity.CRITICAL,
            Regex("""-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP |ENCRYPTED )?PRIVATE KEY-----"""),
            "Private key block embedded in the package",
            "Remove the key and revoke it; skills must never carry private keys."
        ),
        Rule(
            "security.persistence", SkillScanSeverity.HIGH,
            Regex(
                """(?:(?:>>?|\btee\s+(?:-a\s+)?)\s*(?:~/|\$\{?HOME\}?/)?\.(?:bashrc|zshrc|bash_profile|profile)\b|\bauthorized_keys\b|\bcrontab\s+-[a-z]|\blaunchctl\s+(?:load|bootstrap|submit)\b|\bsystemctl\s+enable\b)""",
                RegexOption.IGNORE_CASE
            ),
            "Persistence mechanism detected (shell profile, authorized_keys, cron, launchd, systemd)",
            "Remove persistence setup; installing a skill must not modify the host's startup state."
        ),
        Rule(
            "security.exfil-endpoint", SkillScanSeverity.HIGH,
            Regex(
                """(?:curl|wget|Invoke-WebRequest|requests\.(?:post|put)|http\.client)\b[^\n]*(?:pastebin\.com|webhook\.site|transfer\.sh|0x0\.st|termbin\.com|pipedream\.net|ngrok\.io|discord\.com/api/webhooks)""",
                RegexOption.IGNORE_CASE
            ),
            "Uploads data to a paste, webhook or tunneling endpoint",
            "Remove the upload; cite documentation instead of sending user data to third parties."
        ),
        Rule(
            "security.download-execute", SkillScanSeverity.HIGH,
            Regex(
                """(?:(?:curl|wget)\b[^\n|]*\|\s*(?:sudo\s+)?(?:ba|z|k|fi)?sh\b|\b(?:ba|z|k)?sh\s+<\(\s*(?:curl|wget)\b|\biex\s*\(\s*new-object\s+net\.webclient|python[0-9.]*\s+-c\s+["'][^"'\n]*(?:exec|eval)\s*\([^"'\n]*(?:requests|urllib|urlopen))""",
                RegexOption.IGNORE_CASE
            ),
            "Downloads and executes remote content",
            "Replace this with explicit, reviewable steps the user can run themselves."
        ),
        Rule(
            "security.privilege-escalation", SkillScanSeverity.HIGH,
            Regex(
                """(?:\bsudo\b|\bsetuid\b|\bchmod\s+(?:[ugoa]*\+s|[0-7]*[4-7][0-7]{3})\b|\bchown\s+(?:root|0:0)\b)""",
                RegexOption.IGNORE_CASE
            ),
            "Privilege escalation detected",
            "Remove sudo/setuid/root-ownership steps; a skill runs without elevated rights."
        ),
        Rule(
            "security.credential-access", SkillScanSeverity.HIGH,
            Regex(
                """(?:(?:~/|\$\{?HOME\}?/)?\.(?:ssh/(?:id_rsa|id_ed25519|id_dsa|config)|aws/credentials|kube/config|docker/config\.json|netrc|npmrc|gnupg)\b|Library/(?:Cookies|Application Support/(?:Google/Chrome|Firefox|BraveSoftware))|\.mozilla/firefox|security\s+find-(?:generic|internet)-password)""",
                RegexOption.IGNORE_CASE
            ),
            "Reads credentials, keys or browser secrets",
            "Remove the credential access; a skill must never read secrets outside its directory."
        ),
        Rule(
            "security.aws-key", SkillScanSeverity.HIGH,
            Regex("""\bAKIA[0-9A-Z]{16}\b"""),
            "AWS access key detected", "Remove the key and rotate it.", redactMatch = true
        ),
        Rule(
            "security.anthropic-key", SkillScanSeverity.HIGH,
            Regex("""\bsk-ant-[A-Za-z0-9_-]{20,}\b"""),
            "Anthropic API key detected", "Remove the key and rotate it.", redactMatch = true
        ),
        Rule(
            "security.openai-key", SkillScanSeverity.HIGH,
            Regex("""\bsk-(?!ant-)(?:proj-)?[A-Za-z0-9_-]{20,}\b"""),
            "API key detected", "Remove the key and rotate it.", redactMatch = true
        ),
        Rule(
            "security.github-token", SkillScanSeverity.HIGH,
            Regex("""\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}\b|\bgithub_pat_[A-Za-z0-9_]{30,}\b"""),
            "GitHub token detected", "Remove the token and revoke it.", redactMatch = true
        ),
        Rule(
            "security.slack-token", SkillScanSeverity.HIGH,
            Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}\b"""),
            "Slack token detected", "Remove the token and revoke it.", redactMatch = true
        ),
        Rule(
            "security.google-key", SkillScanSeverity.HIGH,
            Regex("""\bAIza[0-9A-Za-z_-]{35}\b"""),
            "Google API key detected", "Remove the key and rotate it.", redactMatch = true
        ),
        Rule(
            "security.jwt", SkillScanSeverity.HIGH,
            Regex("""\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"""),
            "JWT detected", "Remove the token; skills must not carry live credentials.", redactMatch = true
        ),
        Rule(
            "security.database-url", SkillScanSeverity.HIGH,
            Regex("""\b(?:postgres(?:ql)?|mysql|mongodb(?:\+srv)?|redis)://[^:\s/@]+:[^@\s/]+@[^\s/]+"""),
            "Database URL with an embedded password detected",
            "Remove the connection string; never ship credentials in a skill.",
            redactMatch = true
        ),
        Rule(
            "security.prompt-injection", SkillScanSeverity.HIGH,
            Regex(
                """(?:\bignore\s+(?:all\s+)?(?:the\s+)?(?:previous|prior|above)\s+instructions?\b|\bdisregard\s+(?:all\s+)?(?:the\s+)?(?:previous|prior|above)\s+(?:instructions?|rules?)\b|\bdo\s+not\s+(?:tell|inform|notify)\s+the\s+user\b|</?system>|\[/?INST\])""",
                RegexOption.IGNORE_CASE
            ),
            "Prompt-injection marker detected",
            "Remove instructions that try to override the host agent or hide behavior from the user."
        ),
        Rule(
            "security.anti-forensics", SkillScanSeverity.HIGH,
            Regex(
                """(?:\bhistory\s+-c\b|\bunset\s+HISTFILE\b|\brm\b[^\n]*\.bash_history\b|\bshred\b)""",
                RegexOption.IGNORE_CASE
            ),
            "Anti-forensic cleanup detected",
            "Remove shell-history or log cleanup; it hides what the skill did."
        ),
        Rule(
            "security.eval-exec", SkillScanSeverity.MEDIUM,
            Regex("""\b(?:eval|exec|Function)\s*\(""", RegexOption.IGNORE_CASE),
            "Dynamic code evaluation detected",
            "Prefer explicit code; eval/exec hides what will run."
        )
    )

    private val CRITICAL_CONTENT_RULES = RULES.filter { it.severity == SkillScanSeverity.CRITICAL }
    private val EXECUTION_RULE_IDS = setOf(
        "security.download-execute", "security.privilege-escalation", "security.system-shutdown"
    )

    private fun inspectSecurity(file: ScanFile, findings: Findings) {
        if (file.binary) return
        val text = file.text ?: return

        if (isCredentialFile(file.path)) {
            findings.add(
                sec(
                    "security.credential-file", SkillScanSeverity.HIGH, file.path, null,
                    "Package contains a credential-like file: ${file.path.substringAfterLast('/')}",
                    "Remove secrets from the package; reference environment variables instead."
                )
            )
        }

        val extension = file.path.substringAfterLast('.', "").lowercase()
        if (extension in SCRIPT_EXTENSIONS) {
            findings.add(
                sec(
                    "security.script-file", SkillScanSeverity.MEDIUM, file.path, null,
                    "Package contains a script; installation never executes it",
                    "Keep it only if needed, and state in SKILL.md that the user must run it."
                )
            )
        }

        var credentialLine: Int? = null
        var networkLine: Int? = null
        var urlLine: Int? = null
        var sendLine: Int? = null
        var injectionLine: Int? = null
        var executionLine: Int? = null
        var downloadLine: Int? = null
        var persistenceLine: Int? = null

        file.lines.forEachIndexed { index, line ->
            val lineNumber = index + 1

            if (line.length > MAX_LINE_LENGTH) {
                findings.add(
                    sec(
                        "security.obfuscated-line", SkillScanSeverity.HIGH, file.path, lineNumber,
                        if (line.length > MAX_LINE_SCAN_CHARS) {
                            "Line is ${line.length} characters long; only the first $MAX_LINE_SCAN_CHARS can be inspected"
                        } else {
                            "Line is ${line.length} characters long"
                        },
                        "Split or shorten the line; very long lines commonly hide encoded payloads."
                    )
                )
            }

            // A dangerous command must not hide behind 9k characters of padding: scan every chunk
            // (bounded per line) instead of only the head.
            for (probe in chunksOf(line)) {
                for (rule in RULES) {
                    val match = rule.pattern.find(probe) ?: continue
                    if (rule.redactMatch && isPlaceholder(match.value)) continue
                    val message = if (rule.redactMatch) "${rule.message}: ${redact(match.value)}" else rule.message
                    findings.add(
                        sec(
                            rule.id, rule.severity, file.path, lineNumber, message, rule.suggestion,
                            rule.blocking, redacted = rule.redactMatch
                        )
                    )
                    when (rule.id) {
                        "security.credential-access" -> credentialLine = credentialLine ?: lineNumber
                        "security.download-execute" -> {
                            executionLine = executionLine ?: lineNumber
                            downloadLine = downloadLine ?: lineNumber
                        }
                        in EXECUTION_RULE_IDS -> executionLine = executionLine ?: lineNumber
                        "security.persistence" -> persistenceLine = persistenceLine ?: lineNumber
                        "security.prompt-injection" -> injectionLine = injectionLine ?: lineNumber
                    }
                }

                BASE64_BLOB.findAll(probe).take(3).forEach { blob ->
                    findings.add(
                        sec(
                            "security.obfuscated-blob", SkillScanSeverity.HIGH, file.path, lineNumber,
                            "Long base64-looking blob (${blob.value.length} characters)",
                            "Remove encoded payloads; document the behavior in plain text."
                        )
                    )
                    decoded(blob.value)?.let { decodedText ->
                        val hit = CRITICAL_CONTENT_RULES.firstOrNull { it.pattern.containsMatchIn(decodedText) }
                        if (hit != null) {
                            findings.add(
                                sec(
                                    "security.encoded-dangerous", SkillScanSeverity.CRITICAL, file.path, lineNumber,
                                    "Encoded content decodes to a dangerous pattern (${hit.id})",
                                    "Remove the encoded payload; the package is refused."
                                )
                            )
                        }
                    }
                }
                if (HEX_BLOB.containsMatchIn(probe)) {
                    findings.add(
                        sec(
                            "security.obfuscated-blob", SkillScanSeverity.HIGH, file.path, lineNumber,
                            "Long hexadecimal blob", "Remove encoded payloads; document the behavior in plain text."
                        )
                    )
                }

                val networkCommand = NETWORK_COMMAND.containsMatchIn(probe)
                if (networkCommand) {
                    networkLine = networkLine ?: lineNumber
                    sendLine = sendLine ?: lineNumber
                } else if (URL.containsMatchIn(probe)) {
                    // A citation is not a network call; keep it distinct from shell/HTTP usage.
                    urlLine = urlLine ?: lineNumber
                }
                if (DEPENDENCY_INSTALL.containsMatchIn(probe) && !PINNED_VERSION.containsMatchIn(probe)) {
                    findings.add(
                        sec(
                            "security.unpinned-dependency", SkillScanSeverity.MEDIUM, file.path, lineNumber,
                            "Installs a dependency without a pinned version",
                            "Pin the version, or let the user install dependencies themselves."
                        )
                    )
                }
            }
        }

        if (networkLine != null) {
            val domains = URL.findAll(text).map { it.groupValues[1] }.distinct().take(10).toList()
            val suffix = if (domains.isEmpty()) "" else " (domains: ${domains.joinToString(", ")})"
            findings.add(
                sec(
                    "security.network-access", SkillScanSeverity.MEDIUM, file.path, networkLine,
                    "Uses network commands$suffix",
                    "Confirm the endpoints are expected; installation itself performs no network calls."
                )
            )
        } else if (urlLine != null) {
            val domains = URL.findAll(text).map { it.groupValues[1] }.distinct().take(10).toList()
            val suffix = if (domains.isEmpty()) "" else " (domains: ${domains.joinToString(", ")})"
            findings.add(
                sec(
                    "security.network-access", SkillScanSeverity.LOW, file.path, urlLine,
                    "Mentions URLs$suffix",
                    "Citations are fine; confirm the skill does not tell the agent to fetch unexpected endpoints."
                )
            )
        }

        HIDDEN_UNICODE.find(text)?.let { match ->
            val line = text.take(match.range.first).count { it == '\n' } + 1
            findings.add(
                sec(
                    "security.hidden-unicode", SkillScanSeverity.HIGH, file.path, line,
                    "Hidden or bidirectional Unicode characters detected",
                    "Remove zero-width/bidi characters; they can hide instructions from review."
                )
            )
        }

        HTML_COMMENT.findAll(text).forEach { comment ->
            if (HTML_COMMENT_INSTRUCTION.containsMatchIn(comment.value)) {
                val line = text.take(comment.range.first).count { it == '\n' } + 1
                findings.add(
                    sec(
                        "security.html-comment-instruction", SkillScanSeverity.MEDIUM, file.path, line,
                        "HTML comment contains instructions rather than documentation",
                        "Move explanations into visible text or delete the comment."
                    )
                )
            }
        }

        if (credentialLine != null && sendLine != null) {
            findings.add(
                sec(
                    "security.credential-exfiltration", SkillScanSeverity.CRITICAL, file.path, credentialLine,
                    "Reads credentials near network-sending code",
                    "Remove the credential access and the transfer; the package is refused."
                )
            )
        }
        if (injectionLine != null && executionLine != null) {
            findings.add(
                sec(
                    "security.prompt-injection-danger", SkillScanSeverity.CRITICAL, file.path, injectionLine,
                    "Prompt injection combined with an executable action",
                    "Remove the hidden instruction and the action; the package is refused."
                )
            )
        }
        if (persistenceLine != null && downloadLine != null) {
            findings.add(
                sec(
                    "security.persistence-download", SkillScanSeverity.CRITICAL, file.path, persistenceLine,
                    "Persistence setup combined with downloading and executing code",
                    "Remove both; the package is refused."
                )
            )
        }
    }

    /**
     * Overlapping windows so a pattern that straddles a chunk boundary is still seen. The budget
     * covers every line the panel can submit; a longer line is flagged blocking by the caller
     * because its tail cannot be inspected.
     */
    private fun chunksOf(line: String): List<String> {
        if (line.length <= REGEX_SCAN_LIMIT) return listOf(line)
        val covered = minOf(line.length, MAX_LINE_SCAN_CHARS)
        val chunks = ArrayList<String>()
        var start = 0
        while (start < covered) {
            val end = minOf(start + REGEX_SCAN_LIMIT + CHUNK_OVERLAP, line.length)
            // Carry the preceding character so a `^`-anchored rule cannot match mid-line just
            // because the window happens to start there.
            val from = if (start == 0) 0 else start - 1
            chunks.add(line.substring(from, end))
            start += REGEX_SCAN_LIMIT
        }
        return chunks
    }

    private val HTML_COMMENT_INSTRUCTION = Regex(
        """\b(?:ignore|instruction|system prompt|execute|run this|do not tell|secret|token|password)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun isCredentialFile(path: String): Boolean {
        val base = path.substringAfterLast('/')
        if (base in ENV_ALLOWLIST) return false
        if (base == ".env" || base.startsWith(".env.")) return true
        if (base in CREDENTIAL_FILE_NAMES) return true
        return base.endsWith(".pem") || base.endsWith(".key") || base.endsWith(".p12") || base.endsWith(".pfx")
    }

    private fun isPlaceholder(value: String): Boolean {
        val lower = value.lowercase()
        return listOf(
            "your", "xxx", "example", "placeholder", "redacted", "changeme", "dummy", "sample", "<", ">", "***"
        ).any { it in lower }
    }

    private fun redact(value: String): String =
        if (value.length <= 6) "***" else "${value.take(4)}…${value.takeLast(2)}"

    /** Decode a base64 blob only when it looks like printable text after decoding. */
    private fun decoded(blob: String): String? = try {
        val bytes = Base64.getMimeDecoder().decode(blob)
        val text = String(bytes, Charsets.UTF_8)
        val printable = text.count { it == '\n' || it == '\r' || it == '\t' || it.code in 0x20..0x7E }
        if (text.isNotEmpty() && printable * 100 / text.length >= 80) text else null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Declared list/scalar values as trimmed, deduplicated strings: a repeated tag is one tag. */
    private fun stringsOf(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.filterIsInstance<String>().map { it.trim() }.filter { it.isNotEmpty() }
        is String -> listOf(raw.trim()).filter { it.isNotEmpty() }
        else -> emptyList()
    }.distinct()

    // ── Findings ───────────────────────────────────────────────────────────────

    private class Findings {
        private val merged = LinkedHashMap<String, SkillScanFinding>()

        fun add(finding: SkillScanFinding) {
            val key = if (finding.id == "spec.broken-reference") {
                "${finding.id}|${finding.file}|${finding.message}"
            } else {
                "${finding.id}|${finding.file}"
            }
            // One occurrence per rule and file is enough for the report, but a blocking verdict
            // must never be masked by an earlier non-blocking occurrence of the same rule.
            val existing = merged[key]
            if (existing == null || (!existing.blocking && finding.blocking)) {
                merged[key] = finding
            }
        }

        fun all(): List<SkillScanFinding> = merged.values.toList()
    }

    private fun spec(
        id: String,
        severity: SkillScanSeverity,
        file: String?,
        line: Int?,
        message: String,
        suggestion: String,
        blocking: Boolean = false
    ): SkillScanFinding = SkillScanFinding(
        id = id,
        category = SkillScanCategory.SPEC,
        severity = severity,
        file = file,
        line = line,
        message = message,
        suggestion = suggestion,
        blocking = blocking
    )

    private fun specPath(id: String, file: String, message: String, suggestion: String): SkillScanFinding =
        spec(id, SkillScanSeverity.HIGH, file, null, message, suggestion, blocking = true)

    private fun quality(
        id: String,
        file: String?,
        line: Int?,
        message: String,
        suggestion: String
    ): SkillScanFinding = spec(id, SkillScanSeverity.INFO, file, line, message, suggestion)
        .copy(category = SkillScanCategory.QUALITY)

    private fun sec(
        id: String,
        severity: SkillScanSeverity,
        file: String?,
        line: Int?,
        message: String,
        suggestion: String,
        blocking: Boolean = severity == SkillScanSeverity.CRITICAL,
        redacted: Boolean = false
    ): SkillScanFinding = SkillScanFinding(
        id = id,
        category = SkillScanCategory.SECURITY,
        severity = severity,
        file = file,
        line = line,
        message = message,
        suggestion = suggestion,
        blocking = blocking,
        redacted = redacted
    )
}
