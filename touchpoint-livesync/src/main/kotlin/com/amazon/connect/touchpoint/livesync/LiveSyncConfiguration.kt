package com.amazon.connect.touchpoint.livesync

import com.amazon.connect.touchpoint.core.LiveSyncChannel
import com.amazon.connect.touchpoint.core.LiveSyncCredentials
import java.net.URLEncoder

/**
 * Connection details for a Live Sync session — the parts that are *not* credentials.
 *
 * Credentials (`deploymentKey`, `apiKey`) come from a [com.amazon.connect.touchpoint.core.LiveSyncCredentialSource]
 * on every connection attempt and every context POST, because the key lives in the socket URL and a
 * rotated key can only reach a new socket. The hosts are derived from [region] and [stage]:
 * `ws.nlu.acxd.connect[-stage].<region>.amazonaws.com` for the receive-only action socket and the
 * same host without the `ws.` prefix for the context API.
 *
 * @property region AWS region of the Amazon Connect instance, e.g. `us-west-2`.
 * @property conversationId Amazon Connect contact ID of the conversation to mirror. Sent as `conversationId`.
 * @property channel The Live Sync channel, derived from a `Locale` (Core canonicalises the tag).
 * @property stage A non-production stage suffix, for testing the SDK itself. Leave it `null`: an
 *   app always uses production.
 * @property userAgent `User-Agent` sent with the socket handshake and the context request; `null`
 *   leaves the HTTP client's default in place.
 */
public data class LiveSyncConfiguration(
    val region: String,
    val conversationId: String,
    val channel: LiveSyncChannel,
    val stage: String? = null,
    val userAgent: String? = null,
) {
    /** `connect-<languageTag>`. */
    public val channelKey: String get() = channel.key

    internal val stageSuffix: String get() = if (stage.isNullOrEmpty()) "" else "-$stage"

    /** Host of the receive-only action socket. */
    public val socketHost: String get() = "ws.nlu.acxd.connect$stageSuffix.$region.amazonaws.com"

    /** Host of the context API — the socket host without the `ws.` prefix. */
    public val apiHost: String get() = "nlu.acxd.connect$stageSuffix.$region.amazonaws.com"

    internal fun socketUrl(credentials: LiveSyncCredentials): String {
        val query =
            listOf(
                "deploymentKey" to credentials.deploymentKey,
                "channelKey" to channelKey,
                "apiKey" to credentials.apiKey,
                "conversationId" to conversationId,
                "type" to "voice-plus",
            ).joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        return "wss://$socketHost/?$query"
    }

    internal fun contextUrl(credentials: LiveSyncCredentials): String =
        "https://$apiHost/nlu/c/${encode(credentials.deploymentKey)}/${encode(channelKey)}/context"

    internal fun validate(credentials: LiveSyncCredentials) {
        val required = listOf("region" to region, "deploymentKey" to credentials.deploymentKey, "apiKey" to credentials.apiKey, "conversationId" to conversationId)
        for ((name, value) in required) {
            if (value.isEmpty()) throw LiveSyncException.InvalidConfiguration("`$name` must not be empty")
        }
        // A region or stage becomes part of a host name: anything else would put the request somewhere
        // unintended, or fail inside the HTTP client. Case is not significant in a host name.
        for ((name, value) in listOf("region" to region, "stage" to (stage ?: ""))) {
            if (value.isNotEmpty() && !HOST_LABEL.matches(value)) throw LiveSyncException.InvalidConfiguration("`$name` is not a host label")
        }
        // A header value outside printable ASCII is refused by the HTTP client, on every attempt.
        if (userAgent != null && userAgent.any { it !in ' '..'~' }) throw LiveSyncException.InvalidConfiguration("`userAgent` must be printable ASCII")
    }

    // The conversation id is a contact id: redacting it would make a log useless, and it is no secret.
    override fun toString(): String = "LiveSyncConfiguration(region=$region, conversationId=$conversationId, channel=$channelKey, stage=$stage)"

    private companion object {
        val HOST_LABEL = Regex("[A-Za-z0-9-]+")

        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    }
}
