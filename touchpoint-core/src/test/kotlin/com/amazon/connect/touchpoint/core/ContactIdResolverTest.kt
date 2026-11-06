package com.amazon.connect.touchpoint.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A contact id from a deep link, refused unless it is exactly ours and exactly an id. */
class ContactIdResolverTest {
    private val id = "6f1d9a3e-2b4c-4d5e-8f60-718293a4b5c6"

    private fun parse(url: String, prefix: String? = "/livesync") = DeepLinkContactIdResolver.contactId(url, "acmebank", "support", prefix)

    @Test
    fun `the id is read from the path or the query, which wins`() {
        assertEquals(id, parse("acmebank://support/livesync/$id"))
        assertEquals(id, parse("ACMEBANK://Support/livesync?contactId=$id"))
        assertEquals(id, parse("acmebank://support/livesync/00000000-0000-0000-0000-000000000000?contact_id=$id"))
        assertEquals(id, parse("acmebank://support/livesync/${id.uppercase()}"))
    }

    @Test
    fun `anything not exactly ours, or not exactly an id, is refused`() {
        assertNull(parse("https://support/livesync/$id"))
        assertNull(parse("acmebank://elsewhere/livesync/$id"))
        assertNull(parse("acmebank://user@support/livesync/$id"))
        assertNull(parse("acmebank://support:8080/livesync/$id"))
        assertNull(parse("acmebank://support/other/$id"))
        assertNull(parse("acmebank://support/livesync/../$id"))
        assertNull(parse("acmebank://support/livesync/not-an-id"))
        assertNull(parse("acmebank://support/livesync?contactId=$id%26x%3D1"))
        assertNull(parse("acmebank://support/livesync/{$id}"))
        assertNull(parse("not a url at all"))
        assertNull(parse("acmebank://support/livesync?contactId=%"))
        assertNull(parse("acmebank://support/livesync?contactId=%ZZ"))
    }

    @Test
    fun `the resolvers resolve`() =
        runBlocking {
            assertEquals(id, DeepLinkContactIdResolver("acmebank://support/livesync/$id", "acmebank", "support").resolveContactId())
            assertNull(DeepLinkContactIdResolver(null, "acmebank", "support").resolveContactId())
            assertEquals("fixed", StaticContactIdResolver("fixed").resolveContactId())
        }
}
