package com.amazon.connect.touchpoint.chat.ui

import com.amazon.connect.touchpoint.core.ResolvedView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opening a Connect View as web's GuideModal does: Connect's renderer, the view, and its action. */
class ConnectViewsTest {
    private val view = ResolvedView("v1", "Form", 1, """{"Head":{"Title":"t"}}""", """{"type":"object"}""", listOf("Submit", "Cancel"))

    @Test
    fun `the renderer loads from the host's instance over https only, as web's script URL`() {
        assertEquals("https://example.my.connect.aws/connectwidget/static/views/renderer/latest/index.js", ConnectViewPage.scriptUrl("https://example.my.connect.aws/"))
        assertEquals("https://example.my.connect.aws", ConnectViewPage.origin("example.my.connect.aws"))
        assertNull(ConnectViewPage.origin("http://example.my.connect.aws"))
        assertNull(ConnectViewPage.html("javascript:alert(1)", view, "{}"))
    }

    @Test
    fun `only an Amazon Connect instance is an origin`() {
        assertEquals("https://acme.my.connect.aws", ConnectViewPage.origin("https://acme.my.connect.aws"))
        assertEquals("https://acme.awsapps.com", ConnectViewPage.origin("https://acme.awsapps.com"))
        assertEquals("https://acme.my.connect.aws", ConnectViewPage.origin("https://ACME.my.connect.aws/connect/home?x=1"))
        // Not https, the bare apex, a look-alike suffix, an unrelated host.
        assertNull(ConnectViewPage.origin("http://acme.my.connect.aws"))
        assertNull(ConnectViewPage.origin("https://my.connect.aws"))
        assertNull(ConnectViewPage.origin("https://acme.my.connect.aws.evil.com"))
        assertNull(ConnectViewPage.origin("https://evil.com"))
        assertNull(ConnectViewPage.html("https://evil.com", view, "{}"))
    }

    @Test
    fun `the page's policy keeps every fetch on the instance and runs no inline script but its own nonced boot`() {
        val origin = "https://acme.my.connect.aws"
        val html = ConnectViewPage.html(origin, view, "{}", nonce = "abc123")!!
        val csp = html.lines().first { "Content-Security-Policy" in it }
        assertTrue("script-src 'self' $origin 'nonce-abc123'" in csp)
        assertTrue("connect-src 'self' $origin" in csp && "default-src 'self' $origin" in csp)
        assertTrue("frame-ancestors 'none'" in csp && "base-uri 'none'" in csp && "form-action 'none'" in csp)
        assertFalse("unsafe-eval" in csp)
        assertFalse("script-src 'self' $origin 'unsafe" in csp)
        assertTrue("<script nonce=\"abc123\">" in html)
        // No inline handler, which the policy would refuse: the renderer's load failure is the boot script's.
        assertFalse("onerror=" in html)
        // A fresh nonce per page.
        val a = ConnectViewPage.html(origin, view, "{}")!!
        val b = ConnectViewPage.html(origin, view, "{}")!!
        assertTrue(a != b)
    }

    @Test
    fun `the page's policy names its own instance and no other, so a guide cannot reach a sibling tenant`() {
        // The allowlist admits any Connect instance as configuration; the lock is the configured origin.
        val origin = "https://acme.my.connect.aws"
        val csp = ConnectViewPage.html(origin, view, "{}", nonce = "n")!!.lines().first { "Content-Security-Policy" in it }
        val sources = Regex("""[a-z][a-z0-9+.-]*://[^\s;"']+|\*""").findAll(csp).map { it.value }.toSet()
        assertEquals(setOf(origin), sources)
        for (other in listOf("https://other.my.connect.aws", "https://acme.awsapps.com", "https://acme.my.connect.aws:8443", "https://x.acme.my.connect.aws")) {
            assertFalse(other in csp.replace(origin, ""), other)
        }
        // Another port or a subdomain is its own origin, never folded into the configured one.
        assertEquals("https://acme.my.connect.aws:8443", ConnectViewPage.origin("https://acme.my.connect.aws:8443"))
        assertEquals("https://x.acme.my.connect.aws", ConnectViewPage.origin("https://x.acme.my.connect.aws"))
    }

    @Test
    fun `the policy names the configured instance and no other - not a sibling tenant on the same suffix`() {
        for (origin in listOf("https://acme.awsapps.com", "https://acme.my.connect.aws")) {
            val csp = ConnectViewPage.html(origin, view, "{}", nonce = "n")!!.lines().first { "Content-Security-Policy" in it }
            val policy = csp.substringAfter("content=\"").substringBefore('"')
            val sources = policy.split(';').flatMap { it.trim().split(' ').drop(1) }
            // Every source is a keyword, a scheme for inline data, or the instance itself.
            val allowed = setOf("'self'", "'none'", "'unsafe-inline'", "'nonce-n'", "data:", "blob:", origin)
            assertEquals(emptyList(), sources.filter { it !in allowed }, "for $origin")
            assertTrue(origin in sources, "the instance itself is named")
            assertFalse("*" in policy, "no wildcard host")
            assertFalse("https://other." in policy)
        }
    }

    @Test
    fun `the renderer is given web's view payload, as the customer, and nothing in the data can end the script`() {
        val payload = ConnectViewPage.payload(view, """{"Heading":"</script><script>x()</script>"}""")
        assertEquals(listOf("Submit", "Cancel"), payload["Content"]!!.jsonObject["Actions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("t", payload["Content"]!!.jsonObject["Template"]!!.jsonObject["Head"]!!.jsonObject["Title"]!!.jsonPrimitive.content)
        val html = ConnectViewPage.html("https://example.my.connect.aws", view, """{"Heading":"</script><script>x()</script>"}""")!!
        assertTrue("'END_CUSTOMER'" in html && "'Actual'" in html)
        assertFalse("</script><script>x()" in html)
        assertFalse("<script>x()" in html)
    }

    @Test
    fun `an action is web's reading of onAction - its Action, and its Output or Data`() {
        assertEquals("Submit" to """{"reason":"a charge"}""", ConnectViewPage.action("""{"Action":"Submit","Output":{"reason":"a charge"}}"""))
        assertEquals("Cancel" to """{"x":1}""", ConnectViewPage.action("""{"Action":"Cancel","Data":{"x":1}}"""))
        assertEquals("Submit" to "{}", ConnectViewPage.action("""{"Action":"Submit"}"""))
        assertNull(ConnectViewPage.action("""{"Output":{}}"""))
        assertNull(ConnectViewPage.action("not json"))
        // Size-capped: a runaway or hostile page sends nothing.
        assertNull(ConnectViewPage.action("""{"Action":"Submit","Output":{"blob":"${"a".repeat(70_000)}"}}"""))
        assertTrue(Json.parseToJsonElement(ConnectViewPage.action("""{"Action":"Submit","Output":{"a":[1,2]}}""")!!.second).jsonObject.containsKey("a"))
    }

    @Test
    fun `the page's cookies are read by name, to expire with it`() {
        assertEquals(listOf("a", "session"), ConnectViewPage.cookieNames("a=1; session=xyz; a=2"))
        assertEquals(emptyList(), ConnectViewPage.cookieNames(null))
        assertEquals(emptyList(), ConnectViewPage.cookieNames(" ; "))
    }
}
