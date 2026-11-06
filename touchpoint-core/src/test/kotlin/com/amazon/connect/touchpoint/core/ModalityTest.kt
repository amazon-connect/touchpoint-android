package com.amazon.connect.touchpoint.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Web's modalities and Connect's views, read from frames the live showcase flow put on the wire (captured 2026-10-20). */
class ModalityTest {
    private fun frame(name: String) = checkNotNull(javaClass.getResource("/showcase/$name.json")).readText()

    private fun content(name: String, template: String = InteractiveContent.CHOICE_TEMPLATE) = InteractiveContent(template, frame(name))

    @Test
    fun `a message's modalities are read in the flow's order, as web draws them`() {
        assertEquals(listOf("DefaultCard", "DefaultDateInput"), content("cardAndDate").modalities.map { it.name })
        assertEquals(listOf("AcmeDisputeSummary", "DefaultVideo"), content("summaryAndVideo").modalities.map { it.name })
        val carousel = content("carousel").modalities.single()
        assertEquals("DefaultCarousel", carousel.name)
        assertEquals(JsonPrimitive("Charge"), ((carousel.data as JsonObject)["\$saveAs"] as JsonObject)["id"])
    }

    @Test
    fun `a choice menu, a view, and anything unreadable carry no modalities`() {
        assertTrue(content("menu").modalities.isEmpty())
        assertTrue(InteractiveContent("ViewResource", frame("view")).modalities.isEmpty())
        assertTrue(InteractiveContent(InteractiveContent.CHOICE_TEMPLATE, "{not json").modalities.isEmpty())
        assertTrue(InteractiveContent("QuickReply", frame("carousel")).modalities.isEmpty())
    }

    @Test
    fun `a view's heading and subheading are what the guide card shows, its viewData a string or an object`() {
        val summary = InteractiveContent("ViewResource", frame("view")).viewSummary
        assertEquals(ViewSummary("Before we connect you", "Tell us a little about your request."), summary)
        assertEquals(ViewSummary("H", null), InteractiveContent("ViewResource", """{"data":{"content":{"viewData":{"Heading":"H"}}}}""").viewSummary)
        assertNull(content("menu").viewSummary)
    }

    @Test
    fun `a modality prints no data`() {
        assertTrue("Harbour" !in content("carousel").modalities.single().toString())
    }

    @Test
    fun `a view's token and the flow's input data are what opening it needs, the data read from a string or an object`() {
        val ref = InteractiveContent("ViewResource", frame("view")).viewReference
        assertEquals("redacted", ref?.viewToken)
        assertTrue(ref?.inputData?.contains("Before we connect you") == true)
        assertTrue(ViewReference("secret-token", """{"Heading":"private"}""").toString().let { "secret-token" !in it && "private" !in it })
        val obj = InteractiveContent("ViewResource", """{"data":{"content":{"viewToken":"t","viewData":{"Heading":"H"}}}}""").viewReference
        assertEquals(ViewReference("t", """{"Heading":"H"}"""), obj)
        assertEquals("{}", InteractiveContent("ViewResource", """{"data":{"content":{"viewToken":"t"}}}""").viewReference?.inputData)
        assertNull(InteractiveContent("ViewResource", """{"data":{"content":{}}}""").viewReference)
        assertNull(content("menu").viewReference)
    }
}
