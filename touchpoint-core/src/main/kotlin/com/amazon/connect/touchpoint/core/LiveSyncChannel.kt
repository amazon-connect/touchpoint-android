package com.amazon.connect.touchpoint.core

import java.util.Locale

/**
 * The Live Sync channel a conversation targets, derived from a [Locale].
 *
 * The backend's channel key is `connect-<languageTag>` and must match the deployment exactly: `en`
 * and `en-US` are **different channels**, and a mismatch surfaces only as a rejected context
 * request. So the mapping is explicit, host-overridable, and never defaults silently.
 *
 * The tag is composed from the locale's language, script and region subtags in canonical BCP 47 case
 * (`zh-Hant-TW`), so Unicode extensions (`-u-ca-gregory`) and variants (`-1996`) never leak into the
 * channel. It is read from [Locale.toLanguageTag], never [Locale.getLanguage]: on Android the latter
 * still reports the legacy ISO codes `iw`, `in` and `ji`, while a deployment is keyed on BCP 47's
 * `he`, `id` and `yi`.
 */
public class LiveSyncChannel private constructor(
    /** Canonical BCP 47 tag as the deployment expects it, e.g. `en-US`, `fr-CA`, `zh-Hant-TW`. */
    public val languageTag: String,
) {
    /** Wire-level channel key: `connect-<languageTag>`. */
    public val key: String get() = "connect-$languageTag"

    /** Why a locale could not be mapped to a channel. */
    public sealed class MappingException(message: String) : Exception(message) {
        /** The locale carries no usable language subtag (empty, or `und`), so no channel can be chosen. */
        public class UndeterminedLanguage : MappingException("locale has no language subtag") {
            override fun equals(other: Any?): Boolean = other is UndeterminedLanguage

            override fun hashCode(): Int = UndeterminedLanguage::class.hashCode()
        }

        /** A host override mapped the locale to an empty or whitespace-only tag. */
        public class InvalidOverride : MappingException("override maps to an empty channel language") {
            override fun equals(other: Any?): Boolean = other is InvalidOverride

            override fun hashCode(): Int = InvalidOverride::class.hashCode()
        }
    }

    override fun equals(other: Any?): Boolean = other is LiveSyncChannel && other.languageTag == languageTag

    override fun hashCode(): Int = languageTag.hashCode()

    override fun toString(): String = "LiveSyncChannel($key)"

    public companion object {
        /**
         * @param locale The locale to map.
         * @param overrides Host-supplied map from a **canonical** tag (as this type would derive it) to
         *   the deployment's channel language. Lets a host route `en-GB` devices to an `en-US`
         *   deployment without touching the device locale.
         * @throws MappingException when no channel can be chosen.
         */
        public fun from(locale: Locale, overrides: Map<String, String> = emptyMap()): LiveSyncChannel {
            val derived = canonicalTag(locale)
            val override = overrides[derived] ?: return LiveSyncChannel(derived)
            val trimmed = override.trim()
            if (trimmed.isEmpty()) throw MappingException.InvalidOverride()
            return LiveSyncChannel(trimmed)
        }

        private fun canonicalTag(locale: Locale): String {
            // toLanguageTag() is well-formed BCP 47: language, then an optional 4-letter script, then
            // an optional 2-letter or 3-digit region, then anything we deliberately drop.
            val subtags = locale.toLanguageTag().split('-')
            val language = subtags.first().lowercase(Locale.ROOT)
            // A language subtag is 2–8 letters. `und` is "undetermined"; `x` opens a private-use tag
            // (Java maps irregular tags such as i-enochian to x-i-enochian), which names no language.
            if (language.length !in 2..8 || !language.all(Char::isLetter) || language == "und") {
                throw MappingException.UndeterminedLanguage()
            }

            val parts = mutableListOf(language)
            var next = 1
            subtags.getOrNull(next)?.takeIf { it.length == 4 && it.all(Char::isLetter) }?.let { script ->
                parts += script.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
                next += 1
            }
            subtags.getOrNull(next)?.takeIf { isRegion(it) }?.let { parts += it.uppercase(Locale.ROOT) }
            return parts.joinToString("-")
        }

        private fun isRegion(subtag: String): Boolean =
            (subtag.length == 2 && subtag.all(Char::isLetter)) || (subtag.length == 3 && subtag.all(Char::isDigit))
    }
}
