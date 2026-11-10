package com.amazon.connect.touchpoint.chat.ui

import com.amazon.connect.touchpoint.chat.ui.Markdown.Align
import com.amazon.connect.touchpoint.chat.ui.Markdown.Block
import com.amazon.connect.touchpoint.chat.ui.Markdown.Inline
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** Markdown as web's `marked` + DOMPurify render an assistant's message, and safety on hostile input. */
class MarkdownTest {
    private fun text(s: String) = Inline.Text(s)

    private fun para(vararg i: Inline) = Block.Paragraph(i.toList())

    private fun one(source: String): Block = Markdown.parse(source).single()

    private fun inlines(source: String): List<Inline> = (one(source) as Block.Paragraph).inlines

    @Test
    fun `a single line break is a space, two trailing spaces or a backslash break the line`() {
        assertEquals(para(text("one two")), one("one\ntwo"))
        assertEquals(para(text("one"), Inline.Break, text("two")), one("one  \ntwo"))
        assertEquals(para(text("one"), Inline.Break, text("two")), one("one\\\ntwo"))
        assertEquals(listOf(para(text("one")), para(text("two"))), Markdown.parse("one\n\ntwo"))
    }

    @Test
    fun `emphasis - bold, italic, both, strikethrough, and the underscore rule`() {
        assertEquals(listOf(Inline.Strong(listOf(text("b")))), inlines("**b**"))
        assertEquals(listOf(Inline.Strong(listOf(text("b")))), inlines("__b__"))
        assertEquals(listOf(Inline.Emphasis(listOf(text("i")))), inlines("*i*"))
        assertEquals(listOf(Inline.Emphasis(listOf(text("i")))), inlines("_i_"))
        assertEquals(listOf(Inline.Strong(listOf(Inline.Emphasis(listOf(text("bi")))))), inlines("***bi***"))
        assertEquals(listOf(Inline.Strike(listOf(text("gone")))), inlines("~~gone~~"))
        assertEquals(listOf(Inline.Emphasis(listOf(text("a "), Inline.Strong(listOf(text("b")))))), inlines("*a **b***"))
        assertEquals(listOf(Inline.Strong(listOf(text("a "), Inline.Emphasis(listOf(text("b")))))), inlines("**a *b***"))
        // Inside a word, an underscore is a letter; a star opening before a space opens nothing.
        assertEquals(listOf(text("snake_case_name")), inlines("snake_case_name"))
        assertEquals(listOf(text("2 * 3 * 4")), inlines("2 * 3 * 4"))
        assertEquals(listOf(text("**unclosed")), inlines("**unclosed"))
    }

    @Test
    fun `headings, ATX and setext, and rules`() {
        assertEquals(Block.Heading(1, listOf(text("Title"))), one("# Title"))
        assertEquals(Block.Heading(3, listOf(text("Closed"))), one("### Closed ###"))
        assertEquals(para(text("#hashtag")), one("#hashtag"))
        assertEquals(Block.Heading(1, listOf(text("Big"))), one("Big\n==="))
        assertEquals(Block.Heading(2, listOf(text("Small"))), one("Small\n---"))
        assertEquals(Block.Rule, one("***"))
        assertEquals(Block.Rule, one("- - -"))
        assertEquals(listOf(para(text("a")), Block.Rule, para(text("b"))), Markdown.parse("a\n\n___\n\nb"))
    }

    @Test
    fun `code - spans, fenced, indented, and nothing inside is markup`() {
        assertEquals(listOf(text("run "), Inline.Code("make **check**"), text(" now")), inlines("run `make **check**` now"))
        assertEquals(listOf(Inline.Code("a`b")), inlines("`` a`b ``"))
        assertEquals(Block.Code("val x = 1\n<b>not bold</b>"), one("```kotlin\nval x = 1\n<b>not bold</b>\n```"))
        assertEquals(Block.Code("unclosed"), one("~~~\nunclosed"))
        assertEquals(Block.Code("indented\n  more"), one("    indented\n      more"))
        assertEquals(listOf(text("`not code")), inlines("`not code"))
    }

    @Test
    fun `lists - bullets, numbers from their start, nesting, and lazy lines`() {
        // A different bullet character starts a new list, as in CommonMark.
        val lists = Markdown.parse("- one\n- two\n* three")
        assertEquals(listOf(2, 1), lists.map { (it as Block.Items).items.size })
        assertEquals(false, (lists[0] as Block.Items).ordered)
        val numbers = one("3. three\n4. four") as Block.Items
        assertEquals(true, numbers.ordered)
        assertEquals(3, numbers.start)
        assertEquals(listOf(listOf(para(text("three"))), listOf(para(text("four")))), numbers.items)
        val nested = one("- outer\n  - inner\n- next") as Block.Items
        assertEquals(2, nested.items.size)
        assertEquals(para(text("outer")), nested.items[0][0])
        assertTrue(nested.items[0][1] is Block.Items)
        val lazy = one("- first line\ncontinued") as Block.Items
        assertEquals(listOf(para(text("first line continued"))), lazy.items[0])
        // A number other than 1 does not interrupt a paragraph: it is the paragraph's text.
        assertEquals(para(text("In 2024 we had 2. results")), one("In 2024 we had\n2. results"))
    }

    @Test
    fun `quotes and tables`() {
        assertEquals(Block.Quote(listOf(para(text("said and more")))), one("> said\nand more"))
        val table = one("| Card | Due |\n|:---|---:|\n| Rewards | \$240 |\n| Debit |") as Block.Table
        assertEquals(listOf(listOf(text("Card")), listOf(text("Due"))), table.header)
        assertEquals(listOf(Align.START, Align.END), table.align)
        assertEquals(listOf(listOf(listOf(text("Rewards")), listOf(text("\$240"))), listOf(listOf(text("Debit")), emptyList())), table.rows)
    }

    @Test
    fun `links open only web, mail and phone targets - anything else is shown as text`() {
        assertEquals(listOf(Inline.Link("https://acme.example/help", listOf(text("help")))), inlines("[help](https://acme.example/help)"))
        assertEquals(listOf(Inline.Link("https://acme.example/a_(b)", listOf(text("x")))), inlines("[x](https://acme.example/a_(b) \"title\")"))
        assertEquals(listOf(Inline.Link("mailto:help@acme.example", listOf(text("mail")))), inlines("[mail](mailto:help@acme.example)"))
        assertEquals(listOf(Inline.Link("tel:+15550100", listOf(text("call")))), inlines("[call](tel:+15550100)"))
        for (hostile in listOf("javascript:alert(1)", "intent://scan#Intent;scheme=zxing;end", "file:///sdcard/x", "content://x/y", "data:text/html,hi", "https:nohost", "relative/path")) {
            assertEquals(listOf(Inline.Link(null, listOf(text("go")))), inlines("[go]($hostile)"), hostile)
        }
        assertNull(Markdown.safeUrl("JaVaScRiPt:alert(1)"))
        assertNull(Markdown.safeUrl("https://a b"))
    }

    @Test
    fun `autolinks, bare links without their trailing punctuation, www, and email`() {
        assertEquals(listOf(Inline.Link("https://acme.example", listOf(text("https://acme.example")))), inlines("<https://acme.example>"))
        assertEquals(listOf(Inline.Link("mailto:help@acme.example", listOf(text("help@acme.example")))), inlines("<help@acme.example>"))
        assertEquals(listOf(text("See "), Inline.Link("https://acme.example/x", listOf(text("https://acme.example/x"))), text(".")), inlines("See https://acme.example/x."))
        assertEquals(listOf(text("("), Inline.Link("http://www.acme.example", listOf(text("www.acme.example"))), text(")")), inlines("(www.acme.example)"))
        // marked finds bare links anywhere in text, not only after a space.
        assertEquals(listOf(text("Visit:"), Inline.Link("https://acme.example", listOf(text("https://acme.example")))), inlines("Visit:https://acme.example"))
        assertEquals(listOf(text("詳細は"), Inline.Link("https://acme.example", listOf(text("https://acme.example")))), inlines("詳細はhttps://acme.example"))
        assertEquals(listOf(text("https://")), inlines("https://"))
        // And bare email addresses, without the sentence's full stop.
        assertEquals(listOf(text("Write to "), Inline.Link("mailto:help.desk+1@acme.example", listOf(text("help.desk+1@acme.example"))), text(".")), inlines("Write to help.desk+1@acme.example."))
        assertEquals(listOf(text("@handle and a@b")), inlines("@handle and a@b"))
    }

    @Test
    fun `one visible link is one target - no links inside a link's words`() {
        assertEquals(listOf(Inline.Link("https://acme.example/help", listOf(text("https://acme.example/help")))), inlines("[https://acme.example/help](https://acme.example/help)"))
        assertEquals(listOf(Inline.Link("https://b.example", listOf(text("see https://a.example")))), inlines("[see https://a.example](https://b.example)"))
        assertEquals(listOf(Inline.Link("https://b.example", listOf(text("[x](https://a.example)")))), inlines("[[x](https://a.example)](https://b.example)"))
        // A badge: an image inside a link is the link's words.
        assertEquals(listOf(Inline.Link("https://acme.example", listOf(text("status")))), inlines("[![status](https://acme.example/badge.svg)](https://acme.example)"))
    }

    @Test
    fun `targets are read as the DOM reads them - entities decoded, the scheme lower-case`() {
        assertEquals(listOf(Inline.Link("https://acme.example/?a=1&b=2", listOf(text("x")))), inlines("[x](https://acme.example/?a=1&amp;b=2)"))
        assertEquals(listOf(Inline.Link(null, listOf(text("x")))), inlines("[x](jav&#97;script:alert(1))"))
        assertEquals("https://acme.example/Path", Markdown.safeUrl("HTTPS://acme.example/Path"))
        assertEquals("mailto:Help@acme.example", Markdown.safeUrl("MailTo:Help@acme.example"))
    }

    @Test
    fun `single tildes strike through, as marked's GFM has it`() {
        assertEquals(listOf(Inline.Strike(listOf(text("gone")))), inlines("~gone~"))
        assertEquals(listOf(text("a ~~~not~~~")), inlines("a ~~~not~~~"))
    }

    @Test
    fun `images are their alt text, linked`() {
        assertEquals(listOf(Inline.Link("https://acme.example/card.png", listOf(text("your card")))), inlines("![your card](https://acme.example/card.png)"))
        assertEquals(listOf(Inline.Link(null, listOf(text("x")))), inlines("![x](javascript:alert(1))"))
    }

    @Test
    fun `HTML as the sanitiser leaves it`() {
        assertEquals(listOf(Inline.Strong(listOf(text("b"))), text(" "), Inline.Emphasis(listOf(text("i")))), inlines("<b>b</b> <em>i</em>"))
        assertEquals(listOf(text("a"), Inline.Break, text("b")), inlines("a<br>b"))
        assertEquals(listOf(text("ab")), inlines("a<script>steal()</script>b"))
        assertEquals(listOf(text("ab")), inlines("a<style>p{}</style>b"))
        assertEquals(listOf(Inline.Link("https://acme.example", listOf(text("Acme")))), inlines("<a href=\"https://acme.example\" onclick=\"y\">Acme</a>"))
        assertEquals(listOf(text("Acme")), inlines("<a href='javascript:steal()'>Acme</a>"))
        // Block elements keep their lines apart, as the DOM lays them out.
        assertEquals(listOf(text("Hello"), Inline.Break, text("there")), inlines("<p>Hello</p><p>there</p>"))
        assertEquals(listOf(text("One"), Inline.Break, text("Two")), inlines("<ul><li>One</li><li>Two</li></ul>"))
        assertEquals(listOf(text("ab")), inlines("a<!-- hidden -->b"))
        assertEquals(listOf(text("1 < 2 and 3 > 2")), inlines("1 < 2 and 3 > 2"))
    }

    @Test
    fun `escapes and entities`() {
        assertEquals(listOf(text("*not italic* [x]")), inlines("\\*not italic\\* \\[x]"))
        assertEquals(listOf(text("A & B <c> © — é")), inlines("A &amp; B &lt;c&gt; &copy; &mdash; &#233;"))
        assertEquals(listOf(text("&bogus; &#xD800;")), inlines("&bogus; &#xD800;"))
    }

    @Test
    fun `plain text for TalkBack and the announcer carries no markup, but keeps a list's numbers`() {
        assertEquals("Your card ending 1234 is ready.\nTrack it\nor call us", Markdown.plainText("Your **card** ending `1234` is _ready_.\n\n- [Track it](https://acme.example)\n- or call us"))
        assertEquals("Steps:\n1. Open\n2. Tap\nThen:\n3. Done", Markdown.plainText("Steps:\n\n1. Open\n2. Tap\n\nThen:\n\n3. Done"))
        assertEquals("", Markdown.plainText(""))
    }

    @Test
    fun `a message's links, for TalkBack's actions`() {
        val blocks = Markdown.parse("[Track it](https://acme.example/track), mail help@acme.example, or [nothing](javascript:x).\n\n- see www.acme.example")
        assertEquals(
            listOf("Track it" to "https://acme.example/track", "help@acme.example" to "mailto:help@acme.example", "www.acme.example" to "http://www.acme.example"),
            Markdown.links(blocks),
        )
    }

    @Test
    fun `a table is bounded - too wide is text, too long stops being a table`() {
        val wide = (1..30).joinToString("|", "|", "|") { "h$it" } + "\n" + (1..30).joinToString("|", "|", "|") { "-" }
        assertTrue(Markdown.parse(wide).none { it is Block.Table })
        val long = "|a|b|\n|-|-|\n" + "|x|y|\n".repeat(3_000) // within the 20,000-character bound
        val table = Markdown.parse(long).first() as Block.Table
        assertTrue(table.rows.size * 2 <= 2_000)
    }

    @Test
    fun `hostile input never throws and stays fast`() {
        val pathological =
            listOf(
                "*a".repeat(5_000), "_".repeat(10_000), "[".repeat(10_000), "![".repeat(5_000), "`".repeat(9_999) + "x",
                "<".repeat(10_000), "&".repeat(10_000), "> ".repeat(5_000), "- ".repeat(5_000), "1. ".repeat(3_000),
                (1..2_000).joinToString("\n") { "  ".repeat(it % 40) + "- item" }, "|a|\n|-|\n" + "|x|\n".repeat(3_000),
                "**".repeat(5_000), "~~".repeat(5_000), "https://" + "a".repeat(10_000), "x".repeat(25_000),
                // The review's: unclosed tags, a link of closing parens, blank lines after indented code,
                // unclosed image destinations, a table of millions of cells.
                "<b>".repeat(6_666), "https://a" + ")".repeat(19_980), "    a\n" + "\n".repeat(19_980) + "    b", "![a](".repeat(4_000),
                (1..1_000).joinToString("|", "|", "|") { "a" } + "\n" + (1..1_000).joinToString("|", "|", "|") { "-" } + "\n" + "|\n".repeat(8_000),
                "a@".repeat(9_000), "`` ` ".repeat(4_000), "*a `b ".repeat(3_000),
            )
        val elapsed =
            measureTime {
                for (p in pathological) Markdown.parse(p)
            }
        assertTrue(elapsed < 2.seconds, "pathological inputs took $elapsed")

        val alphabet = "*_~`[]()!<>&#;:/\\|-+=. \nabcAB1234\"'"
        val random = Random(42)
        repeat(3_000) {
            val s = String(CharArray(random.nextInt(0, 200)) { alphabet[random.nextInt(alphabet.length)] })
            Markdown.parse(s)
            Markdown.plainText(s)
        }
    }

    @Test
    fun `text beyond the bound is shown as written, unparsed`() {
        val long = "**x** ".repeat(5_000)
        assertEquals(listOf(para(text(long))), Markdown.parse(long))
    }

    @Test
    fun `an end tag with space before its bracket closes, and an escaped backslash leaves the pipe a delimiter`() {
        val para = Markdown.parse("<b>bold</b >after").single() as Markdown.Block.Paragraph
        assertEquals("boldafter", Markdown.plainText(listOf(para)))
        val table = Markdown.parse("| a | b |\n|---|---|\n| x\\\\| y |").single() as Markdown.Block.Table
        assertEquals(2, table.rows.single().size)
    }

    @Test
    fun `a data-href is not a link, and a web link needs a host`() {
        assertTrue(Markdown.links(Markdown.parse("""<a data-href="https://evil.example">x</a>""")).isEmpty())
        assertEquals(null, Markdown.safeUrl("https://"))
        assertEquals(null, Markdown.safeUrl("https:///path"))
        assertEquals("https://acme.example", Markdown.safeUrl("https://acme.example"))
    }
}
