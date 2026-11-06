package com.amazon.connect.touchpoint.chat.ui

/**
 * Markdown, as Touchpoint web renders an assistant's or agent's message: GitHub-flavoured markdown
 * (web uses `marked`), sanitised as web's DOMPurify leaves it. In-house because the SDK takes no
 * third-party library beyond the platform's; pure Kotlin, so it is tested on the JVM.
 *
 * What it reads: paragraphs (a single line break is a space; two trailing spaces or a backslash
 * break the line), ATX and setext headings, fenced and indented code, block quotes, bullet and
 * ordered lists, rules, GFM tables; bold, italic, bold-italic, strikethrough, code spans, links,
 * images (as their alt text, linked), autolinks and bare `http(s)://` / `www.` links, backslash
 * escapes, entities. HTML as the sanitiser leaves it: `<b>`, `<strong>`, `<i>`, `<em>`, `<br>` kept,
 * `<script>` and `<style>` dropped with their content, any other tag dropped and its text kept.
 *
 * A link goes only to http, https, mailto or tel — never `intent:`, `javascript:` or a file; any other
 * target is shown as its text. Input from the contact is untrusted: parsing never throws, is
 * bounded in depth and length, and runs in linear-ish time on hostile input.
 */
internal object Markdown {
    sealed interface Block {
        data class Paragraph(val inlines: List<Inline>) : Block

        data class Heading(val level: Int, val inlines: List<Inline>) : Block

        data class Code(val text: String) : Block

        data class Quote(val blocks: List<Block>) : Block

        data class Items(val ordered: Boolean, val start: Int, val items: List<List<Block>>) : Block

        data object Rule : Block

        data class Table(val header: List<List<Inline>>, val align: List<Align>, val rows: List<List<List<Inline>>>) : Block
    }

    enum class Align { START, CENTER, END }

    sealed interface Inline {
        data class Text(val text: String) : Inline

        data class Strong(val children: List<Inline>) : Inline

        data class Emphasis(val children: List<Inline>) : Inline

        data class Strike(val children: List<Inline>) : Inline

        data class Code(val text: String) : Inline

        /** [url] is `null` when the target is not one a message may open: the text is shown, unlinked. */
        data class Link(val url: String?, val children: List<Inline>) : Inline

        data object Break : Inline
    }

    /** The blocks of [source]. Never throws: anything it cannot read stays text. */
    fun parse(source: String): List<Block> =
        try {
            if (source.length > MAX_SOURCE) {
                listOf(Block.Paragraph(listOf(Inline.Text(source))))
            } else {
                Blocks(normalise(source).split('\n'), 0).parse()
            }
        } catch (_: RuntimeException) {
            // A defect here must not cost the host its app: show the message as written.
            listOf(Block.Paragraph(listOf(Inline.Text(source))))
        }

    /** What TalkBack and the announcer read: the text, without the markup. */
    fun plainText(source: String): String = plainText(parse(source))

    fun plainText(blocks: List<Block>): String = blocks.joinToString("\n") { plainText(it) }.trim()

    private fun plainText(block: Block): String =
        when (block) {
            is Block.Paragraph -> plainInline(block.inlines)
            is Block.Heading -> plainInline(block.inlines)
            is Block.Code -> block.text
            is Block.Quote -> plainText(block.blocks)
            // Numbers are content, as a screen reader reads web's; a bullet is not.
            is Block.Items -> block.items.withIndex().joinToString("\n") { (n, item) -> (if (block.ordered) "${block.start + n}. " else "") + plainText(item) }
            Block.Rule -> ""
            is Block.Table -> (listOf(block.header) + block.rows).joinToString("\n") { row -> row.joinToString(", ") { plainInline(it) } }
        }

    fun plainInline(inlines: List<Inline>): String =
        buildString {
            for (i in inlines) {
                when (i) {
                    is Inline.Text -> append(i.text)
                    is Inline.Code -> append(i.text)
                    is Inline.Strong -> append(plainInline(i.children))
                    is Inline.Emphasis -> append(plainInline(i.children))
                    is Inline.Strike -> append(plainInline(i.children))
                    is Inline.Link -> append(plainInline(i.children))
                    Inline.Break -> append('\n')
                }
            }
        }

    /** A message's links, in order, as (the words they are shown as, where they go): TalkBack's actions. */
    fun links(blocks: List<Block>): List<Pair<String, String>> {
        val found = ArrayList<Pair<String, String>>()

        fun inlines(list: List<Inline>) {
            for (i in list) {
                when (i) {
                    is Inline.Link -> i.url?.let { found += plainInline(i.children).ifBlank { it } to it }
                    is Inline.Strong -> inlines(i.children)
                    is Inline.Emphasis -> inlines(i.children)
                    is Inline.Strike -> inlines(i.children)
                    is Inline.Text, is Inline.Code, Inline.Break -> Unit
                }
            }
        }

        fun walk(list: List<Block>) {
            for (b in list) {
                when (b) {
                    is Block.Paragraph -> inlines(b.inlines)
                    is Block.Heading -> inlines(b.inlines)
                    is Block.Quote -> walk(b.blocks)
                    is Block.Items -> b.items.forEach { walk(it) }
                    is Block.Table -> (listOf(b.header) + b.rows).forEach { row -> row.forEach { inlines(it) } }
                    is Block.Code, Block.Rule -> Unit
                }
            }
        }
        walk(blocks)
        return found
    }

    /**
     * The target a link may open, or `null`: http, https, mailto and tel only — the schemes a message
     * has any business sending a customer to — and `www.` as web's GFM reads it, as http. The scheme
     * comes back lower-case: Android matches an intent's scheme exactly, so `HTTPS:` would reach no
     * browser.
     */
    fun safeUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty() || url.length > MAX_URL || url.any { it.isWhitespace() || it.isISOControl() }) return null
        if (url.startsWith("www.", ignoreCase = true)) return "http://$url"
        val colon = url.indexOf(':')
        if (colon <= 0) return null
        val scheme = url.substring(0, colon).lowercase()
        if (scheme !in SCHEMES || url.length == colon + 1) return null
        // A web link needs a host: `https:` alone, or `https:x`, opens nothing sensible.
        if ((scheme == "http" || scheme == "https") && (!url.substring(colon + 1).startsWith("//") || url.length <= colon + 3 || url[colon + 3] in "/?#")) return null
        return scheme + url.substring(colon)
    }

    /** `&amp;`, `&#233;`, `&#xE9;` and the common named entities, decoded; anything else left as written. */
    fun decodeEntities(raw: String): String {
        if (!raw.contains('&')) return raw
        val out = StringBuilder()
        var k = 0
        while (k < raw.length) {
            val decoded = if (raw[k] == '&') entityAt(raw, k) else null
            if (decoded == null) {
                out.append(raw[k])
                k++
            } else {
                out.append(decoded.first)
                k = decoded.second
            }
        }
        return out.toString()
    }

    /** The entity starting at [k] and where the text after it starts, or `null`. */
    private fun entityAt(
        s: String,
        k: Int,
    ): Pair<String, Int>? {
        val end = s.indexOf(';', k)
        if (end < 0 || end - k > MAX_ENTITY) return null
        val name = s.substring(k + 1, end)
        val decoded =
            when {
                name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(HEX)?.let(::codePoint)
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
                else -> ENTITIES[name]
            } ?: return null
        return decoded to end + 1
    }

    private fun codePoint(cp: Int): String? = if (cp in 1..Character.MAX_CODE_POINT && cp !in SURROGATES) String(Character.toChars(cp)) else null

    private fun normalise(source: String): String = source.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")

    // Blocks

    private class Blocks(private val lines: List<String>, private val depth: Int) {
        private var i = 0

        fun parse(): List<Block> {
            val out = ArrayList<Block>()
            while (i < lines.size) {
                val line = lines[i]
                when {
                    line.isBlank() -> i++
                    depth >= MAX_DEPTH -> out += paragraph()
                    fence(line) != null -> out += code()
                    atx(line) != null -> out += heading()
                    isRule(line) -> {
                        out += Block.Rule
                        i++
                    }
                    quoted(line) != null -> out += quote()
                    marker(line) != null -> out += list()
                    tableAt(i) -> out += table()
                    indent(line) >= INDENTED_CODE -> out += indentedCode()
                    else -> out += paragraph()
                }
            }
            return out
        }

        /** A line that starts a block of its own, and so ends a paragraph. */
        private fun interrupts(line: String): Boolean {
            if (line.isBlank() || fence(line) != null || atx(line) != null || isRule(line) || quoted(line) != null) return true
            val m = marker(line) ?: return false
            // As CommonMark: an ordered list interrupts a paragraph only from 1, and no empty item does.
            return m.content.isNotBlank() && (!m.ordered || m.number == 1)
        }

        private fun paragraph(): Block {
            val text = ArrayList<String>()
            text += lines[i++].trimStart()
            while (i < lines.size) {
                val line = lines[i]
                // Setext: a line of = or - under a paragraph makes it a heading.
                if (indent(line) < INDENTED_CODE) {
                    val t = line.trim()
                    if (t.isNotEmpty() && t.all { it == '=' }) {
                        i++
                        return Block.Heading(1, Inlines(text.joinToString("\n"), 0).parse())
                    }
                    if (t.isNotEmpty() && t.all { it == '-' }) {
                        i++
                        return Block.Heading(2, Inlines(text.joinToString("\n"), 0).parse())
                    }
                }
                if (interrupts(line) || tableAt(i)) break
                text += line.trimStart()
                i++
            }
            return Block.Paragraph(Inlines(text.joinToString("\n"), 0).parse())
        }

        private fun heading(): Block {
            val (level, content) = atx(lines[i++]) ?: return Block.Paragraph(emptyList())
            return Block.Heading(level, Inlines(content, 0).parse())
        }

        private fun code(): Block {
            val open = fence(lines[i]) ?: return paragraph()
            val strip = indent(lines[i])
            i++
            val body = ArrayList<String>()
            while (i < lines.size) {
                val line = lines[i]
                val close = fence(line)
                if (close != null && close.first == open.first && close.second >= open.second && close.third.isBlank()) {
                    i++
                    break
                }
                body += line.drop(minOf(strip, indent(line)))
                i++
            }
            return Block.Code(body.joinToString("\n"))
        }

        private fun indentedCode(): Block {
            val body = ArrayList<String>()
            while (i < lines.size) {
                val line = lines[i]
                if (indent(line) >= INDENTED_CODE) {
                    body += line.drop(INDENTED_CODE)
                    i++
                } else if (line.isBlank()) {
                    // Blank lines belong to the code only if indented code follows: look ahead once.
                    var j = i
                    while (j < lines.size && lines[j].isBlank()) j++
                    if (j >= lines.size || indent(lines[j]) < INDENTED_CODE) break
                    repeat(j - i) { body += "" }
                    i = j
                } else {
                    break
                }
            }
            return Block.Code(body.joinToString("\n").trimEnd('\n'))
        }

        private fun quote(): Block {
            val inner = ArrayList<String>()
            while (i < lines.size) {
                val line = lines[i]
                val q = quoted(line)
                if (q != null) {
                    inner += q
                } else if (line.isNotBlank() && inner.isNotEmpty() && inner.last().isNotBlank() && !interrupts(line)) {
                    inner += line // a lazy continuation of the quoted paragraph
                } else {
                    break
                }
                i++
            }
            return Block.Quote(Blocks(inner, depth + 1).parse())
        }

        private fun list(): Block {
            val first = marker(lines[i]) ?: return paragraph()
            val items = ArrayList<List<Block>>()
            while (i < lines.size) {
                val m = marker(lines[i])
                if (m == null || m.ordered != first.ordered || m.delimiter != first.delimiter || m.indent > first.indent + MAX_MARKER_INDENT) break
                val item = ArrayList<String>()
                item += m.content
                i++
                var blankBefore = false
                while (i < lines.size) {
                    val line = lines[i]
                    if (line.isBlank()) {
                        blankBefore = true
                        item += ""
                        i++
                        continue
                    }
                    if (indent(line) >= m.width) {
                        item += line.drop(m.width)
                    } else if (!blankBefore && marker(line) == null && !interrupts(line)) {
                        item += line.trimStart() // lazy continuation of the item's paragraph
                    } else {
                        break
                    }
                    blankBefore = false
                    i++
                }
                items += Blocks(item, depth + 1).parse()
                // A blank line, then something not a sibling item, ends the list.
                if (i < lines.size && blankBefore && marker(lines[i]) == null) break
            }
            return Block.Items(first.ordered, first.number, items)
        }

        /** A table — bounded: past [MAX_COLUMNS] it is text, so a hostile message cannot fill memory with cells. */
        private fun tableAt(at: Int): Boolean {
            if (at + 1 >= lines.size || !lines[at].contains('|')) return false
            val head = cells(lines[at])
            if (head.isEmpty() || head.size > MAX_COLUMNS) return false
            val delimiter = delimiterRow(lines[at + 1]) ?: return false
            return head.size == delimiter.size
        }

        private fun table(): Block {
            val header = cells(lines[i]).map { Inlines(it, 0).parse() }
            val align = delimiterRow(lines[i + 1]) ?: emptyList()
            i += 2
            val rows = ArrayList<List<List<Inline>>>()
            // Bounded too: past [MAX_CELLS], the rest is read as paragraphs.
            while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|') && !interrupts(lines[i]) && (rows.size + 2) * header.size <= MAX_CELLS) {
                val row = cells(lines[i])
                // GFM: a short row is padded, a long one cut, to the header's width.
                rows += List(header.size) { c -> Inlines(row.getOrElse(c) { "" }, 0).parse() }
                i++
            }
            return Block.Table(header, align, rows)
        }
    }

    private data class Marker(val ordered: Boolean, val number: Int, val delimiter: Char, val indent: Int, val width: Int, val content: String)

    /** A list item's marker: `-`, `+`, `*`, or 1–9 digits then `.` or `)`; then the content and how far it is indented. */
    private fun marker(line: String): Marker? {
        val lead = indent(line)
        if (lead > MAX_MARKER_INDENT) return null
        var p = lead
        val ordered: Boolean
        val number: Int
        val delimiter: Char
        if (p < line.length && line[p] in "-+*") {
            ordered = false
            number = 1
            delimiter = line[p]
            p++
        } else {
            val digits = line.drop(p).takeWhile { it.isDigit() }
            if (digits.isEmpty() || digits.length > MAX_ORDINAL_DIGITS) return null
            p += digits.length
            if (p >= line.length || line[p] !in ".)") return null
            ordered = true
            number = digits.toInt()
            delimiter = line[p]
            p++
        }
        if (p < line.length && line[p] != ' ') return null
        val spaces = line.drop(p).takeWhile { it == ' ' }.length
        val content = line.drop(p + spaces)
        // Five or more spaces: indented code inside the item, which keeps one space as the gap.
        val gap = if (content.isEmpty()) 1 else if (spaces in 1..MAX_ITEM_GAP) spaces else 1
        return Marker(ordered, number, delimiter, lead, p + gap, if (spaces > MAX_ITEM_GAP) line.drop(p + 1) else content)
    }

    /** A fence: its character, its length, and the info string after it. */
    private fun fence(line: String): Triple<Char, Int, String>? {
        val lead = indent(line)
        if (lead > MAX_MARKER_INDENT || lead >= line.length) return null
        val c = line[lead]
        if (c != '`' && c != '~') return null
        val run = line.drop(lead).takeWhile { it == c }.length
        if (run < FENCE) return null
        val info = line.drop(lead + run)
        if (c == '`' && info.contains('`')) return null
        return Triple(c, run, info)
    }

    private fun atx(line: String): Pair<Int, String>? {
        val lead = indent(line)
        if (lead > MAX_MARKER_INDENT) return null
        val hashes = line.drop(lead).takeWhile { it == '#' }.length
        if (hashes !in 1..MAX_HEADING) return null
        val rest = line.drop(lead + hashes)
        if (rest.isNotEmpty() && rest[0] != ' ') return null
        var content = rest.trim()
        // A closing run of #, if it stands apart, is not content.
        val closing = content.takeLastWhile { it == '#' }
        if (closing.isNotEmpty() && (closing.length == content.length || content[content.length - closing.length - 1] == ' ')) {
            content = content.dropLast(closing.length).trimEnd()
        }
        return hashes to content
    }

    private fun isRule(line: String): Boolean {
        if (indent(line) > MAX_MARKER_INDENT) return false
        val t = line.filter { it != ' ' }
        return t.length >= RULE && t[0] in "-*_" && t.all { it == t[0] }
    }

    private fun quoted(line: String): String? {
        val lead = indent(line)
        if (lead > MAX_MARKER_INDENT || lead >= line.length || line[lead] != '>') return null
        val rest = line.drop(lead + 1)
        return if (rest.startsWith(' ')) rest.drop(1) else rest
    }

    private fun cells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith('|')) t = t.drop(1)
        // A closing pipe is a delimiter unless an odd run of backslashes escapes it, as marked counts them.
        if (t.endsWith('|') && t.dropLast(1).takeLastWhile { it == '\\' }.length % 2 == 0) t = t.dropLast(1)
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var k = 0
        while (k < t.length) {
            val c = t[k]
            if (c == '\\' && k + 1 < t.length && t[k + 1] == '|') {
                cell.append('|')
                k += 2
                continue
            }
            // An escaped backslash is kept whole, so the pipe after it is a real delimiter.
            if (c == '\\' && k + 1 < t.length && t[k + 1] == '\\') {
                cell.append("\\\\")
                k += 2
                continue
            }
            if (c == '|') {
                out += cell.toString().trim()
                cell.setLength(0)
            } else {
                cell.append(c)
            }
            k++
        }
        out += cell.toString().trim()
        return out
    }

    private fun delimiterRow(line: String): List<Align>? {
        if (!line.contains('-')) return null
        val cs = cells(line)
        if (cs.isEmpty()) return null
        return cs.map { c ->
            val core = c.trim()
            if (core.isEmpty() || !core.trim(':').all { it == '-' } || core.trim(':').isEmpty()) return null
            when {
                core.startsWith(':') && core.endsWith(':') -> Align.CENTER
                core.endsWith(':') -> Align.END
                else -> Align.START
            }
        }
    }

    private fun indent(line: String): Int = line.takeWhile { it == ' ' }.length

    // Inlines

    /**
     * One run of inline text. [inLink]: inside a link's label, where marked — and CommonMark — make no
     * further links, so one visible link is one target.
     */
    private class Inlines(private val s: String, private val depth: Int, private val inLink: Boolean = false) {
        private val out = ArrayList<Inline>()
        private val text = StringBuilder()

        /** Delimiters known to have no closer after a position: a later search for one fails at once. */
        private val noCloserFrom = HashMap<String, Int>()

        /** Closing tags known to be absent after a position, the same way. */
        private val noTagCloseFrom = HashMap<String, Int>()

        /** Where the code span opened by the backtick run at a position closes (-1: it does not). */
        private val spanEnds = HashMap<Int, Int>()

        fun parse(): List<Inline> {
            if (depth >= MAX_DEPTH) return listOf(Inline.Text(s))
            var k = 0
            while (k < s.length) {
                k = step(k)
            }
            flush()
            // A break at the end of a block breaks nothing (CommonMark), and a closing block tag leaves one.
            while (out.lastOrNull() == Inline.Break) out.removeAt(out.size - 1)
            return out
        }

        private fun flush() {
            if (text.isNotEmpty()) {
                out += Inline.Text(text.toString())
                text.setLength(0)
            }
        }

        private fun emit(inline: Inline) {
            flush()
            out += inline
        }

        private fun nested(
            inner: String,
            link: Boolean = inLink,
        ): List<Inline> = Inlines(inner, depth + 1, link).parse()

        /** Reads what starts at [k]; returns where the next read starts. */
        private fun step(k: Int): Int {
            val c = s[k]
            return when (c) {
                '\\' -> escape(k)
                '`' -> codeSpan(k)
                '!' -> if (k + 1 < s.length && s[k + 1] == '[') image(k) else literal(k)
                '[' -> link(k)
                '<' -> angle(k)
                '*', '_' -> emphasis(k)
                '~' -> strike(k)
                '&' -> entity(k)
                '@' -> email(k)
                '\n' -> newline(k)
                'h', 'H', 'w', 'W' -> bareLink(k)
                else -> literal(k)
            }
        }

        private fun literal(k: Int): Int {
            text.append(s[k])
            return k + 1
        }

        private fun escape(k: Int): Int {
            if (k + 1 >= s.length) return literal(k)
            val next = s[k + 1]
            return when {
                next == '\n' -> {
                    emit(Inline.Break)
                    k + 2
                }
                next in PUNCTUATION -> {
                    text.append(next)
                    k + 2
                }
                else -> literal(k)
            }
        }

        private fun newline(k: Int): Int {
            // Two or more spaces before it: a hard break. Otherwise a space, as web renders it.
            var trailing = 0
            while (text.isNotEmpty() && text[text.length - 1] == ' ') {
                text.setLength(text.length - 1)
                trailing++
            }
            if (trailing >= 2) emit(Inline.Break) else text.append(' ')
            var j = k + 1
            while (j < s.length && s[j] == ' ') j++
            return j
        }

        private fun runOf(
            k: Int,
            c: Char,
        ): Int {
            var j = k
            while (j < s.length && s[j] == c) j++
            return j - k
        }

        /**
         * Where the code span opened by the backtick run at [k] closes: the next run of exactly the same
         * length — a longer or shorter run does not close it. -1 if none. Remembered per position, and a
         * run length known to be absent after a point is not searched for again.
         */
        private fun spanEnd(k: Int): Int =
            spanEnds.getOrPut(k) {
                val run = runOf(k, '`')
                val key = "`$run"
                val known = noCloserFrom[key]
                if (known != null && k >= known) return@getOrPut -1
                var j = k + run
                while (j < s.length) {
                    if (s[j] == '`') {
                        val close = runOf(j, '`')
                        if (close == run) return@getOrPut j
                        j += close
                    } else {
                        j++
                    }
                }
                noCloserFrom[key] = k
                -1
            }

        private fun codeSpan(k: Int): Int {
            val run = runOf(k, '`')
            val end = spanEnd(k)
            if (end < 0) {
                text.append(s, k, k + run) // no closing run: the backticks are text
                return k + run
            }
            var body = s.substring(k + run, end).replace('\n', ' ')
            if (body.length >= 2 && body.startsWith(' ') && body.endsWith(' ') && body.isNotBlank()) body = body.substring(1, body.length - 1)
            emit(Inline.Code(body))
            return end + run
        }

        /** Past a code span at [j] if one opens there, else just past the backtick run: spans are opaque. */
        private fun skipSpan(j: Int): Int {
            val run = runOf(j, '`')
            val end = spanEnd(j)
            return if (end < 0) j + run else end + run
        }

        /** `[label](destination "title")`, with the label's brackets balanced. */
        private fun bracketed(open: Int): Triple<String, String, Int>? {
            var level = 0
            var j = open
            var close = -1
            while (j < s.length) {
                when (s[j]) {
                    '\\' -> j++
                    '`' -> j = skipSpan(j) - 1
                    '[' -> level++
                    ']' -> {
                        level--
                        if (level == 0) {
                            close = j
                            break
                        }
                    }
                }
                if (j - open > MAX_LABEL) return null
                j++
            }
            if (close < 0 || close + 1 >= s.length || s[close + 1] != '(') return null
            var d = close + 2
            while (d < s.length && s[d] == ' ') d++
            val destination: String
            if (d < s.length && s[d] == '<') {
                val end = s.indexOf('>', d)
                if (end < 0 || end - d > MAX_URL || s.substring(d, end).contains('\n')) return null
                destination = s.substring(d + 1, end)
                d = end + 1
            } else {
                var parens = 0
                val start = d
                while (d < s.length && !s[d].isWhitespace()) {
                    if (d - start > MAX_URL) return null
                    if (s[d] == '\\' && d + 1 < s.length) {
                        d += 2
                        continue
                    }
                    if (s[d] == '(') parens++
                    if (s[d] == ')') {
                        if (parens == 0) break
                        parens--
                    }
                    d++
                }
                destination = s.substring(start, d)
            }
            while (d < s.length && s[d] == ' ') d++
            // An optional title, which a phone has nowhere to show.
            if (d < s.length && s[d] in "\"'(") {
                val quote = if (s[d] == '(') ')' else s[d]
                val end = s.indexOf(quote, d + 1)
                if (end < 0 || end - d > MAX_LABEL) return null
                d = end + 1
                while (d < s.length && s[d] == ' ') d++
            }
            if (d >= s.length || s[d] != ')') return null
            return Triple(s.substring(open + 1, close), destination, d + 1)
        }

        /** A link's target as the DOM would read it: escapes and entities decoded, then checked. */
        private fun target(destination: String): String? = safeUrl(decodeEntities(destination.replace(ESCAPED, "$1")))

        private fun link(k: Int): Int {
            if (inLink) return literal(k)
            val (label, destination, next) = bracketed(k) ?: return literal(k)
            emit(Inline.Link(target(destination), nested(label, link = true)))
            return next
        }

        private fun image(k: Int): Int {
            val (alt, destination, next) = bracketed(k + 1) ?: return literal(k)
            // No image loader on a phone's SDK: the alt text, linked to the image — or, inside a link
            // (a badge), only the alt text, the link being the outer one.
            val label = nested(alt, link = true).ifEmpty { listOf(Inline.Text(destination)) }
            if (inLink) label.forEach(::emit) else emit(Inline.Link(target(destination), label))
            return next
        }

        /** `<https://…>`, `<name@host>`, or HTML as the sanitiser would leave it. */
        private fun angle(k: Int): Int {
            val end = s.indexOf('>', k + 1)
            if (end < 0 || end - k > MAX_TAG) return literal(k)
            val inside = s.substring(k + 1, end)
            if (inside.isNotEmpty() && inside.none { it.isWhitespace() } && (inside.contains(':') || EMAIL.matches(inside))) {
                val url = if (inside.contains(':')) safeUrl(inside) else "mailto:$inside"
                if (inLink) text.append(inside) else emit(Inline.Link(url, listOf(Inline.Text(inside))))
                return end + 1
            }
            if (inside.startsWith("!--")) {
                val close = s.indexOf("-->", k)
                return if (close < 0) literal(k) else close + COMMENT_CLOSE
            }
            val tag = TAG.matchEntire(inside) ?: return literal(k)
            val closing = tag.groupValues[1].isNotEmpty()
            val name = tag.groupValues[2].lowercase()
            // Block elements keep their lines apart, as the DOM lays them out; a stray closing tag of
            // anything else is dropped.
            if (name in BLOCK_TAGS) {
                if (out.isNotEmpty() || text.isNotEmpty()) {
                    if (out.lastOrNull() != Inline.Break || text.isNotEmpty()) emit(Inline.Break)
                }
                return end + 1
            }
            if (closing) return end + 1
            when (name) {
                "br" -> {
                    emit(Inline.Break)
                    return end + 1
                }
                "script", "style" -> {
                    val close = closeTag(name, end) ?: return s.length
                    val after = s.indexOf('>', close)
                    return if (after < 0) s.length else after + 1
                }
                "b", "strong", "i", "em", "s", "del", "strike", "a" -> {
                    val close = closeTag(name, end) ?: return end + 1
                    val inner = s.substring(end + 1, close)
                    // Past the end tag's own `>`: `</b >` and `</b\n>` close as `</b>` does.
                    val next = s.indexOf('>', close).let { if (it < 0) s.length else it + 1 }
                    if (name == "a") {
                        // DOMPurify keeps a safe href.
                        val href = HREF.find(tag.groupValues[3])?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                        val url = href?.let(::target)
                        if (inLink || url == null) nested(inner).forEach(::emit) else emit(Inline.Link(url, nested(inner, link = true)))
                        return next
                    }
                    val children = nested(inner)
                    emit(
                        when (name) {
                            "b", "strong" -> Inline.Strong(children)
                            "i", "em" -> Inline.Emphasis(children)
                            else -> Inline.Strike(children)
                        },
                    )
                    return next
                }
                else -> return end + 1 // any other tag: dropped, its text kept
            }
        }

        /** Where `</name` next appears after [from], or `null` — remembered, so unclosed tags stay linear. */
        private fun closeTag(
            name: String,
            from: Int,
        ): Int? {
            noTagCloseFrom[name]?.let { if (from >= it) return null }
            val close = s.indexOf("</$name", from, ignoreCase = true)
            if (close < 0) {
                noTagCloseFrom[name] = from
                return null
            }
            return close
        }

        private fun emphasis(k: Int): Int {
            val c = s[k]
            val run = runOf(k, c)
            val before = if (k > 0) s[k - 1] else ' '
            val after = if (k + run < s.length) s[k + run] else ' '
            // Left-flanking: it can open only before something that is not a space.
            val opens = !after.isWhitespace() && !(c == '_' && before.isLetterOrDigit())
            if (!opens) {
                text.append(s, k, k + run)
                return k + run
            }
            for (width in listOf(minOf(run, STRONG_EMPHASIS), STRONG, 1).distinct().filter { it <= run }) {
                val close = closer(k + run, c, width)
                if (close != null) {
                    // The opener is the run's last [width]; the rest of a longer run is text, as `****x**` reads.
                    if (run > width) text.append(s, k, k + run - width)
                    val inner = nested(s.substring(k + run, close))
                    emit(
                        when (width) {
                            STRONG_EMPHASIS -> Inline.Strong(listOf(Inline.Emphasis(inner)))
                            STRONG -> Inline.Strong(inner)
                            else -> Inline.Emphasis(inner)
                        },
                    )
                    return close + width
                }
            }
            text.append(s, k, k + run)
            return k + run
        }

        /**
         * The next run of [c] after [from] that can close a [width] opener: right-flanking, and, for `_`,
         * not inside a word. Exactly [width] long — or, for `*` and `_`, three or more, of which the
         * last [width] close this one (`*a **b***`); a run of two never closes a single. Tildes close
         * only on an exact run.
         */
        private fun closer(
            from: Int,
            c: Char,
            width: Int,
        ): Int? {
            val key = c.toString().repeat(width)
            noCloserFrom[key]?.let { if (from >= it) return null }
            var j = from
            while (j < s.length) {
                val ch = s[j]
                if (ch == '\\') {
                    j += 2
                    continue
                }
                if (ch == '`') {
                    j = skipSpan(j)
                    continue
                }
                if (ch == c) {
                    val run = runOf(j, c)
                    val before = s[j - 1]
                    val after = if (j + run < s.length) s[j + run] else ' '
                    val canClose = !before.isWhitespace() && j > from && !(c == '_' && after.isLetterOrDigit())
                    if (canClose && run == width) return j
                    if (canClose && c != '~' && run >= STRONG_EMPHASIS && run > width) return j + run - width
                    j += run
                    continue
                }
                j++
            }
            noCloserFrom[key] = from
            return null
        }

        /** GFM's strikethrough, as marked reads it: one tilde or two, closed by the same. */
        private fun strike(k: Int): Int {
            val run = runOf(k, '~')
            if (run > STRONG || k + run >= s.length || s[k + run].isWhitespace()) {
                text.append(s, k, k + run)
                return k + run
            }
            val close = closer(k + run, '~', run)
            if (close == null) {
                text.append(s, k, k + run)
                return k + run
            }
            emit(Inline.Strike(nested(s.substring(k + run, close))))
            return close + run
        }

        private fun entity(k: Int): Int {
            val (decoded, next) = entityAt(s, k) ?: return literal(k)
            text.append(decoded)
            return next
        }

        /**
         * GFM's bare links, as marked finds them — anywhere in the text, not only at a word's start:
         * `https://…`, `http://…`, `www.…`. Trailing punctuation is the sentence's, and a closing paren
         * only when unmatched.
         */
        private fun bareLink(k: Int): Int {
            if (inLink) return literal(k)
            val prefix =
                when {
                    s.regionMatches(k, "https://", 0, HTTPS, ignoreCase = true) -> HTTPS
                    s.regionMatches(k, "http://", 0, HTTP, ignoreCase = true) -> HTTP
                    s.regionMatches(k, "www.", 0, WWW, ignoreCase = true) -> WWW
                    else -> return literal(k)
                }
            // marked's domain: at least one letter, digit or hyphen after the prefix.
            if (k + prefix >= s.length || !(s[k + prefix].isLetterOrDigit() || s[k + prefix] == '-')) return literal(k)
            var end = k
            var opens = 0
            var closes = 0
            while (end < s.length && !s[end].isWhitespace() && s[end] != '<') {
                if (s[end] == '(') opens++
                if (s[end] == ')') closes++
                end++
            }
            while (end > k + prefix) {
                val last = s[end - 1]
                if (last in TRAILING) {
                    end--
                } else if (last == ')' && closes > opens) {
                    end--
                    closes--
                } else {
                    break
                }
            }
            if (end <= k + prefix) return literal(k)
            val url = s.substring(k, end)
            emit(Inline.Link(safeUrl(url), listOf(Inline.Text(url))))
            return end
        }

        /**
         * A bare email address, as marked links it: the local part already read as text, then
         * `@domain.tld`. Linear: the local part is scanned back over the text just read, the domain
         * forward.
         */
        private fun email(k: Int): Int {
            if (inLink) return literal(k)
            var start = text.length
            while (start > 0 && text[start - 1].let { it.isLetterOrDigit() || it in EMAIL_LOCAL }) start--
            if (start == text.length) return literal(k)
            var j = k + 1
            var labels = 0
            var lastLabelEnd = -1
            while (true) {
                val labelStart = j
                while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '-' || s[j] == '_')) j++
                if (j == labelStart) break
                labels++
                lastLabelEnd = j
                if (j < s.length && s[j] == '.' && j + 1 < s.length && s[j + 1].isLetterOrDigit()) j++ else break
            }
            val domainEnd = lastLabelEnd
            if (labels < 2 || !s[domainEnd - 1].isLetterOrDigit()) return literal(k)
            val local = text.substring(start)
            text.setLength(start)
            val address = local + "@" + s.substring(k + 1, domainEnd)
            emit(Inline.Link("mailto:$address", listOf(Inline.Text(address))))
            return domainEnd
        }
    }

    private val SCHEMES = setOf("http", "https", "mailto", "tel")
    private const val PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    private val ESCAPED = Regex("""\\([!-/:-@\[-`{-~])""")
    private val TAG = Regex("""(/?)([A-Za-z][A-Za-z0-9-]*)((?:\s[^<>]*)?)/?""")
    // An attribute of its own: `data-href` is not a link (the attribute string starts with whitespace).
    private val HREF = Regex("""(?:^|\s)href\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""", RegexOption.IGNORE_CASE)
    private val BLOCK_TAGS = setOf("p", "div", "li", "ul", "ol", "tr", "table", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "section", "article", "header", "footer", "hr")
    private const val TRAILING = "?!.,:;*_~'\""
    private const val EMAIL_LOCAL = "._+-"
    private val EMAIL = Regex("""[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+""")
    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0", "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "euro" to "€", "pound" to "£")
    private val SURROGATES = 0xD800..0xDFFF

    private const val MAX_SOURCE = 20_000
    private const val MAX_COLUMNS = 20
    private const val MAX_CELLS = 2_000
    private const val MAX_URL = 2_048
    private const val MAX_DEPTH = 12
    private const val MAX_LABEL = 1_000
    private const val MAX_TAG = 200
    private const val MAX_ENTITY = 10
    private const val MAX_HEADING = 6
    private const val MAX_MARKER_INDENT = 3
    private const val MAX_ITEM_GAP = 4
    private const val MAX_ORDINAL_DIGITS = 9
    private const val INDENTED_CODE = 4
    private const val FENCE = 3
    private const val RULE = 3
    private const val STRONG = 2
    private const val STRONG_EMPHASIS = 3
    private const val COMMENT_CLOSE = 3 // `-->`
    private const val HEX = 16
    private const val HTTPS = 8
    private const val HTTP = 7
    private const val WWW = 4
}
