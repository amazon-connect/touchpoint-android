package com.amazon.connect.touchpoint.core

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class LiveSyncChannelMappingTest {
    private fun tag(tag: String): Locale = Locale.forLanguageTag(tag)

    @Test
    fun `en-US maps to connect-en-US`() {
        val channel = LiveSyncChannel.from(tag("en-US"))
        assertEquals("en-US", channel.languageTag)
        assertEquals("connect-en-US", channel.key)
    }

    @Test
    fun `en and en-US are different channels`() {
        val en = LiveSyncChannel.from(tag("en"))
        assertEquals("en", en.languageTag)
        assertEquals("connect-en", en.key)
        assertNotEquals(LiveSyncChannel.from(tag("en-US")), en)
    }

    @Test
    fun `POSIX-style components are composed as BCP 47`() {
        val posix = Locale.Builder().setLanguage("en").setRegion("US").build()
        assertEquals("en-US", LiveSyncChannel.from(posix).languageTag)
    }

    @Test
    fun `region is preserved`() {
        assertEquals("fr-CA", LiveSyncChannel.from(tag("fr-CA")).languageTag)
    }

    @Test
    fun `numeric region is preserved`() {
        assertEquals("es-419", LiveSyncChannel.from(tag("es-419")).languageTag)
    }

    @Test
    fun `script subtag is preserved`() {
        assertEquals("zh-Hant-TW", LiveSyncChannel.from(tag("zh-Hant-TW")).languageTag)
    }

    @Test
    fun `Unicode extensions do not leak into the channel`() {
        assertEquals("en-US", LiveSyncChannel.from(tag("en-US-u-ca-gregory")).languageTag)
        assertEquals("en-US", LiveSyncChannel.from(tag("en-US-u-cu-usd")).languageTag)
        assertEquals("en-US", LiveSyncChannel.from(tag("en-US-x-private")).languageTag)
    }

    @Test
    fun `case is canonicalised - lowercase language, titlecase script, uppercase region`() {
        assertEquals("en-US", LiveSyncChannel.from(tag("EN-us")).languageTag)
        assertEquals("zh-Hant-TW", LiveSyncChannel.from(tag("zh-hant-tw")).languageTag)
    }

    @Test
    fun `variant subtags are dropped - the deployment channel set never carries them`() {
        assertEquals("de-DE", LiveSyncChannel.from(tag("de-DE-1996")).languageTag)
    }

    @Test
    fun `legacy ISO language codes map to their BCP 47 replacements`() {
        // Android's Locale.getLanguage() still reports iw/in for these; the deployment is keyed on he/id.
        assertEquals("he-IL", LiveSyncChannel.from(Locale.Builder().setLanguage("iw").setRegion("IL").build()).languageTag)
        assertEquals("id-ID", LiveSyncChannel.from(Locale.Builder().setLanguage("in").setRegion("ID").build()).languageTag)
    }

    @Test
    fun `host override wins over the derived tag`() {
        val channel = LiveSyncChannel.from(tag("en-GB"), overrides = mapOf("en-GB" to "en-US"))
        assertEquals("en-US", channel.languageTag)
        assertEquals("connect-en-US", channel.key)
    }

    @Test
    fun `overrides are matched against the canonical tag, not the raw identifier`() {
        val channel = LiveSyncChannel.from(tag("en-gb-u-ca-gregory"), overrides = mapOf("en-GB" to "en-US"))
        assertEquals("en-US", channel.languageTag)
    }

    @Test
    fun `the root locale is rejected rather than silently defaulted`() {
        assertFailsWith<LiveSyncChannel.MappingException.UndeterminedLanguage> { LiveSyncChannel.from(Locale.ROOT) }
    }

    @Test
    fun `the undetermined language und is rejected`() {
        assertFailsWith<LiveSyncChannel.MappingException.UndeterminedLanguage> { LiveSyncChannel.from(tag("und")) }
    }

    @Test
    fun `private-use and irregular tags name no language and are rejected`() {
        assertFailsWith<LiveSyncChannel.MappingException.UndeterminedLanguage> { LiveSyncChannel.from(tag("x-whatever")) }
        assertFailsWith<LiveSyncChannel.MappingException.UndeterminedLanguage> { LiveSyncChannel.from(tag("i-enochian")) }
    }

    @Test
    fun `extended-language and grandfathered tags map to their preferred language`() {
        assertEquals("yue-HK", LiveSyncChannel.from(tag("zh-yue-HK")).languageTag)
        assertEquals("tlh", LiveSyncChannel.from(tag("i-klingon")).languageTag)
        assertEquals("nan", LiveSyncChannel.from(tag("zh-min-nan")).languageTag)
    }

    @Test
    fun `an override to an empty or blank string is rejected`() {
        assertFailsWith<LiveSyncChannel.MappingException.InvalidOverride> {
            LiveSyncChannel.from(tag("en-US"), overrides = mapOf("en-US" to ""))
        }
        assertFailsWith<LiveSyncChannel.MappingException.InvalidOverride> {
            LiveSyncChannel.from(tag("en-US"), overrides = mapOf("en-US" to "  "))
        }
    }
}
