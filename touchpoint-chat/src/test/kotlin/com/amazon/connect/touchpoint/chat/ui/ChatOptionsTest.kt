package com.amazon.connect.touchpoint.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Web's hiddenElements, addedElements and icons, as options. */
class ChatOptionsTest {
    @Test
    fun `everything shows by default but the welcome logo, and a hidden element does not`() {
        // The guide's welcome screen has no logo; a host turns it on with welcomeScreenLogo.
        for (e in TouchpointHiddenElement.entries) assertEquals(e != TouchpointHiddenElement.WELCOME_SCREEN_LOGO, TouchpointChatOptions.Default.shows(e))
        assertTrue(TouchpointChatOptions(welcomeScreenLogo = true).shows(TouchpointHiddenElement.WELCOME_SCREEN_LOGO))
        val hidden = TouchpointChatOptions(hiddenElements = setOf(TouchpointHiddenElement.HEADER_RESTART))
        assertFalse(hidden.shows(TouchpointHiddenElement.HEADER_RESTART))
        assertTrue(hidden.shows(TouchpointHiddenElement.HEADER_SETTINGS))
    }

    @Test
    fun `welcomeScreenLogo false is web's legacy spelling of hiding the welcome logo`() {
        val legacy = TouchpointChatOptions(welcomeScreenLogo = false)
        assertFalse(legacy.shows(TouchpointHiddenElement.WELCOME_SCREEN_LOGO))
        assertTrue(legacy.shows(TouchpointHiddenElement.HEADER_RESTART))
        assertFalse(TouchpointChatOptions(welcomeScreenLogo = true, hiddenElements = setOf(TouchpointHiddenElement.WELCOME_SCREEN_LOGO)).shows(TouchpointHiddenElement.WELCOME_SCREEN_LOGO))
    }

    @Test
    fun `by the guide's defaults agents are in bubbles under their avatar and name, the assistant plain`() {
        val d = TouchpointChatOptions.Default
        assertTrue(d.agentMessageBubble)
        assertFalse(d.assistantMessageBubble)
        assertTrue(d.agentParticipantInfo)
        assertFalse(d.showParticipantInfo)
        assertTrue(d.showParticipantNames)
        assertNotEquals(d, TouchpointChatOptions(showParticipantNames = false))
        assertNotEquals(d, TouchpointChatOptions(assistantMessageBubble = true))
        assertNotEquals(d, TouchpointChatOptions(agentParticipantInfo = false))
    }

    @Test
    fun `the new options count in equality, so a change redraws the chat`() {
        assertEquals(TouchpointChatOptions(addedElements = setOf(TouchpointAddedElement.HEADER_CLOSE)), TouchpointChatOptions(addedElements = setOf(TouchpointAddedElement.HEADER_CLOSE)))
        assertNotEquals(TouchpointChatOptions.Default, TouchpointChatOptions(addedElements = setOf(TouchpointAddedElement.HEADER_CLOSE)))
        assertNotEquals(TouchpointChatOptions.Default, TouchpointChatOptions(hiddenElements = setOf(TouchpointHiddenElement.HEADER_SETTINGS)))
    }
}

/** The chat beside a call the host holds. */
class ChatBesideACallTest {
    @Test
    fun `during a call the chat sends nothing, so it neither offers to nor waits for a reply`() {
        val connected = TranscriptState(contactState = com.amazon.connect.touchpoint.core.ContactState.Connected)
        assertTrue(connected.canSend)
        assertFalse(connected.copy(inCall = true).canSend)
    }
}
