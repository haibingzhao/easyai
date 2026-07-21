package com.easy.easyai.tools.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor

/**
 * Converts HTML content to plain text or Markdown.
 *
 * Ported from OpenCode's `extractTextFromHTML` (htmlparser2) and
 * `convertHTMLToMarkdown` (TurndownService) with the same tag removal set.
 */
internal object HtmlConverter {

    private val REMOVE_TAGS = setOf("script", "style", "meta", "link", "noscript", "iframe", "object", "embed")

    /**
     * Strips all HTML tags and returns visible text only.
     * Equivalent to OpenCode's `extractTextFromHTML`.
     */
    fun toText(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select(REMOVE_TAGS.joinToString(",")).remove()
        return doc.text().trim()
    }

    /**
     * Converts HTML to Markdown using Jsoup DOM traversal.
     * Equivalent to OpenCode's `convertHTMLToMarkdown` (TurndownService with atx headings,
     * fenced code blocks, `-` bullets, `*` emphasis, `---` hr).
     */
    fun toMarkdown(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select(REMOVE_TAGS.joinToString(",")).remove()

        val body = doc.body() ?: return doc.text().trim()
        val sb = StringBuilder()
        convertNode(body, sb, ConvertContext())
        return postProcess(sb.toString())
    }

    // ---- Internal DOM traversal ----

    private data class ConvertContext(
        var listDepth: Int = 0,
        var orderedCounters: MutableList<Int> = mutableListOf(),
        var insidePre: Boolean = false,
        var insideCode: Boolean = false,
    )

    private fun convertNode(node: Node, sb: StringBuilder, ctx: ConvertContext) {
        when (node) {
            is TextNode -> {
                val text = if (ctx.insidePre) node.wholeText else node.text()
                if (text.isNotEmpty() || ctx.insidePre) sb.append(text)
            }
            is Element -> convertElement(node, sb, ctx)
            else -> { /* skip comments, etc. */ }
        }
    }

    private fun convertChildren(node: Node, sb: StringBuilder, ctx: ConvertContext) {
        for (child in node.childNodes()) {
            convertNode(child, sb, ctx)
        }
    }

    private fun convertElement(el: Element, sb: StringBuilder, ctx: ConvertContext) {
        when (el.tagName().lowercase()) {
            // Headings
            "h1" -> { sb.append("\n\n# "); convertChildren(el, sb, ctx); sb.append("\n") }
            "h2" -> { sb.append("\n\n## "); convertChildren(el, sb, ctx); sb.append("\n") }
            "h3" -> { sb.append("\n\n### "); convertChildren(el, sb, ctx); sb.append("\n") }
            "h4" -> { sb.append("\n\n#### "); convertChildren(el, sb, ctx); sb.append("\n") }
            "h5" -> { sb.append("\n\n##### "); convertChildren(el, sb, ctx); sb.append("\n") }
            "h6" -> { sb.append("\n\n###### "); convertChildren(el, sb, ctx); sb.append("\n") }

            // Paragraphs & line breaks
            "p" -> { sb.append("\n\n"); convertChildren(el, sb, ctx); sb.append("\n") }
            "br" -> sb.append("\n")
            "hr" -> sb.append("\n\n---\n\n")

            // Bold & italic
            "strong", "b" -> { sb.append("**"); convertChildren(el, sb, ctx); sb.append("**") }
            "em", "i" -> { sb.append("*"); convertChildren(el, sb, ctx); sb.append("*") }

            // Inline code & code blocks
            "code" -> {
                if (ctx.insidePre) {
                    // Inside <pre>, code is already handled by pre
                    convertChildren(el, sb, ctx)
                } else {
                    ctx.insideCode = true
                    sb.append("`")
                    convertChildren(el, sb, ctx)
                    sb.append("`")
                    ctx.insideCode = false
                }
            }
            "pre" -> {
                ctx.insidePre = true
                val lang = el.selectFirst("code")?.classNames()
                    ?.firstOrNull { it.startsWith("language-") || it.startsWith("lang-") }
                    ?.let { it.removePrefix("language-").removePrefix("lang-") }
                    ?: ""
                sb.append("\n\n```").append(lang).append("\n")
                convertChildren(el, sb, ctx)
                sb.append("\n```\n\n")
                ctx.insidePre = false
            }

            // Links
            "a" -> {
                val href = el.attr("href")
                val text = el.text()
                if (href.isNotEmpty() && text.isNotEmpty()) {
                    sb.append("[").append(text).append("](").append(href).append(")")
                } else {
                    convertChildren(el, sb, ctx)
                }
            }

            // Images
            "img" -> {
                val src = el.attr("src")
                val alt = el.attr("alt").ifEmpty { "image" }
                if (src.isNotEmpty()) {
                    sb.append("![").append(alt).append("](").append(src).append(")")
                }
            }

            // Unordered lists
            "ul" -> {
                ctx.listDepth++
                ctx.orderedCounters.add(0) // 0 = unordered
                sb.append("\n")
                convertChildren(el, sb, ctx)
                ctx.orderedCounters.removeLast()
                ctx.listDepth--
                if (ctx.listDepth == 0) sb.append("\n")
            }

            // Ordered lists
            "ol" -> {
                ctx.listDepth++
                ctx.orderedCounters.add(1)
                sb.append("\n")
                convertChildren(el, sb, ctx)
                ctx.orderedCounters.removeLast()
                ctx.listDepth--
                if (ctx.listDepth == 0) sb.append("\n")
            }

            "li" -> {
                val indent = "  ".repeat((ctx.listDepth - 1).coerceAtLeast(0))
                val counter = ctx.orderedCounters.lastOrNull()
                if (counter != null && counter > 0) {
                    // Ordered
                    sb.append("\n").append(indent).append(counter).append(". ")
                    ctx.orderedCounters[ctx.orderedCounters.size - 1] = counter + 1
                } else {
                    // Unordered
                    sb.append("\n").append(indent).append("- ")
                }
                convertChildren(el, sb, ctx)
            }

            // Blockquote
            "blockquote" -> {
                sb.append("\n")
                val inner = StringBuilder()
                convertChildren(el, inner, ctx)
                inner.toString().lines().forEach { line ->
                    sb.append("> ").append(line).append("\n")
                }
                sb.append("\n")
            }

            // Tables
            "table" -> {
                sb.append("\n\n")
                convertTable(el, sb, ctx)
                sb.append("\n")
            }

            // Span/div/section/article — just recurse into children
            "span", "div", "section", "article", "main", "header", "footer",
            "nav", "aside", "figure", "figcaption", "details", "summary",
            "mark", "small", "sub", "sup", "abbr", "cite", "dfn", "kbd",
            "samp", "var", "del", "ins", "s", "u" -> {
                convertChildren(el, sb, ctx)
            }

            // Skip these entirely (redundant with REMOVE_TAGS, but safety net)
            "script", "style", "meta", "link", "noscript" -> { /* skip */ }

            // Default: recurse into children
            else -> convertChildren(el, sb, ctx)
        }
    }

    private fun convertTable(table: Element, sb: StringBuilder, ctx: ConvertContext) {
        val rows = table.select("tr")
        if (rows.isEmpty()) return

        // Collect all cell texts
        val matrix = rows.map { row ->
            row.select("th, td").map { cell ->
                cell.text().replace("|", "\\|").replace("\n", " ")
            }
        }
        val colCount = matrix.maxOfOrNull { it.size } ?: return
        if (colCount == 0) return

        // Pad rows
        val padded = matrix.map { row ->
            row + List(colCount - row.size) { "" }
        }

        // Calculate column widths (min 3)
        val widths = (0 until colCount).map { col ->
            padded.maxOf { it[col].length }.coerceAtLeast(3)
        }

        // Header
        val headerRow = padded.firstOrNull() ?: return
        sb.append("| ")
        headerRow.forEachIndexed { i, cell ->
            sb.append(cell.padEnd(widths[i])).append(" | ")
        }
        sb.append("\n")

        // Separator
        sb.append("| ")
        widths.forEach { w -> sb.append("-".repeat(w)).append(" | ") }
        sb.append("\n")

        // Body rows
        for (row in padded.drop(1)) {
            sb.append("| ")
            row.forEachIndexed { i, cell ->
                sb.append(cell.padEnd(widths[i])).append(" | ")
            }
            sb.append("\n")
        }
    }

    /**
     * Cleans up the generated Markdown: collapse excessive blank lines, trim edges.
     */
    private fun postProcess(md: String): String {
        return md
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
